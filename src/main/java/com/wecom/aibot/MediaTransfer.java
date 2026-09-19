package com.wecom.aibot;

import com.wecom.aibot.model.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 有界媒体任务；分片幂等重传，初始化/结束结果未知时不重放。 */
final class MediaTransfer implements AutoCloseable {
    private final WeComAiBotClient client;
    private final Options options;
    private final AiBotLogger log;
    // 下载/上传分池：慢下载不再饿死上传（原共享 4 槽时 4 个慢下载可封死全部上传）
    private final ExecutorService downloads=Executors.newFixedThreadPool(4,ConnectionManager.threadFactory("wecom-media-download"));
    private final ExecutorService uploads=Executors.newFixedThreadPool(4,ConnectionManager.threadFactory("wecom-media-upload"));
    private final ExecutorService chunks;
    private final Map<CompletableFuture<?>, Future<?>> downloadTasks=new HashMap<>();
    private final Map<CompletableFuture<?>, Future<?>> uploadTasks=new HashMap<>();
    private boolean closed;
    MediaTransfer(WeComAiBotClient client,Options options) {
        this.client=client;this.options=options;this.log=client.logger();
        chunks=Executors.newFixedThreadPool(options.getUploadChunkConcurrency(),ConnectionManager.threadFactory("wecom-chunk"));
    }
    private synchronized <T> CompletionStage<T> submit(ExecutorService pool,Map<CompletableFuture<?>, Future<?>> registry,Callable<T> work,long maxQueueNanos) {
        if(closed)return SdkFutures.failed(new AiBotException(AiBotException.Code.CLOSED,"媒体传输已关闭"));
        if(registry.size()>=4)return SdkFutures.failed(new AiBotException(AiBotException.Code.QUEUE_FULL,"媒体任务容量已满"));
        CompletableFuture<T> result=new CompletableFuture<>();
        long enqueuedAt=System.nanoTime();
        // 在运行前登记任务，避免极快任务先结束再入表。
        FutureTask<Void> task=new FutureTask<>(() -> {
            try {
                if(maxQueueNanos>0 && System.nanoTime()-enqueuedAt>maxQueueNanos)throw new AiBotException(AiBotException.Code.DEADLINE_EXCEEDED,"下载排队超过回调媒体 url 有效期（官方 5 分钟），url 可能已过期");
                result.complete(work.call());
            }catch(Throwable error){result.completeExceptionally(SdkFutures.unwrap(error));}
            finally {synchronized(MediaTransfer.this){registry.remove(result);}}
            return null;
        });
        registry.put(result,task);pool.execute(task);return result;
    }
    CompletionStage<UploadedMedia> upload(String type,String filename,byte[] data) {
        try {
            if(data==null)ProtocolValidator.invalid("文件数据为空");validateUpload(type,filename,data.length);
            byte[] snapshot=data.clone();return submit(uploads,uploadTasks,() -> transfer(type,filename,snapshot),0);
        }catch(Exception e){return SdkFutures.failed(e);}
    }
    CompletionStage<UploadedMedia> upload(String type,Path path) {
        try {
            // 与 byte[] 重载一致：能在调用线程判定的入口校验先行，失败不占稀缺 io 槽位。
            if(path==null)ProtocolValidator.invalid("文件路径为空");
            Path fileName=path.getFileName();
            if(fileName==null)ProtocolValidator.invalid("文件路径无效: "+path);
            validateUploadMeta(type,fileName.toString());
            return submit(uploads,uploadTasks,() -> {
                byte[] bytes;
                try(java.io.InputStream in=Files.newInputStream(path);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192];int count;
                    while((count=in.read(buffer))!=-1) {if((long)out.size()+count>maximum(type))ProtocolValidator.invalid("文件读取超过类型上限");out.write(buffer,0,count);}
                    bytes=out.toByteArray();
                }
                validateUpload(type,fileName.toString(),bytes.length);return transfer(type,fileName.toString(),bytes);
            },0);
        }catch(Exception e){return SdkFutures.failed(e);}
    }
    CompletionStage<MediaUtils.DownloadResult> download(String url,String aesKey) {
        // 官方回调媒体 url 仅 5 分钟有效：排队超龄直接失败，错误与网络故障可区分
        return submit(downloads,downloadTasks,() -> MediaUtils.downloadFile(url,aesKey,options.getMaxDownloadBytes(),timeout(options.getDownloadConnectTimeoutMs()),timeout(options.getDownloadReadTimeoutMs())),TimeUnit.MINUTES.toNanos(5));
    }
    private static int timeout(long value){return (int)Math.min(Integer.MAX_VALUE,value);}
    private UploadedMedia transfer(String type,String filename,byte[] bytes) throws Exception {
        int total=(bytes.length+Constants.UPLOAD_CHUNK_SIZE-1)/Constants.UPLOAD_CHUNK_SIZE;
        byte[] hash=MessageDigest.getInstance("MD5").digest(bytes);StringBuilder md5=new StringBuilder();for(byte b:hash)md5.append(String.format("%02x",b&255));
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30);
        Frame init=SdkFutures.awaitStrict(client.sendAsync(Constants.CMD_UPLOAD_MEDIA_INIT,new UploadInitBody(type,filename,bytes.length,total,md5.toString())));
        String uploadId=required(init,"upload_id");
        AtomicInteger next=new AtomicInteger();
        List<Future<?>> workers=new ArrayList<>();
        for(int worker=0;worker<Math.min(options.getUploadChunkConcurrency(),total);worker++) {
            workers.add(chunks.submit(() -> {
                int index;
                while((index=next.getAndIncrement())<total) {
                    int start=index*Constants.UPLOAD_CHUNK_SIZE,end=Math.min(start+Constants.UPLOAD_CHUNK_SIZE,bytes.length);
                    UploadChunkBody body=new UploadChunkBody(uploadId,index,Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,start,end)));
                    sendChunk(body,deadline);
                }
                return null;
            }));
        }
        try {
            for(Future<?> worker:workers) {
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw new AiBotException(AiBotException.Code.DEADLINE_EXCEEDED,"上传会话已过期");
                worker.get(remaining,TimeUnit.NANOSECONDS);
            }
        } catch(Exception e) {for(Future<?> worker:workers)worker.cancel(true);throw e;}
        client.awaitReady(deadline);
        if(deadline-System.nanoTime()<=0)throw new AiBotException(AiBotException.Code.DEADLINE_EXCEEDED,"上传会话已过期");
        // finish 幂等性官方未声明：仅确定未发出的失败可安全重试
        Frame finish=sendWithRetry(Constants.CMD_UPLOAD_MEDIA_FINISH,new UploadFinishBody(uploadId),deadline,true);
        required(finish,"media_id");
        UploadedMedia result=client.getObjectMapper().treeToValue(finish.getBody(),UploadedMedia.class);
        if(result.getCreatedAt()==null||result.getType()==null)throw new AiBotException(AiBotException.Code.PROTOCOL_ERROR,"上传响应缺少 type/created_at");
        return result;
    }
    private void sendChunk(UploadChunkBody body,long deadline) throws Exception {
        sendWithRetry(Constants.CMD_UPLOAD_MEDIA_CHUNK,body,deadline,false);
    }
    /**
     * 媒体帧发送重试。finish 语义（幂等性官方未声明）：仅 NOT_READY/SEND_FAILED（帧确定未发出）重试，
     * 结果未知（UNKNOWN）直接失败并明确告知上传可能已在服务端完成；chunk 语义：官方明确幂等，
     * NOT_READY/UNKNOWN/SEND_FAILED/SERVER_REJECTED 均可安全重试。
     */
    private Frame sendWithRetry(String cmd,Object body,long deadline,boolean finishSemantics) throws Exception {
        for(int attempt=0;;attempt++) {
            try {client.awaitReady(deadline);return SdkFutures.awaitStrict(client.sendAsync(cmd,body));}
            catch(Exception e) {
                if(Thread.currentThread().isInterrupted()||e instanceof InterruptedException)throw e;
                Throwable cause=SdkFutures.unwrap(e);
                boolean retryable;
                if(finishSemantics) {
                    if(cause instanceof AiBotException && ((AiBotException)cause).getCode()==AiBotException.Code.UNKNOWN)
                        throw new AiBotException(AiBotException.Code.UNKNOWN,"finish 结果未知：上传可能已在服务端完成但 media_id 不可知，建议整文件重传",cause);
                    retryable=cause instanceof AiBotException && (((AiBotException)cause).getCode()==AiBotException.Code.NOT_READY||((AiBotException)cause).getCode()==AiBotException.Code.SEND_FAILED);
                } else if(cause instanceof AiBotException) {
                    AiBotException.Code code=((AiBotException)cause).getCode();
                    retryable=code==AiBotException.Code.NOT_READY||code==AiBotException.Code.UNKNOWN||code==AiBotException.Code.SEND_FAILED||code==AiBotException.Code.SERVER_REJECTED;
                } else retryable=e instanceof TimeoutException;
                if(!retryable)throw e;
                if(attempt>=options.getMaxChunkRetries())throw e;
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw new AiBotException(AiBotException.Code.DEADLINE_EXCEEDED,"上传会话已过期");
                log.warn("媒体帧发送失败进入重试（第{}次，命令={}，原因={})",attempt+1,cmd,cause.getMessage());
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(500L*(attempt+1))));
            }
        }
    }
    private static String required(Frame frame,String field) throws AiBotException {
        if(frame.getBody()==null || !frame.getBody().path(field).isTextual() || frame.getBody().path(field).asText().isEmpty())throw new AiBotException(AiBotException.Code.PROTOCOL_ERROR,"上传响应缺少 "+field);
        return frame.getBody().get(field).asText();
    }
    static void validateUpload(String type,String filename,long size) throws AiBotException {
        long max=maximum(type);
        if(size<5||size>max)ProtocolValidator.invalid("文件大小超出媒体类型限制");
        validateUploadMeta(type,filename);
    }
    /** 文件名/类型/扩展名校验（无需读文件，可在调用线程先行）。 */
    static void validateUploadMeta(String type,String filename) throws AiBotException {
        maximum(type);
        if(filename==null||filename.trim().isEmpty()||filename.getBytes(StandardCharsets.UTF_8).length>256||filename.contains("/")||filename.contains("\\")||filename.matches(".*[\\r\\n\\x00].*"))ProtocolValidator.invalid("文件名无效");
        String lower=filename.toLowerCase(Locale.ROOT);
        if("image".equals(type)&&!(lower.endsWith(".png")||lower.endsWith(".jpg")||lower.endsWith(".jpeg")||lower.endsWith(".gif")))ProtocolValidator.invalid("图片格式不支持");
        if("voice".equals(type)&&!lower.endsWith(".amr"))ProtocolValidator.invalid("语音仅支持 AMR");
        if("video".equals(type)&&!lower.endsWith(".mp4"))ProtocolValidator.invalid("视频仅支持 MP4");
    }
    private static long maximum(String type) throws AiBotException {
        if("file".equals(type))return 20*1024*1024L;
        if("image".equals(type)||"video".equals(type))return 10*1024*1024L;
        if("voice".equals(type))return 2*1024*1024L;
        throw new AiBotException(AiBotException.Code.INVALID_ARGUMENT,"媒体类型无效");
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;
        for(Map<CompletableFuture<?>,Future<?>> registry:java.util.Arrays.asList(downloadTasks,uploadTasks)) {
            for(Map.Entry<CompletableFuture<?>,Future<?>> task:registry.entrySet()) {
                task.getValue().cancel(true);task.getKey().completeExceptionally(new AiBotException(AiBotException.Code.CLOSED,"媒体任务已取消；已发上传可能仍在服务端存在"));
            }
        }
        downloadTasks.clear();uploadTasks.clear();downloads.shutdownNow();uploads.shutdownNow();chunks.shutdownNow();
    }
}
