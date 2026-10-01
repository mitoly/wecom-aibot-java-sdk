package com.wecom.aibot;

import java.io.IOException;

/** 稳定 SDK 错误；UNKNOWN 表示请求可能已被服务端执行，不能盲目重试。 */
public class AiBotException extends IOException {
    public enum Code { NOT_READY, CLOSED, SUPERSEDED, RETRY_EXHAUSTED, PROTOCOL_ERROR, SERVER_REJECTED,
        UNKNOWN, RATE_LIMITED, QUEUE_FULL, SEND_FAILED, STALE_CONTEXT, DEADLINE_EXCEEDED, INVALID_ARGUMENT;
        /** 帧确定未离开本机（排队未发出/写入失败），重发无重复风险。 */
        public boolean definitelyUnsent() { return this==NOT_READY || this==SEND_FAILED; }
        /** 幂等帧（如分片）可安全重试的传输性失败；非幂等帧（如 finish）只认 {@link #definitelyUnsent()}。 */
        public boolean idempotentRetryable() { return definitelyUnsent() || this==UNKNOWN || this==SERVER_REJECTED; }
    }
    private final Code code;
    private final int errCode;
    public AiBotException(Code code, String message) { this(code, message, 0, null); }
    public AiBotException(Code code, String message, Throwable cause) { this(code, message, 0, cause); }
    public AiBotException(Code code, String message, int errCode, Throwable cause) {
        super(message, cause); this.code = code; this.errCode = errCode;
    }
    public Code getCode() { return code; }
    public int getErrCode() { return errCode; }
}
