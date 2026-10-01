package com.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.aibot.model.Frame;
import com.wecom.aibot.model.Headers;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** 单线程生命周期调度；收帧和用户回调不共享执行器，状态只属于自己的连接代。 */
final class ConnectionManager implements AutoCloseable {
    private final Options options;
    private final java.util.function.LongSupplier clock;
    private final AiBotLogger log;
    private final ObjectMapper mapper;
    private final OkHttpClient http;
    private final BiConsumer<String, Object> event;
    private final java.util.function.Consumer<Frame> inbound;
    private final ScheduledExecutorService lifecycle;
    private final ExecutorService completions;
    private final Semaphore capacity;
    private final RateLimiter messages = new RateLimiter();
    private final RateLimiter uploads = new RateLimiter();
    interface SocketConnector { WebSocket connect(Request request, WebSocketListener listener); }
    private final SocketConnector connector;
    private final String clientId = UUID.randomUUID().toString();
    private final Object readyLock = new Object();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile BotConnectionState state = BotConnectionState.STOPPED;
    private volatile Session active;
    private long nextGeneration;
    private boolean terminalCleanupScheduled;
    private int reconnectAttempts, authFailures;
    private ScheduledFuture<?> reconnectTask;

    private static final class Session {
        final long generation;
        WebSocket socket;
        boolean authenticated, ended;
        String authId;
        ScheduledFuture<?> connectTimeout, authTimeout, heartbeat;
        int missed;
        final Set<String> pingIds = new HashSet<>();
        final Map<String, Deque<Envelope>> lanes = new HashMap<>();
        Session(long generation) { this.generation = generation; }
    }
    private static final class Envelope {
        final String cmd, reqId, target;
        final JsonNode body;
        final long generation, deadline;
        final CompletableFuture<Frame> result;
        boolean sent;
        String streamKey;
        ScheduledFuture<?> timeout;
        Envelope(String cmd, String reqId, String target, JsonNode body, long generation, long deadline, CompletableFuture<Frame> result) {
            this.cmd=cmd; this.reqId=reqId; this.target=target; this.body=body; this.generation=generation;
            this.deadline=deadline; this.result=result;
        }
        boolean finalStream() { return StreamRegistry.isFinishFrame(body); }
    }
    /** 流状态唯一记账（跨连接）；本类只管连接、队列与写 socket。 */
    private final StreamRegistry registry;

    ConnectionManager(Options options, ObjectMapper mapper, OkHttpClient http,
                      BiConsumer<String, Object> event, java.util.function.Consumer<Frame> inbound,
                      SocketConnector connector, java.util.function.LongSupplier clock, AiBotLogger log) {
        this.options=options; this.mapper=mapper; this.http=http; this.event=event; this.inbound=inbound; this.connector=connector; this.clock=clock; this.log=log;
        this.registry = new StreamRegistry(options.getMaxPendingRequests());
        capacity = new Semaphore(options.getMaxPendingRequests());
        lifecycle = Executors.newSingleThreadScheduledExecutor(threadFactory("wecom-lifecycle"));
        completions = Executors.newFixedThreadPool(2, threadFactory("wecom-completion"));
    }
    static ThreadFactory threadFactory(String name) {
        return r -> { Thread t = new Thread(r,name); t.setDaemon(true); return t; };
    }
    BotConnectionState state() { return state; }
    String clientId() { return clientId; }
    StreamRegistry registry() { return registry; }
    CompletionStage<Void> termination() { return terminated; }

