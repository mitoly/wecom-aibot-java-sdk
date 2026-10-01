package com.wecom.aibot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * SDK 客户端配置项。
 * <p>
 * 凭证读取优先级：SecretFile/BotIDFile 文件 > 直接赋值。
 * 推荐将凭证文件权限设为 600（仅 owner 可读写）。
 */
public class Options {

    // 必填：机器人凭证
    private String botId;
    private String secret;

    // 可选：从文件读取凭证（优先级高于直接赋值）
    private String secretFile;
    private String botIdFile;

    // WebSocket 端点地址
    private String wsUrl = Constants.DEFAULT_WS_URL;

    // 心跳间隔（毫秒）
    private long heartbeatIntervalMs = Constants.DEFAULT_HEARTBEAT_INTERVAL_MS;

    // 重连退避参数
    private long reconnectBaseDelayMs = Constants.DEFAULT_RECONNECT_BASE_DELAY_MS;
    private long reconnectMaxDelayMs = Constants.DEFAULT_RECONNECT_MAX_DELAY_MS;
    private int maxReconnectAttempts = Constants.DEFAULT_MAX_RECONNECT_ATTEMPTS;

    // 单帧写入超时
    private long requestTimeoutMs = Constants.DEFAULT_REQUEST_TIMEOUT_MS;

    private long connectTimeoutMs = 10000L;
    // 回复 ACK 等待上限：超时即毒化 req_id（不可恢复），默认取宽（服务端慢波动不至截断流式回复）；联调后按实测 p99 复核
    private long replyAckTimeoutMs = 15000L;
    private int maxAuthFailureAttempts = 5;
    private int maxMissedHeartbeats = 3;
    private int maxReplyQueueSize = 32;
    private int maxPendingRequests = 1024;
    private int callbackThreads = 4;
    private int callbackQueueSize = 256;
    private long maxDownloadBytes = Constants.DEFAULT_MAX_DOWNLOAD_BYTES;
    // 下载独立超时：弱网大文件读取远慢于控制帧，不复用 requestTimeoutMs（对齐旧版 30s/60s）
    private long downloadConnectTimeoutMs = Constants.DEFAULT_DOWNLOAD_CONNECT_TIMEOUT_MS;
    private long downloadReadTimeoutMs = Constants.DEFAULT_DOWNLOAD_READ_TIMEOUT_MS;
    private int uploadChunkConcurrency = 2;
    private int maxChunkRetries = 2;

