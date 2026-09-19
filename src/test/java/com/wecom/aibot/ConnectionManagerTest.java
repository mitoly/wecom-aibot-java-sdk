package com.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.aibot.model.Frame;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.Test;

import java.util.concurrent.*;

import static org.junit.Assert.*;

public class ConnectionManagerTest {
    final ObjectMapper mapper=new ObjectMapper();
    static class Socket implements WebSocket {
        final WebSocketListener listener;
        final ObjectMapper mapper=new ObjectMapper();
        final BlockingQueue<JsonNode> frames=new LinkedBlockingQueue<>();
        volatile boolean accept=true;
        Socket(WebSocketListener listener){this.listener=listener;}
        public Request request(){return new Request.Builder().url("https://example.invalid").build();}
        public long queueSize(){return 0;}
        public boolean send(String text){
            try{
                JsonNode frame=mapper.readTree(text);
                if(Constants.CMD_SUBSCRIBE.equals(frame.path("cmd").asText()))ack(frame);else frames.add(frame);
                return accept;
            }catch(Exception e){throw new AssertionError(e);}
        }
        void ack(JsonNode frame){listener.onMessage(this,"{\"headers\":"+frame.path("headers")+",\"errcode\":0}");}
        public boolean send(ByteString value){return accept;}
        public boolean close(int code,String reason){return true;}
        public void cancel(){}
    }
    @Test public void sendFalseFailsAndOldListenerCannotClearNewPending() throws Exception {
        BlockingQueue<Socket> created=new LinkedBlockingQueue<>();
        ConnectionManager manager=new ConnectionManager(new Options().setMaxReconnectAttempts(2).setReconnectBaseDelayMs(10).setReconnectMaxDelayMs(20),mapper,new OkHttpClient(),(event,payload)->{},frame->{},
            (request,listener)->{Socket socket=new Socket(listener);created.add(socket);listener.onOpen(socket,null);return socket;});
        try {
            manager.start().toCompletableFuture().get(2,TimeUnit.SECONDS);Socket first=created.poll(2,TimeUnit.SECONDS);assertNotNull(first);
            first.accept=false;CompletionStage<Frame> failed=manager.request(Constants.CMD_SEND_MSG,"failed",mapper.valueToTree(ClientIntegrationTest.markdown("u","one")),"u",manager.generation(),Long.MAX_VALUE);
            try{failed.toCompletableFuture().get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException e){assertEquals(AiBotException.Code.SEND_FAILED,((AiBotException)SdkFutures.unwrap(e)).getCode());}
            first.listener.onFailure(first,new java.io.IOException("old failure"),null);Socket second=created.poll(2,TimeUnit.SECONDS);assertNotNull(second);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(manager.state()!=BotConnectionState.READY&&System.nanoTime()<until)Thread.sleep(5);
            CompletionStage<Frame> fresh=manager.request(Constants.CMD_SEND_MSG,"fresh",mapper.valueToTree(ClientIntegrationTest.markdown("u","two")),"u",manager.generation(),Long.MAX_VALUE);
            JsonNode sent=second.frames.poll(2,TimeUnit.SECONDS);assertNotNull(sent);first.listener.onClosed(first,1000,"late old close");Thread.sleep(30);assertEquals(BotConnectionState.READY,manager.state());assertFalse(fresh.toCompletableFuture().isDone());
            second.ack(sent);fresh.toCompletableFuture().get(2,TimeUnit.SECONDS);
        }finally{manager.close();}
    }

