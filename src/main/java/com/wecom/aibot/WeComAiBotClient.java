package com.wecom.aibot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.aibot.model.*;
import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 企业微信智能机器人 WebSocket 长连接客户端。
 * <p>
 * 功能：
 * <ul>
 *   <li>WebSocket 连接管理（自动重连、指数退避）</li>
 *   <li>身份认证（aibot_subscribe）</li>
 *   <li>心跳保活（fire-and-forget，不阻塞业务）</li>
 *   <li>消息/事件分发（事件总线）</li>
 *   <li>线程安全的消息发送与回复</li>
 * </ul>
 * <p>
 * 已修复的 Bug 防护：
 * <ul>
 *   <li>disconnected_event nil panic → closeConn() 幂等 + closeCh 通知</li>
 *   <li>Send() TOCTOU 竞态 → synchronized 块内原子检查+写入</li>
 *   <li>Event handler 注销错乱 → 唯一 ID + 幂等 dispose</li>
 *   <li>心跳阻塞 → fire-and-forget sendPing() 不走 pending 机制</li>
 *   <li>重连 attempt 不归零 → 连接持续 &gt; 1 分钟后重置</li>
 * </ul>
 */
public class WeComAiBotClient {

    private final Options options;
    private final AiBotLogger log;
    private final EventEmitter emitter;
    private final ObjectMapper objectMapper;

    // WebSocket 连接状态
    private volatile WebSocket webSocket;
    private final Object writeLock = new Object(); // 保护写操作，避免 TOCTOU 竞态
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile boolean closedByServer = false;

    // 待处理的请求响应（req_id -> CompletableFuture）
    private final ConcurrentHashMap<String, CompletableFuture<Frame>> pending = new ConcurrentHashMap<>();

    // 心跳定时器
    private ScheduledExecutorService heartbeatExecutor;

    // 消息分发线程池（避免使用公共 ForkJoinPool，防止阻塞操作耗尽线程）
    private final ExecutorService dispatchExecutor;

    // 运行控制
    private volatile boolean running = false;
    private final Object runLock = new Object();

    // OkHttp 客户端
    private final OkHttpClient httpClient;

