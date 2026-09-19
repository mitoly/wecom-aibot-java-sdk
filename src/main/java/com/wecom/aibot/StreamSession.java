package com.wecom.aibot;

import com.wecom.aibot.model.Frame;
import java.io.IOException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** 全量流快照会话；不自动合并内容，终帧 ACK 后才标记完成。 */
public class StreamSession {
    public enum State { OPEN, FINISHING, FINISHED, UNKNOWN }
    private final WeComAiBotClient client;
    private final Frame frame;
    private final String streamId;
    private long startNanos;
    private State state=State.OPEN;
    private final Object lock=new Object();
    StreamSession(WeComAiBotClient client,Frame frame,String streamId,AiBotLogger log) {this.client=client;this.frame=frame;this.streamId=streamId;}
    public String getId(){return streamId;}
    public State getState(){synchronized(lock){return state;}}
    public long getElapsedMs(){synchronized(lock){return startNanos==0?0:TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startNanos);}}
    public long getRemainingMs(){return Math.max(0,Constants.STREAM_MAX_DURATION_MS-getElapsedMs());}
    public boolean isExpired(){return getRemainingMs()==0;}
    public boolean isFinished(){return getState()==State.FINISHED;}
    public CompletionStage<Frame> updateAsync(String content){return enqueue(content,false);}
    public CompletionStage<Frame> finishAsync(String content){return enqueue(content,true);}
    public void update(String content) throws IOException {SdkFutures.awaitIo(updateAsync(content));}
    public void finish(String content) throws IOException {SdkFutures.awaitIo(finishAsync(content));}
    private CompletionStage<Frame> enqueue(String content,boolean finish) {
        synchronized(lock) {
            if(state!=State.OPEN)return SdkFutures.failed(new IllegalStateException("流不可更新: "+state));
            if(isExpired())return SdkFutures.failed(new StreamExpiredException("流已超过十分钟"));
            if(startNanos==0)startNanos=System.nanoTime();
            if(finish)state=State.FINISHING;
            CompletionStage<Frame> result=client.replyStreamAsync(frame,streamId,content,finish);
            return result.whenComplete((ack,error) -> {
                synchronized(lock) {
                    Throwable cause=error==null?null:SdkFutures.unwrap(error);
                    if(cause instanceof AiBotException && ((AiBotException)cause).getCode()==AiBotException.Code.UNKNOWN)state=State.UNKNOWN;
                    else if(finish && state!=State.UNKNOWN)state=error==null?State.FINISHED:State.OPEN;
                }
            });
        }
    }
    public static class StreamExpiredException extends IOException {public StreamExpiredException(String message){super(message);}}
}