    @Test public void tenMinuteStreamDeadlineAppliesToDirectReplyCore() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(1);
        BlockingQueue<Socket> created=new LinkedBlockingQueue<>();
        ConnectionManager manager=new ConnectionManager(new Options(),mapper,new OkHttpClient(),(event,payload)->{},frame->{},
            (request,listener)->{Socket socket=new Socket(listener);created.add(socket);listener.onOpen(socket,null);return socket;},clock::get);
        try {
            manager.start().toCompletableFuture().get(2,TimeUnit.SECONDS);Socket socket=created.poll(2,TimeUnit.SECONDS);
            JsonNode update=mapper.readTree("{\"msgtype\":\"stream\",\"stream\":{\"id\":\"s\",\"content\":\"one\",\"finish\":false}}");
            CompletionStage<Frame> first=manager.request(Constants.CMD_RESPOND_MSG,"r",update,"u",manager.generation(),Long.MAX_VALUE);
            socket.ack(socket.frames.poll(2,TimeUnit.SECONDS));first.toCompletableFuture().get(2,TimeUnit.SECONDS);
            clock.addAndGet(TimeUnit.MINUTES.toNanos(10));
            CompletionStage<Frame> late=manager.request(Constants.CMD_RESPOND_MSG,"r",update,"u",manager.generation(),Long.MAX_VALUE);
            try{late.toCompletableFuture().get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException e){assertEquals(AiBotException.Code.DEADLINE_EXCEEDED,((AiBotException)SdkFutures.unwrap(e)).getCode());}
            assertNull(socket.frames.poll(30,TimeUnit.MILLISECONDS));
        }finally{manager.close();}
    }

    @Test public void acceptedRequestDuringTerminalCleanupAlwaysCompletes() throws Exception {
        java.util.concurrent.atomic.AtomicReference<ConnectionManager> holder=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<CompletionStage<Frame>> racing=new java.util.concurrent.atomic.AtomicReference<>();
        BlockingQueue<Socket> created=new LinkedBlockingQueue<>();
        JsonNode body=mapper.valueToTree(ClientIntegrationTest.markdown("u","race"));
        ConnectionManager manager=new ConnectionManager(new Options().setMaxReconnectAttempts(0),mapper,new OkHttpClient(),
            (event,payload)->{if(Constants.EVENT_DISCONNECTED.equals(event)) {ConnectionManager current=holder.get();racing.set(current.request(Constants.CMD_SEND_MSG,"racing",body,"u",current.generation(),Long.MAX_VALUE));}},frame->{},
            (request,listener)->{Socket socket=new Socket(listener);created.add(socket);listener.onOpen(socket,null);return socket;});
        holder.set(manager);
        try {
            manager.start().toCompletableFuture().get(2,TimeUnit.SECONDS);Socket socket=created.poll(2,TimeUnit.SECONDS);
            socket.listener.onFailure(socket,new java.io.IOException("disconnect"),null);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(racing.get()==null&&System.nanoTime()<until)Thread.sleep(5);assertNotNull(racing.get());
            try{racing.get().toCompletableFuture().get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){assertTrue(SdkFutures.unwrap(expected) instanceof AiBotException);}
        }finally{manager.close();}
    }

    @Test public void generationGuardSkipsProactiveButStillGuardsReplyPath() throws Exception {
        BlockingQueue<Socket> created=new LinkedBlockingQueue<>();
        ConnectionManager manager=new ConnectionManager(new Options(),mapper,new OkHttpClient(),(event,payload)->{},frame->{},
            (request,listener)->{Socket socket=new Socket(listener);created.add(socket);listener.onOpen(socket,null);return socket;});
        try {
            manager.start().toCompletableFuture().get(2,TimeUnit.SECONDS);Socket socket=created.poll(2,TimeUnit.SECONDS);
            JsonNode body=mapper.valueToTree(ClientIntegrationTest.markdown("u","hi"));
            // 主动推送语义（generation=-1）：接受发送
            CompletionStage<Frame> proactive=manager.request(Constants.CMD_SEND_MSG,"p",body,"u",-1,Long.MAX_VALUE);
            JsonNode sent=socket.frames.poll(2,TimeUnit.SECONDS);assertNotNull(sent);socket.ack(sent);
            proactive.toCompletableFuture().get(2,TimeUnit.SECONDS);
            // 回复路径语义（透传错误连接代）：仍被 STALE_CONTEXT 拦截
            CompletionStage<Frame> stale=manager.request(Constants.CMD_RESPOND_MSG,"s",mapper.valueToTree(ClientIntegrationTest.markdown("u","reply")),"u",manager.generation()+1000,Long.MAX_VALUE);
            try{stale.toCompletableFuture().get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException e){assertEquals(AiBotException.Code.STALE_CONTEXT,((AiBotException)SdkFutures.unwrap(e)).getCode());}
        }finally{manager.close();}
    }

    @Test public void negativeNanoTimeClockDoesNotExpireSentinelDeadline() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(-1_000_000_000L);
        BlockingQueue<Socket> created=new LinkedBlockingQueue<>();
        ConnectionManager manager=new ConnectionManager(new Options(),mapper,new OkHttpClient(),(event,payload)->{},frame->{},
            (request,listener)->{Socket socket=new Socket(listener);created.add(socket);listener.onOpen(socket,null);return socket;},clock::get);
        try {
            manager.start().toCompletableFuture().get(2,TimeUnit.SECONDS);Socket socket=created.poll(2,TimeUnit.SECONDS);
            CompletionStage<Frame> sent=manager.request(Constants.CMD_SEND_MSG,"n",mapper.valueToTree(ClientIntegrationTest.markdown("u","late")),"u",-1,Long.MAX_VALUE);
            JsonNode frame=socket.frames.poll(2,TimeUnit.SECONDS);assertNotNull(frame); // 旧代码 now-Long.MAX_VALUE 回绕为正，帧在入口即被误判过期
            socket.ack(frame);sent.toCompletableFuture().get(2,TimeUnit.SECONDS);
        }finally{manager.close();}
    }

    private JsonNode streamFrame(String id,boolean finish,boolean withFeedback) throws Exception {
        return mapper.readTree("{\"msgtype\":\"stream\",\"stream\":{\"id\":\""+id+"\",\"content\":\"x\",\"finish\":"+finish+(withFeedback?",\"feedback\":{\"id\":\"fb\"}":"")+"}}");
    }
    @Test public void finishedStreamReleasesCapacityImmediately() throws Exception {
        BlockingQueue<Socket> created=new LinkedBlockingQueue<>();
        ConnectionManager manager=new ConnectionManager(new Options().setMaxPendingRequests(1),mapper,new OkHttpClient(),(event,payload)->{},frame->{},
            (request,listener)->{Socket socket=new Socket(listener);created.add(socket);listener.onOpen(socket,null);return socket;});
        try {
            manager.start().toCompletableFuture().get(2,TimeUnit.SECONDS);Socket socket=created.poll(2,TimeUnit.SECONDS);
            CompletionStage<Frame> first=manager.request(Constants.CMD_RESPOND_MSG,"r",streamFrame("a",true,false),"u",manager.generation(),Long.MAX_VALUE);
            socket.ack(socket.frames.poll(2,TimeUnit.SECONDS));first.toCompletableFuture().get(2,TimeUnit.SECONDS);
            // 流 a finish 成功后槽位应即时回收：流 b（同 reqId 新 stream.id）可再入，未修复时为 QUEUE_FULL
            CompletionStage<Frame> second=manager.request(Constants.CMD_RESPOND_MSG,"r",streamFrame("b",true,false),"u",manager.generation(),Long.MAX_VALUE);
            socket.ack(socket.frames.poll(2,TimeUnit.SECONDS));second.toCompletableFuture().get(2,TimeUnit.SECONDS);
        }finally{manager.close();}
    }
    @Test public void feedbackOnFollowUpFrameIsSentInsteadOfRejected() throws Exception {
        BlockingQueue<Socket> created=new LinkedBlockingQueue<>();
        ConnectionManager manager=new ConnectionManager(new Options(),mapper,new OkHttpClient(),(event,payload)->{},frame->{},
            (request,listener)->{Socket socket=new Socket(listener);created.add(socket);listener.onOpen(socket,null);return socket;});
        try {
            manager.start().toCompletableFuture().get(2,TimeUnit.SECONDS);Socket socket=created.poll(2,TimeUnit.SECONDS);
            CompletionStage<Frame> head=manager.request(Constants.CMD_RESPOND_MSG,"f",streamFrame("s",false,false),"u",manager.generation(),Long.MAX_VALUE);
            socket.ack(socket.frames.poll(2,TimeUnit.SECONDS));head.toCompletableFuture().get(2,TimeUnit.SECONDS);
            // 官方无"feedback 仅首帧"限制：续帧携带 feedback 应照常发送（旧代码 INVALID_ARGUMENT 拒绝）
            CompletionStage<Frame> followUp=manager.request(Constants.CMD_RESPOND_MSG,"f",streamFrame("s",true,true),"u",manager.generation(),Long.MAX_VALUE);
            JsonNode sent=socket.frames.poll(2,TimeUnit.SECONDS);assertNotNull(sent);
            assertEquals("fb",sent.path("body").path("stream").path("feedback").path("id").asText());
            socket.ack(sent);followUp.toCompletableFuture().get(2,TimeUnit.SECONDS);
        }finally{manager.close();}
    }
}
