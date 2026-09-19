package com.wecom.aibot;

/** 本地额度耗尽；SDK 不会自动等待或补发，retryAfterMs 使用毫秒。 */
public class RateLimitException extends AiBotException {
    private final long retryAfterMs;
    public RateLimitException(long retryAfterMs) {
        super(Code.RATE_LIMITED, "发送额度耗尽，请等待 " + retryAfterMs + "ms"); this.retryAfterMs = retryAfterMs;
    }
    public long getRetryAfterMs() { return retryAfterMs; }
}