    synchronized CompletionStage<Void> start() {
        if (closed.get()) return SdkFutures.failed(error(AiBotException.Code.CLOSED,"客户端已关闭"));
        if (state==BotConnectionState.STOPPED) { transition(BotConnectionState.CONNECTING); execute(this::connect); }
        return ready.thenApply(value -> value);
    }
    private boolean transition(BotConnectionState value) {
        synchronized (readyLock) {
            if(closed.get() && value!=BotConnectionState.CLOSED)return false;
            state=value; readyLock.notifyAll(); return true;
        }
    }
    private void execute(Runnable action) {
        try { lifecycle.execute(action); } catch (RejectedExecutionException ignored) { /* 仅在终态关闭后拒绝旧 listener。 */ }
    }
    private void connect() {
        if (closed.get() || terminal(state)) return;
        transition(BotConnectionState.CONNECTING);
        Session s = new Session(++nextGeneration); active=s;
        try {
            s.connectTimeout = lifecycle.schedule(() -> end(s,error(AiBotException.Code.NOT_READY,"建立连接超时"),false),options.getConnectTimeoutMs(),TimeUnit.MILLISECONDS);
            s.socket = connector.connect(new Request.Builder().url(options.getWsUrl()).build(),new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) { execute(() -> opened(s,ws)); }
                @Override public void onMessage(WebSocket ws, String text) { execute(() -> receive(s,text)); }
                @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                    // 网络中断即使发生在认证等待期也走 reconnect 预算：仅订阅 ACK 明确拒绝（errcode!=0）才计入 authFailures。
                    execute(() -> end(s,error(AiBotException.Code.NOT_READY,"WebSocket 连接中断"),false));
                }
                @Override public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code,reason); execute(() -> end(s,error(AiBotException.Code.NOT_READY,"服务端关闭连接"),false));
                }
                @Override public void onClosed(WebSocket ws, int code, String reason) {
                    execute(() -> end(s,error(AiBotException.Code.NOT_READY,"连接已关闭"),false));
                }
            });
        } catch (Exception e) { end(s,error(AiBotException.Code.NOT_READY,"无法创建连接"),false); }
    }
    private boolean current(Session s) { return active==s && !s.ended && !closed.get(); }
    private void opened(Session s, WebSocket ws) {
        if (!current(s)) { ws.cancel(); return; }
        cancel(s.connectTimeout); s.socket=ws; transition(BotConnectionState.AUTHENTICATING);
        s.authId=WeComAiBotClient.generateReqId(Constants.CMD_SUBSCRIBE);
        Map<String,String> credentials=new HashMap<>(); credentials.put("bot_id",options.getBotId()); credentials.put("secret",options.getSecret());
        s.authTimeout=lifecycle.schedule(() -> end(s,error(AiBotException.Code.UNKNOWN,"认证响应超时"),false),options.getRequestTimeoutMs(),TimeUnit.MILLISECONDS);
        try {
            write(s,new Frame(Constants.CMD_SUBSCRIBE,new Headers(s.authId),mapper.valueToTree(credentials)));
            event.accept(Constants.EVENT_CONNECTED,null);
        } catch (IOException e) { end(s,error(AiBotException.Code.SEND_FAILED,"认证帧发送失败"),true); }
    }
    private void receive(Session s, String text) {
        if (!current(s)) return;
        try {
            JsonNode raw=mapper.readTree(text);
            Frame frame=mapper.treeToValue(raw,Frame.class);
            String cmd=frame.getCmd();
            if (Constants.CMD_MSG_CALLBACK.equals(cmd) || Constants.CMD_EVENT_CALLBACK.equals(cmd)) {
                if (frame.getHeaders()==null || frame.getHeaders().getReqId()==null || frame.getBody()==null || !frame.getBody().isObject()) {
                    event.accept(Constants.EVENT_ERROR,error(AiBotException.Code.PROTOCOL_ERROR,"回调缺少 headers/body")); return;
                }
                frame.markReceived(clock.getAsLong(),s.generation,clientId);
                boolean superseded=Constants.CMD_EVENT_CALLBACK.equals(cmd) && Constants.EVENT_TYPE_DISCONNECTED.equals(frame.getBody().path("event").path("eventtype").asText());
                inbound.accept(frame);
                if (superseded) {
                    transition(BotConnectionState.SUPERSEDED);
                    end(s,error(AiBotException.Code.SUPERSEDED,"新连接已替代当前连接"),false);
                    finishTerminal(error(AiBotException.Code.SUPERSEDED,"新连接已替代当前连接"));
                }
                return;
            }
            String reqId=frame.getHeaders()==null ? null : frame.getHeaders().getReqId();
            if (reqId==null) { event.accept(Constants.EVENT_ERROR,error(AiBotException.Code.PROTOCOL_ERROR,"响应缺少 req_id")); return; }
            boolean valid=raw.has("errcode") && raw.get("errcode").isIntegralNumber() && raw.get("errcode").canConvertToInt();
            if (reqId.equals(s.authId)) {
                cancel(s.authTimeout); s.authId=null;
                if (!valid || frame.getErrCode()!=0) {
                    end(s,valid ? rejection(frame) : error(AiBotException.Code.PROTOCOL_ERROR,"认证 ACK 缺少有效 errcode"),true); return;
                }
                if(!transition(BotConnectionState.READY))return;
                s.authenticated=true; reconnectAttempts=0; authFailures=0;
                s.heartbeat=lifecycle.scheduleAtFixedRate(() -> heartbeat(s),options.getHeartbeatIntervalMs(),options.getHeartbeatIntervalMs(),TimeUnit.MILLISECONDS);
                completeControl(ready,null); event.accept(Constants.EVENT_AUTHENTICATED,null); return;
            }
            if (s.pingIds.remove(reqId)) {
                if (valid && frame.getErrCode()==0) { s.missed=0; s.pingIds.clear(); }
                else event.accept(Constants.EVENT_ERROR,error(AiBotException.Code.PROTOCOL_ERROR,"心跳 ACK 无效"));
                return;
            }
            Deque<Envelope> queue=s.lanes.get(reqId);
            if (queue==null || queue.isEmpty() || !queue.peekFirst().sent) return;
            Envelope item=queue.peekFirst();
            if (!valid) { poison(s,item,error(AiBotException.Code.UNKNOWN,"响应缺少有效 errcode，发送结果未知")); return; }
            queue.removeFirst(); cancel(item.timeout);
            if (item.streamKey!=null && item.finalStream()) registry.onFinishAck(item.streamKey,frame.getErrCode()==0);
            complete(item,frame,frame.getErrCode()==0 ? null : rejection(frame));
            if (queue.isEmpty()) s.lanes.remove(reqId); else pump(s,queue);
        } catch (Exception e) { event.accept(Constants.EVENT_ERROR,new AiBotException(AiBotException.Code.PROTOCOL_ERROR,"帧解析失败",e)); }
    }
    private void heartbeat(Session s) {
        if (!current(s) || state!=BotConnectionState.READY) return;
        if (s.missed>=options.getMaxMissedHeartbeats()) { end(s,error(AiBotException.Code.NOT_READY,"心跳 ACK 连续缺失"),false); return; }
        String reqId=WeComAiBotClient.generateReqId("ping"); s.pingIds.add(reqId); s.missed++;
        try { write(s,new Frame(Constants.CMD_PING,new Headers(reqId),null)); }
        catch (IOException e) { end(s,error(AiBotException.Code.SEND_FAILED,"心跳发送失败"),false); }
    }
    /** 主动发送（新 req_id）：不绑定回调连接代，无回复窗口；调用方保证 body 为独占树。 */
    synchronized CompletionStage<Frame> request(String cmd, String reqId, JsonNode body, String target) { return request(cmd,reqId,body,target,-1,Long.MAX_VALUE); }
    synchronized CompletionStage<Frame> request(String cmd, String reqId, JsonNode body, String target, long expectedGeneration, long deadline) {
        if (state!=BotConnectionState.READY) return SdkFutures.failed(error(terminal(state)?terminalCode():AiBotException.Code.NOT_READY,"客户端未就绪: "+state));
        if (!capacity.tryAcquire()) return SdkFutures.failed(error(AiBotException.Code.QUEUE_FULL,"全局在途请求容量已满"));
        CompletableFuture<Frame> future=new CompletableFuture<>();
        Envelope item=new Envelope(cmd,reqId,target,body,expectedGeneration,deadline,future);
        try { lifecycle.execute(() -> enqueue(item)); }
        catch (RejectedExecutionException e) { capacity.release(); return SdkFutures.failed(error(AiBotException.Code.CLOSED,"客户端已关闭")); }
        return future;
    }
    private void enqueue(Envelope item) {
        Session s=active;
        if (s==null || !current(s) || state!=BotConnectionState.READY) { complete(item,null,error(AiBotException.Code.NOT_READY,"连接未就绪")); return; }
        // generation>=0 表示回复路径透传的回调帧连接代；主动推送（新 req_id）传 -1，不参与代校验，避免与重连竞态误杀。
        if (item.generation>=0 && item.generation!=s.generation) { complete(item,null,error(AiBotException.Code.STALE_CONTEXT,"回调属于旧连接")); return; }
        if (registry.isPoisoned(item.reqId)) { complete(item,null,error(AiBotException.Code.UNKNOWN,"该 req_id 已发生不确定结果，禁止继续发送")); return; }
        if(clock.getAsLong()>=item.deadline) {complete(item,null,error(AiBotException.Code.DEADLINE_EXCEEDED,"回复窗口已过期"));return;}
        registry.cleanup(clock.getAsLong());
        Deque<Envelope> queue=s.lanes.computeIfAbsent(item.reqId,k -> new ArrayDeque<>());
        if (queue.size()>=options.getMaxReplyQueueSize()) { complete(item,null,error(AiBotException.Code.QUEUE_FULL,"回复队列已满")); return; }
        try { item.streamKey=registry.admit(item.reqId,item.body,item.deadline); }
        catch (AiBotException rejected) { if(queue.isEmpty())s.lanes.remove(item.reqId); complete(item,null,rejected); return; }
        queue.addLast(item); if(queue.size()==1)pump(s,queue);
    }
    private void pump(Session s, Deque<Envelope> queue) {
        while (!queue.isEmpty()) {
            Envelope item=queue.peekFirst();
            try {
                if (clock.getAsLong()>=item.deadline) throw error(AiBotException.Code.DEADLINE_EXCEEDED,"回复窗口已过期");
                if (registry.windowExpired(item.streamKey,clock.getAsLong())) throw error(AiBotException.Code.DEADLINE_EXCEEDED,"流刷新超过十分钟");
                if(registry.startedAt(item.streamKey)!=0 && item.body.path("stream").has("feedback"))log.warn("流式续帧携带 feedback（官方未限制首帧，放行发送）: {}",item.streamKey);
                if (item.target!=null) messages.acquire(item.target);
                if (Constants.CMD_UPLOAD_MEDIA_INIT.equals(item.cmd)) uploads.acquire(options.getBotId());
                // ACK 等待不超过剩余回复窗口：welcome/卡片更新类 5 秒窗口过期后无需傻等完整 replyAckTimeoutMs
                long windowMs=TimeUnit.NANOSECONDS.toMillis(Math.max(0,item.deadline-clock.getAsLong()));
                long timeout=ProtocolValidator.isReplyCommand(item.cmd) ? Math.max(1,Math.min(options.getReplyAckTimeoutMs(),windowMs)) : options.getRequestTimeoutMs();
                item.timeout=lifecycle.schedule(() -> poison(s,item,error(AiBotException.Code.UNKNOWN,"ACK 超时，发送结果未知；该 req_id 已禁止续发，继续回复需等待新回调")),timeout,TimeUnit.MILLISECONDS);
                write(s,new Frame(item.cmd,new Headers(item.reqId),item.body)); item.sent=true;
                registry.onSent(item.streamKey,clock.getAsLong());
                return;
            } catch (Exception e) {
                cancel(item.timeout); queue.removeFirst(); registry.onSendFailed(item.streamKey);
                complete(item,null,e);
            }
        }
        // 同一队列仅由生命周期线程修改。
        s.lanes.values().removeIf(value -> value==queue);
    }
    private void poison(Session s, Envelope item, AiBotException error) {
        if (!current(s)) return;
        Deque<Envelope> queue=s.lanes.get(item.reqId);
        if (queue==null || queue.peekFirst()!=item) return;
        s.lanes.remove(item.reqId); registry.markPoisoned(item.reqId);
        for (Envelope queued:queue) { cancel(queued.timeout); complete(queued,null,error); }
        if(registry.poisonedCount()>=options.getMaxPendingRequests())end(s,error,false);
    }
    private void complete(Envelope item, Frame value, Throwable error) {
        completionsOrInline(() -> {
            try { if(error==null)item.result.complete(value); else item.result.completeExceptionally(error); }
            finally { capacity.release(); }
        });
    }
    private void completeControl(CompletableFuture<Void> future, Throwable error) {
        completionsOrInline(() -> { if(error==null)future.complete(null); else future.completeExceptionally(error); });
    }
    /** 终态关闭后 completions 可能已 shutdown：回退到调用线程直接完成，保证 future 必达且容量必释放。 */
    private void completionsOrInline(Runnable task) {
        try { completions.execute(task); } catch (RejectedExecutionException e) { task.run(); }
    }
    private void write(Session s, Frame frame) throws IOException {
        if (!current(s) || s.socket==null) throw error(AiBotException.Code.NOT_READY,"连接已失效");
        if (!s.socket.send(mapper.writeValueAsString(frame))) throw error(AiBotException.Code.SEND_FAILED,"WebSocket 拒绝入队");
    }
    private void end(Session s, AiBotException cause, boolean authFailure) {
        if (active!=s || s.ended) return;
        s.ended=true; cancel(s.connectTimeout); cancel(s.authTimeout); cancel(s.heartbeat);
        if(s.socket!=null)s.socket.cancel();
        for(Deque<Envelope> queue:s.lanes.values()) for(Envelope item:queue) {
            cancel(item.timeout);
            // 帧已写出而 ACK 未回：结果未知，毒化 req_id 跨连接禁续发（与 ACK 超时同口径，防重复发送）
            if(item.sent)registry.markPoisoned(item.reqId);
            complete(item,null,item.sent ? new AiBotException(AiBotException.Code.UNKNOWN,"连接中断，发送结果未知；该 req_id 已禁止续发",cause) : cause);
        }
        s.lanes.clear(); event.accept(Constants.EVENT_DISCONNECTED,cause);
        if(closed.get() || terminal(state))return;
        int attempt=authFailure?++authFailures:++reconnectAttempts;
        int max=authFailure?options.getMaxAuthFailureAttempts():options.getMaxReconnectAttempts();
        if(max!=-1 && attempt>max) {
            transition(BotConnectionState.FAILED);
            finishTerminal(error(AiBotException.Code.RETRY_EXHAUSTED,authFailure?"认证重试耗尽":"连接重试耗尽")); return;
        }
        transition(BotConnectionState.BACKOFF); event.accept(Constants.EVENT_RECONNECTING,attempt);
        long upper=(long)Math.min(options.getReconnectMaxDelayMs(),options.getReconnectBaseDelayMs()*Math.pow(2,Math.min(attempt-1,30)));
        long delay=Math.max(1,(long)(upper*(0.8+ThreadLocalRandom.current().nextDouble()*0.2)));
        reconnectTask=lifecycle.schedule(this::connect,delay,TimeUnit.MILLISECONDS);
    }
    private synchronized void finishTerminal(AiBotException cause) {
        if(closed.get())return;
        terminalCleanupScheduled=true;
        if(!ready.isDone())completeControl(ready,cause);
        completeControl(terminated,cause); synchronized(readyLock){readyLock.notifyAll();}
        cancel(reconnectTask);
        // 已接收但尚未执行的 request 可能排在当前 end 后面；先让它们失败并释放容量，再关 completion。
        lifecycle.execute(() -> {
            http.dispatcher().executorService().shutdown(); http.connectionPool().evictAll();
            lifecycle.shutdown(); completions.shutdown();
        });
    }
    void awaitReady(long deadline) throws IOException, InterruptedException {
        synchronized(readyLock) {
            while(state!=BotConnectionState.READY) {
                if(terminal(state))throw error(terminalCode(),"客户端不可恢复: "+state);
                long remaining=deadline-clock.getAsLong();
                if(remaining<=0)throw error(AiBotException.Code.DEADLINE_EXCEEDED,"上传会话已过期");
                TimeUnit.NANOSECONDS.timedWait(readyLock,remaining);
            }
        }
    }
    private AiBotException.Code terminalCode() { return state==BotConnectionState.SUPERSEDED ? AiBotException.Code.SUPERSEDED : state==BotConnectionState.FAILED ? AiBotException.Code.RETRY_EXHAUSTED : AiBotException.Code.CLOSED; }
    private static boolean terminal(BotConnectionState state) { return state==BotConnectionState.CLOSED || state==BotConnectionState.FAILED || state==BotConnectionState.SUPERSEDED; }
    private static void cancel(Future<?> task) { if(task!=null)task.cancel(false); }
    private static AiBotException error(AiBotException.Code code,String message){return new AiBotException(code,message);}
    private static AiBotException rejection(Frame frame){return new AiBotException(AiBotException.Code.SERVER_REJECTED,"服务端拒绝请求，errcode="+frame.getErrCode(),frame.getErrCode(),null);}
    @Override public synchronized void close() {
        if(!closed.compareAndSet(false,true))return;
        transition(BotConnectionState.CLOSED);
        if(terminalCleanupScheduled || lifecycle.isShutdown())return;
        lifecycle.execute(() -> {
            cancel(reconnectTask); Session s=active;
            if(s!=null)end(s,error(AiBotException.Code.CLOSED,"客户端关闭"),false);
            if(!ready.isDone())completeControl(ready,error(AiBotException.Code.CLOSED,"客户端关闭"));
            completeControl(terminated,null); lifecycle.shutdown(); completions.shutdown();
            http.dispatcher().executorService().shutdown(); http.connectionPool().evictAll();
        });
    }
}