    /**
     * 校验并加载凭证。如果配置了文件路径则从文件读取。
     *
     * @throws IOException 读取凭证文件失败
     */
    public void validate() throws IOException {
        // 从文件加载凭证（优先级高于直接赋值）
        if (botIdFile != null && !botIdFile.isEmpty()) {
            byte[] data = Files.readAllBytes(Paths.get(botIdFile));
            botId = new String(data, StandardCharsets.UTF_8).trim();
        }
        if (secretFile != null && !secretFile.isEmpty()) {
            byte[] data = Files.readAllBytes(Paths.get(secretFile));
            secret = new String(data, StandardCharsets.UTF_8).trim();
        }

        if (botId == null || botId.trim().isEmpty()) {
            throw new IllegalArgumentException("BotID 不能为空（请设置 botId 或 botIdFile）");
        }
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret 不能为空（请设置 secret 或 secretFile）");
        }
        if (wsUrl == null || !(wsUrl.startsWith("wss://") || wsUrl.startsWith("ws://"))) {
            throw new IllegalArgumentException("wsUrl 必须使用 ws 或 wss");
        }
        if (heartbeatIntervalMs <= 0 || requestTimeoutMs <= 0 || connectTimeoutMs <= 0 || replyAckTimeoutMs <= 0
                || reconnectBaseDelayMs <= 0 || reconnectMaxDelayMs < reconnectBaseDelayMs
                || maxReconnectAttempts < -1 || maxAuthFailureAttempts < -1 || maxMissedHeartbeats <= 0
                || maxReplyQueueSize <= 0 || maxPendingRequests <= 0 || callbackThreads <= 0 || callbackQueueSize <= 0
                || maxDownloadBytes <= 0 || downloadConnectTimeoutMs <= 0 || downloadReadTimeoutMs <= 0
                || uploadChunkConcurrency <= 0 || uploadChunkConcurrency > 4 || maxChunkRetries < 0) {
            throw new IllegalArgumentException("时间、容量或重试参数无效");
        }
    }

    /**
     * 返回 secret 的脱敏形式，仅保留前4位和后4位。
     * 用于日志输出，避免泄露完整密钥。
     */
    public static String maskSecret(String s) {
        if (s == null || s.length() <= 8) {
            return "****";
        }
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    // =========================================================================
    // Getters & Setters
    // =========================================================================

    public String getBotId() {
        return botId;
    }

    public Options setBotId(String botId) {
        this.botId = botId;
        return this;
    }

    public String getSecret() {
        return secret;
    }

    public Options setSecret(String secret) {
        this.secret = secret;
        return this;
    }

    public String getSecretFile() {
        return secretFile;
    }

    public Options setSecretFile(String secretFile) {
        this.secretFile = secretFile;
        return this;
    }

    public String getBotIdFile() {
        return botIdFile;
    }

    public Options setBotIdFile(String botIdFile) {
        this.botIdFile = botIdFile;
        return this;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public Options setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
        return this;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public Options setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        return this;
    }

    public long getReconnectBaseDelayMs() {
        return reconnectBaseDelayMs;
    }

    public Options setReconnectBaseDelayMs(long reconnectBaseDelayMs) {
        this.reconnectBaseDelayMs = reconnectBaseDelayMs;
        return this;
    }

    public long getReconnectMaxDelayMs() {
        return reconnectMaxDelayMs;
    }

    public Options setReconnectMaxDelayMs(long reconnectMaxDelayMs) {
        this.reconnectMaxDelayMs = reconnectMaxDelayMs;
        return this;
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    /**
     * 最大重连次数：-1 表示无限重连，0 表示断线一次即进入 FAILED 终态（不重连）。
     * 注意与旧版（1.1.0 之前）语义不同：旧版 0/负数均表示无限重连。
     */
    public Options setMaxReconnectAttempts(int maxReconnectAttempts) {
        this.maxReconnectAttempts = maxReconnectAttempts;
        return this;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public Options setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
        return this;
    }
    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public Options setConnectTimeoutMs(long value) { this.connectTimeoutMs = value; return this; }
    public long getReplyAckTimeoutMs() { return replyAckTimeoutMs; }
    public Options setReplyAckTimeoutMs(long value) { this.replyAckTimeoutMs = value; return this; }
    public int getMaxAuthFailureAttempts() { return maxAuthFailureAttempts; }
    public Options setMaxAuthFailureAttempts(int value) { this.maxAuthFailureAttempts = value; return this; }
    public int getMaxMissedHeartbeats() { return maxMissedHeartbeats; }
    public Options setMaxMissedHeartbeats(int value) { this.maxMissedHeartbeats = value; return this; }
    public int getMaxReplyQueueSize() { return maxReplyQueueSize; }
    public Options setMaxReplyQueueSize(int value) { this.maxReplyQueueSize = value; return this; }
    public int getMaxPendingRequests() { return maxPendingRequests; }
    public Options setMaxPendingRequests(int value) { this.maxPendingRequests = value; return this; }
    public int getCallbackThreads() { return callbackThreads; }
    public Options setCallbackThreads(int value) { this.callbackThreads = value; return this; }
    public int getCallbackQueueSize() { return callbackQueueSize; }
    public Options setCallbackQueueSize(int value) { this.callbackQueueSize = value; return this; }
    public long getMaxDownloadBytes() { return maxDownloadBytes; }
    public Options setMaxDownloadBytes(long value) { this.maxDownloadBytes = value; return this; }
    public long getDownloadConnectTimeoutMs() { return downloadConnectTimeoutMs; }
    public Options setDownloadConnectTimeoutMs(long value) { this.downloadConnectTimeoutMs = value; return this; }
    public long getDownloadReadTimeoutMs() { return downloadReadTimeoutMs; }
    public Options setDownloadReadTimeoutMs(long value) { this.downloadReadTimeoutMs = value; return this; }
    public int getUploadChunkConcurrency() { return uploadChunkConcurrency; }
    public Options setUploadChunkConcurrency(int value) { this.uploadChunkConcurrency = value; return this; }
    public int getMaxChunkRetries() { return maxChunkRetries; }
    public Options setMaxChunkRetries(int value) { this.maxChunkRetries = value; return this; }

    Options snapshot() throws IOException {
        Options copy = new Options();
        copy.botId = this.botId;
        copy.secret = this.secret;
        copy.botIdFile = this.botIdFile;
        copy.secretFile = this.secretFile;
        copy.wsUrl = this.wsUrl;
        copy.heartbeatIntervalMs = this.heartbeatIntervalMs;
        copy.requestTimeoutMs = this.requestTimeoutMs;
        copy.reconnectBaseDelayMs = this.reconnectBaseDelayMs;
        copy.reconnectMaxDelayMs = this.reconnectMaxDelayMs;
        copy.maxReconnectAttempts = this.maxReconnectAttempts;
        copy.connectTimeoutMs = this.connectTimeoutMs;
        copy.replyAckTimeoutMs = this.replyAckTimeoutMs;
        copy.maxAuthFailureAttempts = this.maxAuthFailureAttempts;
        copy.maxMissedHeartbeats = this.maxMissedHeartbeats;
        copy.maxReplyQueueSize = this.maxReplyQueueSize;
        copy.maxPendingRequests = this.maxPendingRequests;
        copy.callbackThreads = this.callbackThreads;
        copy.callbackQueueSize = this.callbackQueueSize;
        copy.maxDownloadBytes = this.maxDownloadBytes;
        copy.downloadConnectTimeoutMs = this.downloadConnectTimeoutMs;
        copy.downloadReadTimeoutMs = this.downloadReadTimeoutMs;
        copy.uploadChunkConcurrency = this.uploadChunkConcurrency;
        copy.maxChunkRetries = this.maxChunkRetries;
        copy.validate();
        return copy;
    }
}