    /**
     * 创建 SDK 客户端。
     *
     * @param options 配置项
     * @throws IOException 读取凭证文件失败
     */
    public WeComAiBotClient(Options options) throws IOException {
        options.validate();
        this.options = options;
        this.log = new AiBotLogger.Slf4jLogger();
        this.emitter = new EventEmitter();
        this.emitter.setErrorCallback((event, e) ->
                log.error("事件 [{}] 的 handler 抛出异常: {}", event, e.getMessage()));
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .pingInterval(0, TimeUnit.SECONDS)
                .build();
        this.dispatchExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wecom-aibot-dispatch");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 创建 SDK 客户端（自定义日志）。
     *
     * @param options 配置项
     * @param logger  自定义日志实现
     * @throws IOException 读取凭证文件失败
     */
    public WeComAiBotClient(Options options, AiBotLogger logger) throws IOException {
        options.validate();
        this.options = options;
        this.log = logger != null ? logger : new AiBotLogger.Slf4jLogger();
        this.emitter = new EventEmitter();
        this.emitter.setErrorCallback((event, e) ->
                this.log.error("事件 [{}] 的 handler 抛出异常: {}", event, e.getMessage()));
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .pingInterval(0, TimeUnit.SECONDS)
                .build();
        this.dispatchExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wecom-aibot-dispatch");
            t.setDaemon(true);
            return t;
        });
    }

    // =========================================================================
    // 公共 API
    // =========================================================================

    /**
     * 注册事件处理函数。
     *
     * @param event   事件名称（参见 {@link Constants} 中的 EVENT_* 常量）
     * @param handler 处理函数
     * @return 可取消注册的 Disposable
     */
    public EventEmitter.Disposable on(String event, EventEmitter.Handler handler) {
        return emitter.on(event, handler);
    }

    /**
     * 返回当前 WebSocket 是否已连接。
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * 生成唯一的请求 ID。
     */
    public static String generateReqId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // =========================================================================
    // 连接生命周期
    // =========================================================================

    /**
     * 建立连接并阻塞直到手动断开或超过最大重连次数。
     * 自动处理断线重连。
     *
     * @throws InterruptedException 等待过程中被中断
     */
    public void run() throws InterruptedException {
        synchronized (runLock) {
            if (running) {
                throw new IllegalStateException("客户端已在运行中");
            }
            running = true;
        }
        try {
            connectWithRetry();
        } finally {
            synchronized (runLock) {
                running = false;
            }
        }
    }

    /**
     * 带重连的连接循环。
     */
    private void connectWithRetry() throws InterruptedException {
        int attempt = 0;
        while (running) {
            long connectedAt = System.currentTimeMillis();
            closedByServer = false;

            try {
                connect();
                // connect() 正常返回说明连接已断开
            } catch (Exception e) {
                log.error("连接异常: {}", e.getMessage());
            }

            connected.set(false);

            // 如果这次连接持续了超过 1 分钟，重置重连计数器
            if (System.currentTimeMillis() - connectedAt > 60_000L) {
                attempt = 0;
            }

            // 只在非服务端踢下线时触发 disconnected 事件（避免重复通知）
            if (!closedByServer) {
                emitter.emit(Constants.EVENT_DISCONNECTED, null, null);
            }

            if (!running) {
                return;
            }

            attempt++;
            if (options.getMaxReconnectAttempts() > 0 && attempt > options.getMaxReconnectAttempts()) {
                log.error("超过最大重连次数 ({})", options.getMaxReconnectAttempts());
                return;
            }

            long delay = backoff(attempt);
            log.info("将在 {}ms 后重连 (第 {} 次)", delay, attempt);
            emitter.emit(Constants.EVENT_RECONNECTING, null, attempt);

            Thread.sleep(delay);
        }
    }

    /**
     * 执行一次完整的连接流程：拨号 → 认证 → 心跳 → 等待断开。
     */
    private void connect() throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicBoolean connectSuccess = new AtomicBoolean(false);

        Request request = new Request.Builder()
                .url(options.getWsUrl())
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected.set(true);
                connectSuccess.set(true);
                connectLatch.countDown();
                emitter.emit(Constants.EVENT_CONNECTED, null, null);
                log.info("已连接到 {}", options.getWsUrl());
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("WebSocket 连接失败: {}", t.getMessage());
                connected.set(false);
                connectLatch.countDown();
                closeLatch.countDown();
                cleanupPending();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.info("WebSocket 已关闭: {} {}", code, reason);
                connected.set(false);
                closeLatch.countDown();
                cleanupPending();
            }
        });

        // 等待连接建立
        connectLatch.await();
        if (!connectSuccess.get()) {
            throw new IOException("WebSocket 连接建立失败");
        }

        // 认证
        authenticate();

        // 启动心跳
        startHeartbeat();

        // 阻塞等待连接断开
        closeLatch.await();

        // 停止心跳
        stopHeartbeat();
    }

    /**
     * 发送订阅请求完成身份认证。
     */
    private void authenticate() throws Exception {
        log.info("正在认证 (bot_id={}, secret={})", options.getBotId(), Options.maskSecret(options.getSecret()));

        Map<String, String> body = new HashMap<>();
        body.put("bot_id", options.getBotId());
        body.put("secret", options.getSecret());

        Frame resp = send(Constants.CMD_SUBSCRIBE, body);
        if (resp.getErrCode() != 0) {
            throw new IOException("订阅被拒绝: " + resp.getErrCode() + " " + resp.getErrMsg());
        }
        log.info("认证成功 (bot_id={})", options.getBotId());
        emitter.emit(Constants.EVENT_AUTHENTICATED, null, null);
    }

    // =========================================================================
    // 心跳保活
    // =========================================================================

    /**
     * 启动心跳定时器。
     * 心跳使用 fire-and-forget 方式，不等待响应。
     */
    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wecom-aibot-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                sendPing();
            } catch (Exception e) {
                log.warn("心跳发送失败: {}", e.getMessage());
            }
        }, options.getHeartbeatIntervalMs(), options.getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    /**
     * 发送心跳帧（fire-and-forget，不等待响应）。
     * 直接写帧，不走 pending 等待机制，避免心跳阻塞业务。
     */
    private void sendPing() {
        Frame frame = new Frame();
        frame.setCmd(Constants.CMD_PING);
        frame.setHeaders(new Headers(generateReqId("ping")));

        String json;
        try {
            json = objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.error("序列化心跳帧失败: {}", e.getMessage());
            return;
        }

        // 加锁检查 + 写入，原子操作避免 TOCTOU 竞态
        synchronized (writeLock) {
            WebSocket ws = webSocket;
            if (ws == null || !connected.get()) {
                return;
            }
            ws.send(json);
        }
    }

    // =========================================================================
    // 消息处理
    // =========================================================================

    /**
     * 处理收到的 WebSocket 消息。
     */
    private void handleMessage(String text) {
        Frame frame;
        try {
            frame = objectMapper.readValue(text, Frame.class);
        } catch (Exception e) {
            log.warn("帧解析失败: {}", e.getMessage());
            return;
        }

        // 检查是否是待处理请求的响应
        if (frame.getHeaders() != null && frame.getHeaders().getReqId() != null) {
            CompletableFuture<Frame> future = pending.remove(frame.getHeaders().getReqId());
            if (future != null) {
                future.complete(frame);
                return;
            }
        }

        // 异步分发回调（使用专用线程池，防止阻塞操作耗尽公共 ForkJoinPool）
        dispatchExecutor.execute(() -> dispatch(frame));
    }

    /**
     * 根据命令类型分发帧到对应的事件处理函数。
     * 使用 try-catch 包裹，防止单个 handler 异常导致崩溃。
     */
    private void dispatch(Frame frame) {
        try {
            if (frame.getCmd() == null) {
                return;
            }
            switch (frame.getCmd()) {
                case Constants.CMD_MSG_CALLBACK:
                    dispatchMessage(frame);
                    break;
                case Constants.CMD_EVENT_CALLBACK:
                    dispatchEvent(frame);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log.error("处理函数发生异常: {}", e.getMessage());
            emitter.emit(Constants.EVENT_ERROR, frame, e);
        }
    }

    private void dispatchMessage(Frame frame) {
        try {
            if (frame.getBody() == null) {
                log.warn("消息回调 body 为空，跳过");
                return;
            }
            MsgCallbackBody body = objectMapper.treeToValue(frame.getBody(), MsgCallbackBody.class);
            if (body == null) {
                log.warn("消息回调解析结果为 null，跳过");
                return;
            }
            emitter.emit(Constants.EVENT_MESSAGE, frame, body);

            if (body.getMsgType() != null) {
                switch (body.getMsgType()) {
                    case Constants.MSG_TYPE_TEXT:
                        emitter.emit(Constants.EVENT_MESSAGE_TEXT, frame, body);
                        break;
                    case Constants.MSG_TYPE_IMAGE:
                        emitter.emit(Constants.EVENT_MESSAGE_IMAGE, frame, body);
                        break;
                    case Constants.MSG_TYPE_MIXED:
                        emitter.emit(Constants.EVENT_MESSAGE_MIXED, frame, body);
                        break;
                    case Constants.MSG_TYPE_VOICE:
                        emitter.emit(Constants.EVENT_MESSAGE_VOICE, frame, body);
                        break;
                    case Constants.MSG_TYPE_FILE:
                        emitter.emit(Constants.EVENT_MESSAGE_FILE, frame, body);
                        break;
                    case Constants.MSG_TYPE_VIDEO:
                        emitter.emit(Constants.EVENT_MESSAGE_VIDEO, frame, body);
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            log.warn("消息回调解析失败: {}", e.getMessage());
        }
    }

    private void dispatchEvent(Frame frame) {
        try {
            if (frame.getBody() == null) {
                log.warn("事件回调 body 为空，跳过");
                return;
            }
            EventCallbackBody body = objectMapper.treeToValue(frame.getBody(), EventCallbackBody.class);
            if (body == null) {
                log.warn("事件回调解析结果为 null，跳过");
                return;
            }
            emitter.emit(Constants.EVENT_EVENT, frame, body);

            if (body.getEvent() != null && body.getEvent().getEventType() != null) {
                switch (body.getEvent().getEventType()) {
                    case Constants.EVENT_TYPE_ENTER_CHAT:
                        emitter.emit(Constants.EVENT_ENTER_CHAT, frame, body);
                        break;
                    case Constants.EVENT_TYPE_TEMPLATE_CARD:
                        emitter.emit(Constants.EVENT_TEMPLATE_CARD, frame, body);
                        break;
                    case Constants.EVENT_TYPE_FEEDBACK:
                        emitter.emit(Constants.EVENT_FEEDBACK, frame, body);
                        break;
                    case Constants.EVENT_TYPE_DISCONNECTED:
                        // 被服务端踢下线：先触发事件，再关闭连接
                        log.warn("收到服务端断开通知 (disconnected_event)");
                        closedByServer = true;
                        emitter.emit(Constants.EVENT_DISCONNECTED, frame, body);
                        closeConn();
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            log.warn("事件回调解析失败: {}", e.getMessage());
        }
    }

    // =========================================================================
    // 发送与回复
    // =========================================================================

    /**
     * 发送一个帧并等待服务端响应（通过 req_id 匹配）。
     *
     * @param cmd  命令类型
     * @param body 消息体对象
     * @return 服务端响应帧
     * @throws IOException          连接已关闭或写入失败
     * @throws TimeoutException     等待响应超时
     * @throws InterruptedException 等待过程中被中断
     */
    public Frame send(String cmd, Object body) throws IOException, TimeoutException, InterruptedException {
        String reqId = generateReqId(cmd);

        JsonNode bodyNode = null;
        if (body != null) {
            bodyNode = objectMapper.valueToTree(body);
        }

        Frame frame = new Frame(cmd, new Headers(reqId), bodyNode);
        String json;
        try {
            json = objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IOException("序列化帧失败: " + e.getMessage(), e);
        }

        CompletableFuture<Frame> future = new CompletableFuture<>();
        pending.put(reqId, future);

        // 加锁检查 + 写入，原子操作避免 TOCTOU 竞态
        synchronized (writeLock) {
            WebSocket ws = webSocket;
            if (ws == null || !connected.get()) {
                pending.remove(reqId);
                throw new IOException("连接已关闭");
            }
            boolean sent = ws.send(json);
            if (!sent) {
                pending.remove(reqId);
                throw new IOException("写入失败：WebSocket 发送队列已满");
            }
        }

        try {
            return future.get(options.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(reqId);
            throw new TimeoutException("等待 " + reqId + " 的响应超时");
        } catch (ExecutionException e) {
            pending.remove(reqId);
            throw new IOException("等待响应失败: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            pending.remove(reqId);
            Thread.currentThread().interrupt(); // 保留中断标记
            throw e;
        }
    }

    /**
     * 使用回调帧的 req_id 发送回复（不等待响应）。
     */
    private void sendReply(String cmd, String reqId, Object body) throws IOException {
        JsonNode bodyNode = objectMapper.valueToTree(body);
        Frame frame = new Frame(cmd, new Headers(reqId), bodyNode);
        String json;
        try {
            json = objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IOException("序列化帧失败: " + e.getMessage(), e);
        }

        // 加锁检查 + 写入，原子操作
        synchronized (writeLock) {
            WebSocket ws = webSocket;
            if (ws == null || !connected.get()) {
                throw new IOException("连接已关闭");
            }
            ws.send(json);
        }
    }

    /**
     * 向回调帧发送通用回复。
     */
    public void reply(Frame callbackFrame, ReplyBody body) throws IOException {
        sendReply(Constants.CMD_RESPOND_MSG, callbackFrame.getHeaders().getReqId(), body);
    }

    /**
     * 回复纯文本消息。
     */
    public void replyText(Frame callbackFrame, String content) throws IOException {
        ReplyBody body = new ReplyBody();
        body.setMsgType(Constants.MSG_TYPE_TEXT);
        body.setText(new TextContent(content));
        reply(callbackFrame, body);
    }

    /**
     * 回复 Markdown 格式消息。
     */
    public void replyMarkdown(Frame callbackFrame, String content) throws IOException {
        ReplyBody body = new ReplyBody();
        body.setMsgType(Constants.MSG_TYPE_MARKDOWN);
        body.setMarkdown(new MarkdownContent(content));
        reply(callbackFrame, body);
    }

    /**
     * 发送或更新流式消息。设置 finish=true 结束流式输出。
     */
    public void replyStream(Frame callbackFrame, String streamId, String content, boolean finish) throws IOException {
        ReplyBody body = new ReplyBody();
        body.setMsgType(Constants.MSG_TYPE_STREAM);
        body.setStream(new StreamContent(streamId, finish, content));
        reply(callbackFrame, body);
    }

    /**
     * 回复模板卡片消息。
     */
    public void replyTemplateCard(Frame callbackFrame, TemplateCard card) throws IOException {
        ReplyBody body = new ReplyBody();
        body.setMsgType(Constants.MSG_TYPE_CARD);
        body.setTemplateCard(card);
        reply(callbackFrame, body);
    }

    /**
     * 发送欢迎语（需在收到 enter_chat 事件后 5 秒内调用）。
     */
    public void replyWelcome(Frame callbackFrame, ReplyBody body) throws IOException {
        sendReply(Constants.CMD_RESPOND_WELCOME_MSG, callbackFrame.getHeaders().getReqId(), body);
    }

    /**
     * 更新已有的模板卡片（需在收到卡片点击事件后 5 秒内调用）。
     */
    public void updateTemplateCard(Frame callbackFrame, TemplateCard card) throws IOException {
        UpdateCardBody updateBody = new UpdateCardBody("update_template_card", card);
        sendReply(Constants.CMD_RESPOND_UPDATE_MSG, callbackFrame.getHeaders().getReqId(), updateBody);
    }

    /**
     * 主动向会话推送消息。
     */
    public void sendMessage(SendMsgBody body) throws IOException, TimeoutException, InterruptedException {
        Frame resp = send(Constants.CMD_SEND_MSG, body);
        if (resp.getErrCode() != 0) {
            throw new IOException("发送消息失败: " + resp.getErrCode() + " " + resp.getErrMsg());
        }
    }

    /**
     * 主动推送 Markdown 消息。
     */
    public void sendMarkdown(String chatId, int chatType, String content) throws IOException, TimeoutException, InterruptedException {
        SendMsgBody body = new SendMsgBody();
        body.setChatId(chatId);
        body.setChatType(chatType);
        body.setMsgType(Constants.MSG_TYPE_MARKDOWN);
        body.setMarkdown(new MarkdownContent(content));
        sendMessage(body);
    }

    // =========================================================================
    // 流式消息会话工厂
    // =========================================================================

    /**
     * 创建一个新的流式消息会话。
     */
    public StreamSession newStream(Frame callbackFrame) {
        return new StreamSession(this, callbackFrame, generateReqId("stream"), log);
    }

    /**
     * 创建一个使用指定 streamID 的流式消息会话。
     */
    public StreamSession newStreamWithId(Frame callbackFrame, String streamId) {
        return new StreamSession(this, callbackFrame, streamId, log);
    }

    // =========================================================================
    // 连接关闭
    // =========================================================================

    /**
     * 安全关闭当前连接并通知读循环退出。
     * 可被多个线程安全调用（幂等）。
     */
    private void closeConn() {
        connected.set(false);
        synchronized (writeLock) {
            WebSocket ws = webSocket;
            if (ws != null) {
                try {
                    ws.close(1000, "客户端关闭");
                } catch (Exception ignored) {
                    // 忽略关闭过程中的异常
                }
                webSocket = null;
            }
        }
        cleanupPending();
    }

    /**
     * 清理所有待处理的请求，避免线程泄漏。
     */
    private void cleanupPending() {
        for (Map.Entry<String, CompletableFuture<Frame>> entry : pending.entrySet()) {
            Frame errFrame = new Frame();
            errFrame.setErrCode(-1);
            errFrame.setErrMsg("连接已关闭");
            entry.getValue().complete(errFrame);
        }
        pending.clear();
    }

    /**
     * 优雅地关闭 WebSocket 连接并停止客户端。
     */
    public void disconnect() {
        running = false;
        closeConn();
        stopHeartbeat();
        dispatchExecutor.shutdownNow();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    /**
     * 计算指数退避延迟（带上限）。
     */
    private long backoff(int attempt) {
        long delay = (long) (options.getReconnectBaseDelayMs() * Math.pow(2, attempt - 1));
        return Math.min(delay, options.getReconnectMaxDelayMs());
    }

    /**
     * 获取内部 ObjectMapper（供 MediaUtils 使用）。
     */
    ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
