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

        if (botId == null || botId.isEmpty()) {
            throw new IllegalArgumentException("BotID 不能为空（请设置 botId 或 botIdFile）");
        }
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Secret 不能为空（请设置 secret 或 secretFile）");
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
}
