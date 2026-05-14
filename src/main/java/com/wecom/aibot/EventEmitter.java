package com.wecom.aibot;

import com.wecom.aibot.model.Frame;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的事件总线。
 * <p>
 * 使用唯一递增 ID 绑定 handler，避免注销错乱。
 * 返回的 {@link Disposable} 接口保证幂等注销。
 */
public class EventEmitter {

    /**
     * 事件处理函数签名。
     */
    @FunctionalInterface
    public interface Handler {
        void handle(Frame frame, Object payload);
    }

    /**
     * 可取消注册的接口。
     */
    @FunctionalInterface
    public interface Disposable {
        void dispose();
    }

    private static class HandlerEntry {
        final long id;
        final Handler handler;

        HandlerEntry(long id, Handler handler) {
            this.id = id;
            this.handler = handler;
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<HandlerEntry>> handlers
            = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(0);

    /**
     * 注册指定事件的处理函数，返回一个可取消注册的 Disposable。
     * 多次调用 dispose() 是安全的（幂等）。
     */
    public Disposable on(String event, Handler handler) {
        long id = nextId.incrementAndGet();
        HandlerEntry entry = new HandlerEntry(id, handler);
        handlers.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(entry);

        AtomicBoolean removed = new AtomicBoolean(false);
        return () -> {
            if (removed.compareAndSet(false, true)) {
                CopyOnWriteArrayList<HandlerEntry> list = handlers.get(event);
                if (list != null) {
                    list.removeIf(e -> e.id == id);
                }
            }
        };
    }

    /**
     * 同步触发指定事件的所有处理函数。
     * 单个 handler 抛出异常不影响其他 handler 的执行。
     */
    public void emit(String event, Frame frame, Object payload) {
        CopyOnWriteArrayList<HandlerEntry> list = handlers.get(event);
        if (list == null) {
            return;
        }
        for (HandlerEntry entry : list) {
            try {
                entry.handler.handle(frame, payload);
            } catch (Exception e) {
                // 防止单个 handler 异常影响其他 handler
                // 调用方应在外层处理
            }
        }
    }
}
