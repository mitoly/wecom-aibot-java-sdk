package com.wecom.aibot;

import com.wecom.aibot.model.Frame;
import java.io.IOException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * 全量流快照会话（门面）：状态与 10 分钟窗口计时的唯一来源是 {@link StreamRegistry}
 * （client 级、跨连接存活），本类不再自持第二份状态机；发送经 client.replyStreamAsync，
 * 由连接层 admit 权威裁决。前置检查仅是友好快速失败（StreamRegistry 不变量 4）。
 */
public class StreamSession {
    public enum State { OPEN, FINISHING, FINISHED, UNKNOWN }
    private final WeComAiBotClient client;
    private final Frame frame;
    private final String streamId;
    private final Object lock=new Object();
    StreamSession(WeComAiBotClient client,Frame frame,String streamId) {this.client=client;this.frame=frame;this.streamId=streamId;}
    public String getId(){return streamId;}
    public State getState(){
        StreamRegistry registry=client.streamRegistry();
        String key=key();
        if(key!=null && registry.isPoisoned(frame.getHeaders().getReqId()))return State.UNKNOWN;
        StreamRegistry.Entry entry=registry.snapshot(key);
        if(entry==null)return State.OPEN;
        if(entry.finished)return State.FINISHED;
        if(entry.finishing)return State.FINISHING;
        return State.OPEN;
    }
    public long getElapsedMs(){
        long startedAt=client.streamRegistry().startedAt(key());
        return startedAt==0?0:TimeUnit.NANOSECONDS.toMillis(client.clockSource().getAsLong()-startedAt);
    }
    public long getRemainingMs(){return Math.max(0,Constants.STREAM_MAX_DURATION_MS-getElapsedMs());}
    public boolean isExpired(){return getRemainingMs()==0;}
    public boolean isFinished(){return getState()==State.FINISHED;}
    public CompletionStage<Frame> updateAsync(String content){return enqueue(content,false);}
    public CompletionStage<Frame> finishAsync(String content){return enqueue(content,true);}
    public void update(String content) throws IOException {SdkFutures.awaitIo(updateAsync(content));}
    public void finish(String content) throws IOException {SdkFutures.awaitIo(finishAsync(content));}
    private String key(){return frame.getHeaders()!=null&&frame.getHeaders().getReqId()!=null?StreamRegistry.key(frame.getHeaders().getReqId(),streamId):null;}
    private CompletionStage<Frame> enqueue(String content,boolean finish) {
        synchronized(lock) {
            if(getState()!=State.OPEN)return SdkFutures.failed(new IllegalStateException("流不可更新: "+getState()));
            if(isExpired())return SdkFutures.failed(new StreamExpiredException("流已超过十分钟"));
            return client.replyStreamAsync(frame,streamId,content,finish);
        }
    }
    public static class StreamExpiredException extends IOException {public StreamExpiredException(String message){super(message);}}
}
