package com.wecom.aibot;

import com.wecom.aibot.model.Frame;

import java.io.IOException;

/**
 * 流式消息会话管理器。
 * <p>
 * 自动追踪时间，防止超过服务端 10 分钟限制。
 * 接近超时（最后 30 秒）时会输出警告日志。
 * <p>
 * 使用方式:
 * <pre>
 * StreamSession stream = client.newStream(callbackFrame);
 * stream.update("正在处理...");    // 发送中间状态
 * stream.update("继续处理...");    // 更新内容
 * stream.finish("最终结果");       // 结束流式消息
 * </pre>
 */
public class StreamSession {

    private final WeComAiBotClient client;
    private final Frame frame;
    private final String streamId;
    private final AiBotLogger log;

    private long startTimeMs = 0; // 0 表示未开始
    private boolean finished = false;
    private final Object lock = new Object();

    StreamSession(WeComAiBotClient client, Frame frame, String streamId, AiBotLogger log) {
        this.client = client;
        this.frame = frame;
        this.streamId = streamId;
        this.log = log;
    }

    /**
     * 返回当前会话的 stream_id。
     */
    public String getId() {
        return streamId;
    }

    /**
     * 返回从首次发送到现在经过的时间（毫秒）。未开始时返回 0。
     */
    public long getElapsedMs() {
        synchronized (lock) {
            if (startTimeMs == 0) {
                return 0;
            }
            return System.currentTimeMillis() - startTimeMs;
        }
    }

    /**
     * 返回距离 10 分钟超时还剩多少时间（毫秒）。未开始时返回最大时长。
     */
    public long getRemainingMs() {
        synchronized (lock) {
            if (startTimeMs == 0) {
                return Constants.STREAM_MAX_DURATION_MS;
            }
            long remaining = Constants.STREAM_MAX_DURATION_MS - (System.currentTimeMillis() - startTimeMs);
            return Math.max(remaining, 0);
        }
    }

    /**
     * 返回流式消息是否已超过 10 分钟限制。
     */
    public boolean isExpired() {
        return getRemainingMs() == 0;
    }

    /**
     * 返回流式消息是否已结束。
     */
    public boolean isFinished() {
        synchronized (lock) {
            return finished;
        }
    }

    /**
     * 发送流式消息的中间更新（finish=false）。
     * 如果已超过 10 分钟限制，抛出 {@link StreamExpiredException}。
     * 接近超时（最后 30 秒）时会输出警告日志。
     *
     * @param content 更新内容
     * @throws IOException             发送失败
     * @throws StreamExpiredException  流式消息已超时
     */
    public void update(String content) throws IOException {
        doSend(content, false);
    }

    /**
     * 发送流式消息的最终内容并结束（finish=true）。
     * 如果已超过 10 分钟限制，抛出 {@link StreamExpiredException}。
     *
     * @param content 最终内容
     * @throws IOException             发送失败
     * @throws StreamExpiredException  流式消息已超时
     */
    public void finish(String content) throws IOException {
        doSend(content, true);
    }

    private void doSend(String content, boolean finish) throws IOException {
        synchronized (lock) {
            if (finished) {
                throw new IllegalStateException("流式消息已结束，不能再次发送");
            }

            // 首次发送时记录开始时间
            if (startTimeMs == 0) {
                startTimeMs = System.currentTimeMillis();
            }

            // 检查是否超时
            long elapsed = System.currentTimeMillis() - startTimeMs;
            if (elapsed >= Constants.STREAM_MAX_DURATION_MS) {
                throw new StreamExpiredException("流式消息已超过 10 分钟限制，服务端将拒绝更新");
            }

            // 接近超时时警告
            long remaining = Constants.STREAM_MAX_DURATION_MS - elapsed;
            if (remaining <= Constants.STREAM_WARN_THRESHOLD_MS && !finish) {
                log.warn("流式消息即将超时（剩余 {}ms），请尽快调用 finish()", remaining);
            }

            if (finish) {
                finished = true;
            }
        }

        client.replyStream(frame, streamId, content, finish);
    }

    /**
     * 流式消息已超过 10 分钟限制时抛出的异常。
     */
    public static class StreamExpiredException extends IOException {
        public StreamExpiredException(String message) {
            super(message);
        }
    }
}
