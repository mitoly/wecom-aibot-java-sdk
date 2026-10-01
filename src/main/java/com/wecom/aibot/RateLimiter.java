package com.wecom.aibot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** 按调用目标检查两个滚动窗口；只记录发送尝试，不假设服务端的精确计数方式。 */
final class RateLimiter {
    private final Map<String, Deque<Long>> attempts = new HashMap<>();
    private final LongSupplier clock = System::nanoTime;
    private long lastSweepNanos; // 仅 lifecycle 线程访问
    RateLimiter() {}
    synchronized void acquire(String target) throws AiBotException {
        long now=clock.getAsLong();
        // 全表清扫至多 60 秒一次（条目本身 1 小时才过期）；本 target 队列修剪每次都做。
        if(now-lastSweepNanos>=60000000000L) {
            attempts.entrySet().removeIf(e -> e.getValue().isEmpty() || now-e.getValue().peekLast() >= 3600000000000L);
            lastSweepNanos=now;
        }
        if(!attempts.containsKey(target) && attempts.size()>=10000)throw new AiBotException(AiBotException.Code.QUEUE_FULL,"会话限流表容量已满");
        Deque<Long> times=attempts.computeIfAbsent(target, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now-times.peekFirst() >= 3600000000000L) times.removeFirst();
        long minuteWait=0, hourWait=0; int recent=0;
        for (long time:times) if (now-time < 60000000000L) { if (recent++ == 0) minuteWait=60000000000L-(now-time); }
        if (times.size() >= 1000) hourWait=3600000000000L-(now-times.peekFirst());
        if (recent >= 30 || hourWait > 0) throw new RateLimitException(Math.max(1,(Math.max(recent>=30?minuteWait:0,hourWait)+999999)/1000000));
        times.addLast(now);
    }
}
