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
    private final ExecutorService io=Executors.newFixedThreadPool(4,ConnectionManager.threadFactory("wecom-media"));
    private final ExecutorService chunks;
    private final Map<CompletableFuture<?>, Future<?>> tasks=new HashMap<>();
    private boolean closed;
    MediaTransfer(WeComAiBotClient client,Options options) {
        this.client=client;this.options=options;
        chunks=Executors.newFixedThreadPool(options.getUploadChunkConcurrency(),ConnectionManager.threadFactory("wecom-chunk"));
    }
    private synchronized <T> CompletionStage<T> submit(Callable<T> work) {
        if(closed)return SdkFutures.failed(new AiBotException(AiBotException.Code.CLOSED,"媒体传输已关闭"));
        if(tasks.size()>=4)return SdkFutures.failed(new AiBotException(AiBotException.Code.QUEUE_FULL,"媒体任务容量已满"));
        CompletableFuture<T> result=new CompletableFuture<>();
        // 在运行前登记任务，避免极快任务先结束再入表。
        FutureTask<Void> task=new FutureTask<>(() -> {
            try {result.complete(work.call());}catch(Throwable error){result.completeExceptionally(SdkFutures.unwrap(error));}
            finally {synchronized(MediaTransfer.this){tasks.remove(result);}}
            return null;
        });
        tasks.put(result,task);io.execute(task);return result;
    }
    CompletionStage<UploadedMedia> upload(String type,String filename,byte[] data) {
        try {
            if(data==null)ProtocolValidator.invalid("文件数据为空");validateUpload(type,filename,data.length);
            byte[] snapshot=data.clone();return submit(() -> transfer(type,filename,snapshot));
        }catch(Exception e){return SdkFutures.failed(e);}
    }
    CompletionStage<UploadedMedia> upload(String type,Path path) {
        return submit(() -> {
            if(path==null)throw new AiBotException(AiBotException.Code.INVALID_ARGUMENT,"文件路径为空");
            String filename=path.getFileName().toString();validateUpload(type,filename,Files.size(path));
            byte[] bytes;
            try(java.io.InputStream in=Files.newInputStream(path);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int count;
                while((count=in.read(buffer))!=-1) {if((long)out.size()+count>maximum(type))ProtocolValidator.invalid("文件读取超过类型上限");out.write(buffer,0,count);}
                bytes=out.toByteArray();
            }
            validateUpload(type,filename,bytes.length);return transfer(type,filename,bytes);
        });
    }
    CompletionStage<MediaUtils.DownloadResult> download(String url,String aesKey) {
        return submit(() -> MediaUtils.downloadFile(url,aesKey,options.getMaxDownloadBytes(),timeout(options.getConnectTimeoutMs()),timeout(options.getRequestTimeoutMs())));
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
        Frame finish=SdkFutures.awaitStrict(client.sendAsync(Constants.CMD_UPLOAD_MEDIA_FINISH,new UploadFinishBody(uploadId)));
        required(finish,"media_id");
        UploadedMedia result=client.getObjectMapper().treeToValue(finish.getBody(),UploadedMedia.class);
        if(result.getCreatedAt()==null||result.getType()==null)throw new AiBotException(AiBotException.Code.PROTOCOL_ERROR,"上传响应缺少 type/created_at");
        return result;
    }
    private void sendChunk(UploadChunkBody body,long deadline) throws Exception {
        for(int attempt=0;;attempt++) {
            try {client.awaitReady(deadline);SdkFutures.awaitStrict(client.sendAsync(Constants.CMD_UPLOAD_MEDIA_CHUNK,body));return;}
            catch(Exception e) {
                if(Thread.currentThread().isInterrupted()||e instanceof InterruptedException)throw e;
                Throwable cause=SdkFutures.unwrap(e);
                if(cause instanceof AiBotException) {
                    AiBotException.Code code=((AiBotException)cause).getCode();
                    if(code!=AiBotException.Code.NOT_READY && code!=AiBotException.Code.UNKNOWN && code!=AiBotException.Code.SEND_FAILED && code!=AiBotException.Code.SERVER_REJECTED)throw e;
                } else if(!(e instanceof TimeoutException))throw e;
                if(attempt>=options.getMaxChunkRetries())throw e;
                long remaining=deadline-System.nanoTime();if(remaining<=0)throw new AiBotException(AiBotException.Code.DEADLINE_EXCEEDED,"上传会话已过期");
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
        if(filename==null||filename.trim().isEmpty()||filename.getBytes(StandardCharsets.UTF_8).length>256||filename.contains("/")||filename.contains("\\")||filename.matches(".*[\\r\\n\\x00].*"))ProtocolValidator.invalid("文件名无效");
        if(size<5||size>max)ProtocolValidator.invalid("文件大小超出媒体类型限制");
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
        for(Map.Entry<CompletableFuture<?>,Future<?>> task:tasks.entrySet()) {
            task.getValue().cancel(true);task.getKey().completeExceptionally(new AiBotException(AiBotException.Code.CLOSED,"媒体任务已取消；已发上传可能仍在服务端存在"));
        }
        tasks.clear();io.shutdownNow();chunks.shutdownNow();
    }
}
