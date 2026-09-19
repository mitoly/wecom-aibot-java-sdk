package com.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流会话状态的唯一记账（client 级，跨连接存活）。
 *
 * <p>并发模型——单写者 + 无锁读：
 * <ol>
 * <li>写者唯一 = ConnectionManager 的 lifecycle 线程；其他线程调用写方法即是 bug；</li>
 * <li>条目 {@link Entry} 全字段 final、不可变，状态转移只做"替换引用"，getter 不暴露可变对象；</li>
 * <li>持任何锁（StreamSession.lock / ConnectionManager 监视器 / readyLock）时不得执行用户代码，通知一律经 completions 异步；</li>
 * <li>StreamSession 的前置检查只是友好快速失败，admit 是唯一权威裁决，检查与 admit 之间的竞态由 future 失败兜底；</li>
 * <li>客户端关闭后读取为冻结快照（无害），发送路径由 ConnectionManager 的终态检查拒绝。</li>
 * </ol>
 * 结构性修改（put/remove）仅 lifecycle 单线程执行，{@link ConcurrentHashMap} 只提供内存可见性，
 * 不引入新锁节点——现有锁序（StreamSession.lock → CM monitor → readyLock）保持不变、无环。
 *
 * <p>字段语义：startedAt/streamExpiresAt 是 10 分钟流刷新窗口（跨连接连续计时，对齐官方
 * "从发送开始计时"）；replyDeadline 是回复窗口（24h/5s），仅用于过期回收；poisoned 按
 * req_id 维度跨连接记录——ACK 结果未知的 req_id 禁止续发，重连不豁免（防重复发送）。
 */
final class StreamRegistry {
    /** 不可变流状态条目；startedAt=0 表示尚未写过 socket。 */
    static final class Entry {
        final long startedAt;
        final long streamExpiresAt;
        final long replyDeadline;
        final boolean finishing;
        final boolean finished;
        Entry(long startedAt, long streamExpiresAt, long replyDeadline, boolean finishing, boolean finished) {
            this.startedAt=startedAt; this.streamExpiresAt=streamExpiresAt; this.replyDeadline=replyDeadline;
            this.finishing=finishing; this.finished=finished;
        }
    }

    private final ConcurrentHashMap<String,Entry> streams=new ConcurrentHashMap<>();
    private final Set<String> poisoned=ConcurrentHashMap.newKeySet();
    private final int maxActive;
    private int active; // 仅 lifecycle 线程访问：未终结（!finished）流条目计数，即流会话容量

    StreamRegistry(int maxActive) { this.maxActive=maxActive; }

    /**
     * enqueue 阶段裁决流消息：识别 stream 消息体、计算 key、容量与 finishing/finished 检查。
     * @return 流 key（reqId:streamId）；非流消息返回 null；拒绝时抛 AiBotException
     */
    String admit(String reqId, JsonNode body, long deadline) throws AiBotException {
        if(body==null || !"stream".equals(body.path("msgtype").asText()))return null;
        String key=reqId+":"+body.path("stream").path("id").asText();
        Entry current=streams.get(key);
        if(current==null) {
            if(active>=maxActive)throw new AiBotException(AiBotException.Code.QUEUE_FULL,"流会话容量已满");
            current=new Entry(0,0,deadline,false,false);
            streams.put(key,current);active++;
        }
        if(current.finishing||current.finished)throw new AiBotException(AiBotException.Code.INVALID_ARGUMENT,"流已结束或正在结束");
        if(body.path("stream").path("finish").asBoolean())
            streams.put(key,new Entry(current.startedAt,current.streamExpiresAt,current.replyDeadline,true,current.finished));
        return key;
    }

    /** pump 写 socket 成功后记录流窗口起点（幂等：仅首帧生效）。 */
    void onSent(String key,long now) {
        if(key==null)return;
        Entry current=streams.get(key);
        if(current==null||current.startedAt!=0)return;
        streams.put(key,new Entry(now,now+ java.util.concurrent.TimeUnit.MINUTES.toNanos(10),current.replyDeadline,current.finishing,current.finished));
    }

    /** pump 发送失败回退 finishing（终帧未发出，流仍可续）。 */
    void onSendFailed(String key) {
        if(key==null)return;
        Entry current=streams.get(key);
        if(current==null||!current.finishing)return;
        streams.put(key,new Entry(current.startedAt,current.streamExpiresAt,current.replyDeadline,false,current.finished));
    }

    /**
     * finish 帧 ACK：被接受时流终结并即时释放容量；被拒（errcode!=0）时回退 finishing
     * 保持可重试——服务端明确拒绝意味着消息未发出，重发合法（仅 UNKNOWN 走 poison 禁续发）。
     */
    void onFinishAck(String key,boolean accepted) {
        if(key==null)return;
        Entry current=streams.get(key);
        if(current==null)return;
        if(accepted) {
            streams.put(key,new Entry(current.startedAt,current.streamExpiresAt,current.replyDeadline,false,true));
            if(!current.finished)active--;
        } else if(current.finishing||current.finished) {
            streams.put(key,new Entry(current.startedAt,current.streamExpiresAt,current.replyDeadline,false,false));
        }
    }

    /** ACK 超时等结果未知：毒化整个 req_id，跨连接永久（重连不豁免）。 */
    void markPoisoned(String reqId) { poisoned.add(reqId); }
    boolean isPoisoned(String reqId) { return poisoned.contains(reqId); }
    int poisonedCount() { return poisoned.size(); }

    /** 回复窗口过期或已终结条目的回收（仅未终结条目占容量）。 */
    void cleanup(long now) {
        for(java.util.Map.Entry<String,Entry> entry:streams.entrySet()) {
            Entry value=entry.getValue();
            if(now>=value.replyDeadline) {
                if(streams.remove(entry.getKey(),value)&&!value.finished)active--;
            }
        }
    }

    /** 无锁读：流窗口起点；无记录或未发送返回 0。 */
    long startedAt(String key) { Entry e=key==null?null:streams.get(key); return e==null?0:e.startedAt; }
    /** 无锁读：条目快照（不可变）。 */
    Entry snapshot(String key) { return key==null?null:streams.get(key); }
}
