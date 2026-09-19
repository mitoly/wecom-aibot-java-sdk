package com.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.aibot.model.*;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 企业微信长连接客户端。异步发送只在有效 ACK 后成功；同步入口等待同一可靠核心。
 * close/disconnect 是不可重启的终态。回调运行于独立有界执行器。
 */
public class WeComAiBotClient implements AutoCloseable {
    private final Options options;
    private final AiBotLogger log;
    private final ObjectMapper mapper=new ObjectMapper();
    private final EventEmitter emitter=new EventEmitter();
    private final ThreadPoolExecutor callbacks;
    private final ConnectionManager connection;
    private final MediaTransfer media;
    private final java.util.concurrent.atomic.AtomicLong rejectedCallbacks=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.function.LongSupplier clock=System::nanoTime;

    public WeComAiBotClient(Options options) throws IOException {this(options,null);}
    public WeComAiBotClient(Options options,AiBotLogger logger) throws IOException {
        this.options=Objects.requireNonNull(options,"options").snapshot();
        this.log=logger==null?new AiBotLogger.Slf4jLogger():logger;
        callbacks=new ThreadPoolExecutor(this.options.getCallbackThreads(),this.options.getCallbackThreads(),0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(this.options.getCallbackQueueSize()),ConnectionManager.threadFactory("wecom-callback"),new ThreadPoolExecutor.AbortPolicy());
        emitter.setErrorCallback((event,error) -> log.error("事件处理失败: {}",event,error));
        OkHttpClient http=new OkHttpClient.Builder().connectTimeout(this.options.getConnectTimeoutMs(),TimeUnit.MILLISECONDS).pingInterval(0,TimeUnit.SECONDS).build();
        connection=new ConnectionManager(this.options,mapper,http,this::emit,this::dispatch,http::newWebSocket,clock,log);
        media=new MediaTransfer(this,this.options);
        connection.termination().whenComplete((value,error) -> callbacks.shutdown());
    }
    /** 注册事件；返回值可幂等注销。请把耗时业务安排到调用方自己的执行器。 */
    public EventEmitter.Disposable on(String event,EventEmitter.Handler handler) {return emitter.on(event,handler);}
    /** 接管 handler 异常回调（默认仅记日志）；参数为 (事件名, 异常)，回调内请勿抛出。 */
    public void setEventErrorHandler(java.util.function.BiConsumer<String,Exception> callback) {emitter.setErrorCallback(callback);}
    /** 物理连接已经打开；业务发送应检查 getState()==READY。 */
    public boolean isConnected() {return getState()==BotConnectionState.AUTHENTICATING || getState()==BotConnectionState.READY;}
    public long getRejectedCallbackCount() {return rejectedCallbacks.get();}
    public BotConnectionState getState() {return connection.state();}
    public static String generateReqId(String prefix) {return prefix+"_"+UUID.randomUUID().toString();}
    /** 首次认证成功时完成；后续连接变化通过生命周期事件报告。 */
    public CompletionStage<Void> startAsync() {return connection.start();}
    /** 阻塞运行至关闭/失败/被替代；被中断时统一关闭资源并保留中断标记。 */
    public void run() throws InterruptedException {
        startAsync();
        try {connection.termination().toCompletableFuture().get();}
        catch(ExecutionException e){log.warn("客户端进入终态: {}",getState());}
        catch(InterruptedException e){close();Thread.currentThread().interrupt();throw e;}
    }
    private void callback(Frame frame,Runnable action) {
        try {callbacks.execute(action);}
        catch(RejectedExecutionException e) {
            rejectedCallbacks.incrementAndGet();log.error("回调队列已满或客户端关闭；请检查 getRejectedCallbackCount");
            // 逐条可见化被丢的回调（含 msgid），不再只有汇总计数；emit 同步执行，不经 callbacks，不会递归。
            String msgid=frame!=null&&frame.getBody()!=null?frame.getBody().path("msgid").asText():null;
            emitter.emit(Constants.EVENT_ERROR,frame,new AiBotException(AiBotException.Code.QUEUE_FULL,"回调队列已满，回调已丢弃"+(msgid==null||msgid.isEmpty()?"":"（msgid="+msgid+"）")));
        }
    }
    private void emit(String event,Object payload) {callback(null,() -> emitter.emit(event,null,payload));}
    private void dispatch(Frame frame) {
        callback(frame,() -> {
            try {
                if(Constants.CMD_MSG_CALLBACK.equals(frame.getCmd())) {
                    MsgCallbackBody body=mapper.treeToValue(frame.getBody(),MsgCallbackBody.class);
                    emitter.emit(Constants.EVENT_MESSAGE,frame,body);
                    emitter.emit("message."+body.getMsgType(),frame,body);
                } else {
                    EventCallbackBody body=mapper.treeToValue(frame.getBody(),EventCallbackBody.class);
                    emitter.emit(Constants.EVENT_EVENT,frame,body);
                    if(body.getEvent()!=null)emitter.emit("event."+body.getEvent().getEventType(),frame,body);
                }
            } catch(Exception e){emitter.emit(Constants.EVENT_ERROR,frame,new AiBotException(AiBotException.Code.PROTOCOL_ERROR,"回调解析失败",e));}
        });
    }
    /** 高级发送入口；回调回复请使用 replyAsync，以便透传 req_id 与检查上下文。 */
    public CompletionStage<Frame> sendAsync(String cmd,Object body) {
        try {
            if(Constants.CMD_RESPOND_MSG.equals(cmd)||Constants.CMD_RESPOND_UPDATE_MSG.equals(cmd)||Constants.CMD_RESPOND_WELCOME_MSG.equals(cmd)
                    ||Constants.CMD_SUBSCRIBE.equals(cmd)||Constants.CMD_PING.equals(cmd))ProtocolValidator.invalid("该命令由连接管理或回调回复入口处理");
            JsonNode json=mapper.valueToTree(body); ProtocolValidator.body(cmd,json);
            String target=Constants.CMD_SEND_MSG.equals(cmd)?target(json):null;
            // 主动推送使用新生成 req_id，不绑定回调连接代；-1 跳过代校验，重连瞬间不受 STALE_CONTEXT 误杀。
            return connection.request(cmd,generateReqId(cmd),json,target,-1,Long.MAX_VALUE);
        } catch(Exception e){return SdkFutures.failed(e);}
    }
    @Deprecated
    public Frame send(String cmd,Object body) throws IOException,TimeoutException,InterruptedException {return SdkFutures.await(sendAsync(cmd,body));}
    public CompletionStage<Frame> replyAsync(Frame callback,ReplyBody body) {return replyCommand(callback,body,Constants.CMD_RESPOND_MSG);}
    private CompletionStage<Frame> replyCommand(Frame callback,Object body,String cmd) {
        try {
            if(callback==null || callback.getHeaders()==null || callback.getBody()==null || !connection.clientId().equals(callback.getClientId()))ProtocolValidator.invalid("必须使用当前客户端收到的原始回调帧");
            String reqId=callback.getHeaders().getReqId();
            ProtocolValidator.text(mapper.valueToTree(reqId),256,true,"req_id");
            JsonNode json=mapper.valueToTree(body); ProtocolValidator.body(cmd,json);
            String event=callback.getBody().path("event").path("eventtype").asText();
            long window;
            if(Constants.CMD_RESPOND_MSG.equals(cmd)) {
                if(!Constants.CMD_MSG_CALLBACK.equals(callback.getCmd()))ProtocolValidator.invalid("普通回复只能用于消息回调");
                window=TimeUnit.HOURS.toNanos(24);
            } else {
                String required=Constants.CMD_RESPOND_WELCOME_MSG.equals(cmd)?Constants.EVENT_TYPE_ENTER_CHAT:Constants.EVENT_TYPE_TEMPLATE_CARD;
                if(!Constants.CMD_EVENT_CALLBACK.equals(callback.getCmd()) || !required.equals(event))ProtocolValidator.invalid("命令与事件类型不匹配");
                if(Constants.CMD_RESPOND_UPDATE_MSG.equals(cmd)) {
                    JsonNode eventBody=callback.getBody().path("event");
                    String task=eventBody.path("template_card_event").path("task_id").asText(eventBody.path("task_id").asText());
                    if(task.isEmpty() || !task.equals(json.path("template_card").path("task_id").asText()))ProtocolValidator.invalid("更新卡片 task_id 必须与点击事件一致");
                }
                window=TimeUnit.SECONDS.toNanos(5);
            }
            return connection.request(cmd,reqId,json,callbackTarget(callback.getBody()),callback.getGeneration(),callback.getReceivedNanos()+window);
        } catch(Exception e){return SdkFutures.failed(e);}
    }
    private static String callbackTarget(JsonNode body) throws AiBotException {
        boolean group="group".equals(body.path("chattype").asText());
        String id=group?body.path("chatid").asText():body.path("from").path("userid").asText();
        if(id.isEmpty())ProtocolValidator.invalid("回调缺少会话目标");
        return id;
    }
    private static String target(JsonNode body) {
        // 相同 id 共用额度，避免通过切换 chat_type 绕过本地限流。
        return body.path("chatid").asText();
    }
    @Deprecated
    public void reply(Frame callback,ReplyBody body) throws IOException {SdkFutures.awaitIo(replyAsync(callback,body));}
    /** 一次性纯文本通过 finish=true 的 stream 发送。 */
    public CompletionStage<Frame> replyTextAsync(Frame callback,String content) {return replyStreamAsync(callback,generateReqId("stream"),content,true);}
    @Deprecated
    public void replyText(Frame callback,String content) throws IOException {SdkFutures.awaitIo(replyTextAsync(callback,content));}
    public CompletionStage<Frame> replyMarkdownAsync(Frame callback,String content) {
        ReplyBody body=new ReplyBody();body.setMsgType("markdown");body.setMarkdown(new MarkdownContent(content));return replyAsync(callback,body);
    }
    @Deprecated
    public void replyMarkdown(Frame callback,String content) throws IOException {SdkFutures.awaitIo(replyMarkdownAsync(callback,content));}
    public CompletionStage<Frame> replyStreamAsync(Frame callback,String id,String content,boolean finish) {return replyStreamAsync(callback,id,content,finish,null);}
    public CompletionStage<Frame> replyStreamAsync(Frame callback,String id,String content,boolean finish,ReplyFeedback feedback) {
        ReplyBody body=new ReplyBody();body.setMsgType("stream");StreamContent stream=new StreamContent(id,finish,content);stream.setFeedback(feedback);body.setStream(stream);return replyAsync(callback,body);
    }
    @Deprecated
    public void replyStream(Frame callback,String id,String content,boolean finish) throws IOException {SdkFutures.awaitIo(replyStreamAsync(callback,id,content,finish));}
    public CompletionStage<Frame> replyTemplateCardAsync(Frame callback,TemplateCard card) {ReplyBody body=new ReplyBody();body.setMsgType("template_card");body.setTemplateCard(card);return replyAsync(callback,body);}
    @Deprecated
    public void replyTemplateCard(Frame callback,TemplateCard card) throws IOException {SdkFutures.awaitIo(replyTemplateCardAsync(callback,card));}
    public CompletionStage<Frame> replyWelcomeAsync(Frame callback,ReplyBody body) {return replyCommand(callback,body,Constants.CMD_RESPOND_WELCOME_MSG);}
    @Deprecated
    public void replyWelcome(Frame callback,ReplyBody body) throws IOException {SdkFutures.awaitIo(replyWelcomeAsync(callback,body));}
    public CompletionStage<Frame> updateTemplateCardAsync(Frame callback,TemplateCard card,List<String> userIds) {
        UpdateCardBody body=new UpdateCardBody("update_template_card",card);body.setUserIds(userIds);return replyCommand(callback,body,Constants.CMD_RESPOND_UPDATE_MSG);
    }
    public CompletionStage<Frame> updateTemplateCardAsync(Frame callback,TemplateCard card) {return updateTemplateCardAsync(callback,card,null);}
    @Deprecated
    public void updateTemplateCard(Frame callback,TemplateCard card) throws IOException {SdkFutures.awaitIo(updateTemplateCardAsync(callback,card));}
    @Deprecated
    public void updateTemplateCard(Frame callback,TemplateCard card,List<String> userIds) throws IOException {SdkFutures.awaitIo(updateTemplateCardAsync(callback,card,userIds));}
    public CompletionStage<Frame> sendMessageAsync(SendMsgBody body) {return sendAsync(Constants.CMD_SEND_MSG,body);}
    @Deprecated
    public void sendMessage(SendMsgBody body) throws IOException,TimeoutException,InterruptedException {SdkFutures.await(sendMessageAsync(body));}
    public CompletionStage<Frame> sendMarkdownAsync(String chatId,int chatType,String content) {
        SendMsgBody body=new SendMsgBody();body.setChatId(chatId);body.setChatType(chatType);body.setMsgType("markdown");body.setMarkdown(new MarkdownContent(content));return sendMessageAsync(body);
    }
    @Deprecated
    public void sendMarkdown(String chatId,int chatType,String content) throws IOException,TimeoutException,InterruptedException {SdkFutures.await(sendMarkdownAsync(chatId,chatType,content));}
    public CompletionStage<Frame> replyMediaAsync(Frame callback,String type,String mediaId) {return replyMediaAsync(callback,type,mediaId,null,null);}
    public CompletionStage<Frame> replyMediaAsync(Frame callback,String type,String mediaId,String title,String description) {
        if(type==null)return SdkFutures.failed(new AiBotException(AiBotException.Code.INVALID_ARGUMENT,"媒体类型为空"));
        ReplyBody body=new ReplyBody();body.setMsgType(type);MediaContent value=new MediaContent(mediaId);value.setTitle(title);value.setDescription(description);
        switch(type){case "image":body.setImage(value);break;case "file":body.setFile(value);break;case "voice":body.setVoice(value);break;case "video":body.setVideo(value);break;default:return SdkFutures.failed(new AiBotException(AiBotException.Code.INVALID_ARGUMENT,"媒体类型无效"));}
        return replyAsync(callback,body);
    }
    @Deprecated
    public void replyMedia(Frame callback,String type,String mediaId) throws IOException {SdkFutures.awaitIo(replyMediaAsync(callback,type,mediaId));}
    public CompletionStage<Frame> sendMediaMessageAsync(String chatId,int chatType,String type,String mediaId) {return sendMediaMessageAsync(chatId,chatType,type,mediaId,null,null);}
    public CompletionStage<Frame> sendMediaMessageAsync(String chatId,int chatType,String type,String mediaId,String title,String description) {
        if(type==null)return SdkFutures.failed(new AiBotException(AiBotException.Code.INVALID_ARGUMENT,"媒体类型为空"));
        SendMsgBody body=new SendMsgBody();body.setChatId(chatId);body.setChatType(chatType);body.setMsgType(type);MediaContent value=new MediaContent(mediaId);value.setTitle(title);value.setDescription(description);
        switch(type){case "image":body.setImage(value);break;case "file":body.setFile(value);break;case "voice":body.setVoice(value);break;case "video":body.setVideo(value);break;default:return SdkFutures.failed(new AiBotException(AiBotException.Code.INVALID_ARGUMENT,"媒体类型无效"));}
        return sendMessageAsync(body);
    }
    @Deprecated
    public void sendMediaMessage(String chatId,int chatType,String type,String mediaId) throws IOException,TimeoutException,InterruptedException {SdkFutures.await(sendMediaMessageAsync(chatId,chatType,type,mediaId));}
    public CompletionStage<UploadedMedia> uploadMediaAsync(String type,String filename,byte[] data) {return media.upload(type,filename,data);}
    public CompletionStage<UploadedMedia> uploadMediaAsync(String type,Path path) {return media.upload(type,path);}
    public CompletionStage<MediaUtils.DownloadResult> downloadFileAsync(String url,String aesKey) {return media.download(url,aesKey);}
    public StreamSession newStream(Frame callback) {return newStreamWithId(callback,generateReqId("stream"));}
    public StreamSession newStreamWithId(Frame callback,String id) {return new StreamSession(this,callback,id,log);}
    ObjectMapper getObjectMapper() {return mapper;}
    AiBotLogger logger() {return log;}
    StreamRegistry streamRegistry() {return connection.registry();}
    java.util.function.LongSupplier clockSource() {return clock;}
    void awaitReady(long deadline) throws IOException,InterruptedException {connection.awaitReady(deadline);}
    Options options() {return options;}
    /** 幂等终态关闭；取消媒体任务及连接，不隐式重放已发消息。 */
    @Override public void close() {connection.close();media.close();}
    public void disconnect() {close();}
}
