package com.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wecom.aibot.model.*;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.Assert.*;

public class ClientIntegrationTest {
    final ObjectMapper mapper=new ObjectMapper();
    MockWebServer server;
    WeComAiBotClient client;
    final BlockingQueue<Frame> inbound=new LinkedBlockingQueue<>();
    final BlockingQueue<JsonNode> outbound=new LinkedBlockingQueue<>();
    final AtomicReference<WebSocket> socket=new AtomicReference<>();
    BiConsumer<WebSocket,JsonNode> policy;
    @Before public void before() throws Exception {server=new MockWebServer();server.start();}
    @After public void after() throws Exception {if(client!=null)client.close();server.shutdown();}
    Options options() {return new Options().setBotId("bot-1").setSecret("offline-secret").setWsUrl(server.url("/").toString().replace("http://","ws://"))
            .setRequestTimeoutMs(1000).setReplyAckTimeoutMs(500).setReconnectBaseDelayMs(20).setReconnectMaxDelayMs(40).setHeartbeatIntervalMs(10000);}
    void upgrade() {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws,Response response){socket.set(ws);}
            @Override public void onMessage(WebSocket ws,String raw){
                try {
                    JsonNode frame=mapper.readTree(raw);String cmd=frame.path("cmd").asText();
                    if(!Constants.CMD_SUBSCRIBE.equals(cmd)&&!Constants.CMD_PING.equals(cmd))outbound.add(frame);
                    if(policy!=null)policy.accept(ws,frame);else ack(ws,frame,0);
                }catch(Exception e){throw new AssertionError(e);}
            }
        }));
    }
    void start(Options options) throws Exception {
        client=new WeComAiBotClient(options,new AiBotLogger.NopLogger());client.on(Constants.EVENT_MESSAGE_TEXT,(frame,body)->inbound.add(frame));
        client.startAsync().toCompletableFuture().get(3,TimeUnit.SECONDS);
    }
    void ack(WebSocket ws,JsonNode frame,int code) {ws.send("{\"headers\":{\"req_id\":\""+frame.path("headers").path("req_id").asText()+"\"},\"errcode\":"+code+",\"errmsg\":\"test\"}");}
    Frame message(String reqId) throws Exception {
        ObjectNode json=(ObjectNode)mapper.readTree(ProtocolModelTest.fixture("text-callback.json"));((ObjectNode)json.get("headers")).put("req_id",reqId);socket.get().send(json.toString());
        Frame callback=inbound.poll(2,TimeUnit.SECONDS);assertNotNull(callback);return callback;
    }
    JsonNode sent() throws Exception {JsonNode result=outbound.poll(2,TimeUnit.SECONDS);assertNotNull(result);return result;}
    Throwable failure(CompletionStage<?> stage) throws Exception {
        try{stage.toCompletableFuture().get(3,TimeUnit.SECONDS);fail("expected failure");return null;}catch(ExecutionException e){return SdkFutures.unwrap(e);}
    }
    void waitState(BotConnectionState target) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(client.getState()!=target && System.nanoTime()<until)Thread.sleep(5);
        assertEquals(target,client.getState());
    }
    @Test public void repliesWaitForAckAndSerializeSameReqId() throws Exception {
        policy=(ws,frame)->{if(Constants.CMD_SUBSCRIBE.equals(frame.path("cmd").asText()))ack(ws,frame,0);};upgrade();start(options());Frame callback=message("same-req");
        CompletionStage<Frame> first=client.replyStreamAsync(callback,"stream-1","one",false);
        CompletionStage<Frame> second=client.replyStreamAsync(callback,"stream-1","two",false);
        CompletionStage<Frame> last=client.replyStreamAsync(callback,"stream-1","done",true);
        JsonNode one=sent();assertEquals("same-req",one.path("headers").path("req_id").asText());assertFalse(first.toCompletableFuture().isDone());assertNull(outbound.poll(80,TimeUnit.MILLISECONDS));
        ack(socket.get(),one,0);first.toCompletableFuture().get(2,TimeUnit.SECONDS);JsonNode two=sent();assertEquals("two",two.path("body").path("stream").path("content").asText());ack(socket.get(),two,0);
        second.toCompletableFuture().get(2,TimeUnit.SECONDS);JsonNode done=sent();assertTrue(done.path("body").path("stream").path("finish").asBoolean());ack(socket.get(),done,0);last.toCompletableFuture().get(2,TimeUnit.SECONDS);
    }
    @Test public void timeoutPoisonsLaneAndLateAckDoesNotCompleteNextMessage() throws Exception {
        policy=(ws,frame)->{if(Constants.CMD_SUBSCRIBE.equals(frame.path("cmd").asText()))ack(ws,frame,0);};upgrade();start(options().setReplyAckTimeoutMs(100));Frame callback=message("timeout-req");
        CompletionStage<Frame> first=client.replyMarkdownAsync(callback,"first");JsonNode sent=sent();CompletionStage<Frame> queued=client.replyMarkdownAsync(callback,"queued");
        assertEquals(AiBotException.Code.UNKNOWN,((AiBotException)failure(first)).getCode());assertEquals(AiBotException.Code.UNKNOWN,((AiBotException)failure(queued)).getCode());
        ack(socket.get(),sent,0);assertEquals(AiBotException.Code.UNKNOWN,((AiBotException)failure(client.replyMarkdownAsync(callback,"later"))).getCode());assertNull(outbound.poll(80,TimeUnit.MILLISECONDS));
        Frame other=message("other-req");CompletionStage<Frame> independent=client.replyMarkdownAsync(other,"independent");ack(socket.get(),sent(),0);independent.toCompletableFuture().get(2,TimeUnit.SECONDS);
    }
    @Test public void missingErrcodeCannotBeSuccessfulAck() throws Exception {
        policy=(ws,frame)->{if(Constants.CMD_SUBSCRIBE.equals(frame.path("cmd").asText()))ack(ws,frame,0);else ws.send("{\"headers\":"+frame.path("headers")+"}");};upgrade();start(options());
        assertEquals(AiBotException.Code.UNKNOWN,((AiBotException)failure(client.replyMarkdownAsync(message("r"),"text"))).getCode());
    }
    @Test public void serverRejectionAndFinishRetryHaveAccurateState() throws Exception {
        AtomicInteger attempts=new AtomicInteger();policy=(ws,frame)->ack(ws,frame,Constants.CMD_RESPOND_MSG.equals(frame.path("cmd").asText())&&attempts.getAndIncrement()==0?400:0);
        upgrade();start(options());StreamSession stream=client.newStream(message("r"));
        Throwable error=failure(stream.finishAsync("done"));assertEquals(AiBotException.Code.SERVER_REJECTED,((AiBotException)error).getCode());assertFalse(stream.isFinished());assertEquals(StreamSession.State.OPEN,stream.getState());
        stream.finishAsync("done").toCompletableFuture().get(2,TimeUnit.SECONDS);assertTrue(stream.isFinished());assertTrue(failure(stream.updateAsync("too late")) instanceof IllegalStateException);
    }
    @Test public void callbackCanUseSynchronousReplyWithoutBlockingAckReader() throws Exception {
        upgrade();start(options());CompletableFuture<Void> replied=new CompletableFuture<>();
        client.on(Constants.EVENT_MESSAGE_TEXT,(frame,body)->{try{client.replyText(frame,"text");replied.complete(null);}catch(Exception e){replied.completeExceptionally(e);}});
        message("r");replied.get(2,TimeUnit.SECONDS);assertEquals("stream",sent().path("body").path("msgtype").asText());
    }
    @Test public void replacedClientStopsReconnecting() throws Exception {
        upgrade();start(options());socket.get().send("{\"cmd\":\"aibot_event_callback\",\"headers\":{\"req_id\":\"kick\"},\"body\":{\"msgid\":\"kick\",\"msgtype\":\"event\",\"event\":{\"eventtype\":\"disconnected_event\"}}}");
        waitState(BotConnectionState.SUPERSEDED);Thread.sleep(80);assertEquals(1,server.getRequestCount());
        assertEquals(AiBotException.Code.SUPERSEDED,((AiBotException)failure(client.sendMessageAsync(markdown("user","one")))).getCode());
    }
    @Test public void physicalConnectionIsNotBusinessReadyAndCloseEndsAuthWait() throws Exception {
        policy=(ws,frame)->{};upgrade();client=new WeComAiBotClient(options(),new AiBotLogger.NopLogger());CompletionStage<Void> ready=client.startAsync();waitState(BotConnectionState.AUTHENTICATING);
        assertEquals(AiBotException.Code.NOT_READY,((AiBotException)failure(client.sendMessageAsync(markdown("user","one")))).getCode());client.close();assertEquals(AiBotException.Code.CLOSED,((AiBotException)failure(ready)).getCode());
        assertEquals(AiBotException.Code.CLOSED,((AiBotException)failure(client.startAsync())).getCode());client.close();
    }
    @Test public void authFailureHasSeparateFiniteBudget() throws Exception {
        policy=(ws,frame)->ack(ws,frame,401);upgrade();upgrade();upgrade();client=new WeComAiBotClient(options().setMaxAuthFailureAttempts(2),new AiBotLogger.NopLogger());
        assertEquals(AiBotException.Code.RETRY_EXHAUSTED,((AiBotException)failure(client.startAsync())).getCode());waitState(BotConnectionState.FAILED);assertEquals(3,server.getRequestCount());
    }
    @Test public void heartbeatBlackholeEventuallyFailsAndReleasesRequests() throws Exception {
        policy=(ws,frame)->{if(Constants.CMD_SUBSCRIBE.equals(frame.path("cmd").asText()))ack(ws,frame,0);};upgrade();start(options().setHeartbeatIntervalMs(30).setMaxReconnectAttempts(0));
        waitState(BotConnectionState.FAILED);assertEquals(1,server.getRequestCount());
    }
    @Test public void boundedLaneRejectsExtraRequestsAndCloseCompletesWaiter() throws Exception {
        policy=(ws,frame)->{if(Constants.CMD_SUBSCRIBE.equals(frame.path("cmd").asText()))ack(ws,frame,0);};upgrade();start(options().setMaxReplyQueueSize(1));Frame callback=message("r");
        CompletionStage<Frame> first=client.replyMarkdownAsync(callback,"first");sent();assertEquals(AiBotException.Code.QUEUE_FULL,((AiBotException)failure(client.replyMarkdownAsync(callback,"extra"))).getCode());client.close();
        assertEquals(AiBotException.Code.UNKNOWN,((AiBotException)failure(first)).getCode());
    }
    @Test public void eventDeadlineTypeAndUserIdsAreValidated() throws Exception {
        upgrade();start(options());BlockingQueue<Frame> events=new LinkedBlockingQueue<>();client.on(Constants.EVENT_EVENT,(frame,body)->events.add(frame));socket.get().send(ProtocolModelTest.fixture("card-event.json"));
        Frame frame=events.poll(2,TimeUnit.SECONDS);assertNotNull(frame);TemplateCard card=new TemplateCard();card.setCardType("button_interaction");card.setTaskId("task_1");
        client.updateTemplateCardAsync(frame,card,Collections.singletonList("user-1")).toCompletableFuture().get(2,TimeUnit.SECONDS);assertEquals("user-1",sent().path("body").path("userids").get(0).asText());
        assertEquals(AiBotException.Code.INVALID_ARGUMENT,((AiBotException)failure(client.replyTemplateCardAsync(frame,card))).getCode());
        card.setTaskId("wrong_task");assertEquals(AiBotException.Code.INVALID_ARGUMENT,((AiBotException)failure(client.updateTemplateCardAsync(frame,card))).getCode());card.setTaskId("task_1");
        frame.markReceived(System.nanoTime()-TimeUnit.SECONDS.toNanos(6),frame.getGeneration(),frame.getClientId());assertEquals(AiBotException.Code.DEADLINE_EXCEEDED,((AiBotException)failure(client.updateTemplateCardAsync(frame,card))).getCode());
    }
    @Test public void unknownAckOfFinalStreamLeavesUnknownRatherThanFinished() throws Exception {
        policy=(ws,frame)->{if(Constants.CMD_SUBSCRIBE.equals(frame.path("cmd").asText()))ack(ws,frame,0);};upgrade();start(options().setReplyAckTimeoutMs(80));StreamSession stream=client.newStream(message("r"));
        failure(stream.finishAsync("done"));assertFalse(stream.isFinished());assertEquals(StreamSession.State.UNKNOWN,stream.getState());
    }
    @Test public void uploadChunksStartAtZeroAndCreatedAtStringIsAccepted() throws Exception {
        uploadPolicy(false);upgrade();start(options());byte[] data=new byte[Constants.UPLOAD_CHUNK_SIZE+5];
        UploadedMedia result=client.uploadMediaAsync("file","file.bin",data).toCompletableFuture().get(4,TimeUnit.SECONDS);assertEquals("media-id",result.getMediaId());assertEquals(Long.valueOf(1700000000),result.getCreatedAt());
        Set<Integer> indices=new HashSet<>();JsonNode frame;
        while((frame=outbound.poll(50,TimeUnit.MILLISECONDS))!=null)if(Constants.CMD_UPLOAD_MEDIA_CHUNK.equals(frame.path("cmd").asText()))indices.add(frame.path("body").path("chunk_index").asInt());
        assertEquals(new HashSet<>(Arrays.asList(0,1)),indices);
    }
    void uploadPolicy(boolean skipFirstChunk) {
        AtomicInteger chunks=new AtomicInteger();policy=(ws,frame)-> {
            String cmd=frame.path("cmd").asText();String req=frame.path("headers").path("req_id").asText();
            if(Constants.CMD_UPLOAD_MEDIA_INIT.equals(cmd))ws.send("{\"headers\":{\"req_id\":\""+req+"\"},\"errcode\":0,\"body\":{\"upload_id\":\"upload-id\"}}");
            else if(Constants.CMD_UPLOAD_MEDIA_FINISH.equals(cmd))ws.send("{\"headers\":{\"req_id\":\""+req+"\"},\"errcode\":0,\"body\":{\"type\":\"file\",\"media_id\":\"media-id\",\"created_at\":\"1700000000\"}}");
            else if(Constants.CMD_UPLOAD_MEDIA_CHUNK.equals(cmd) && skipFirstChunk && chunks.getAndIncrement()==0)return;
            else ack(ws,frame,0);
        };
    }
    @Test public void chunkAckTimeoutRetriesIdenticalIdIndexAndContent() throws Exception {
        uploadPolicy(true);upgrade();start(options().setRequestTimeoutMs(80));
        client.uploadMediaAsync("file","file.bin",new byte[5]).toCompletableFuture().get(4,TimeUnit.SECONDS);
        sent();JsonNode first=sent(),retry=sent();assertEquals(first.path("body"),retry.path("body"));assertNotEquals(first.path("headers"),retry.path("headers"));
    }
    @Test public void uploadInitUnknownIsNotBlindlyRetried() throws Exception {
        policy=(ws,frame)->{if(Constants.CMD_SUBSCRIBE.equals(frame.path("cmd").asText()))ack(ws,frame,0);};upgrade();start(options().setRequestTimeoutMs(80));
        assertEquals(AiBotException.Code.UNKNOWN,((AiBotException)failure(client.uploadMediaAsync("file","f.bin",new byte[5]))).getCode());
        assertEquals(Constants.CMD_UPLOAD_MEDIA_INIT,sent().path("cmd").asText());assertNull(outbound.poll(80,TimeUnit.MILLISECONDS));
    }

    @Test public void generationChangeRejectsOldCallbackButNewReplyWorks() throws Exception {
        upgrade();upgrade();start(options());Frame old=message("old");WebSocket original=socket.get();long generation=old.getGeneration();
        original.close(1001,"simulate disconnect");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while((server.getRequestCount()<2 || socket.get()==original || client.getState()!=BotConnectionState.READY)&&System.nanoTime()<until)Thread.sleep(5);
        assertEquals(2,server.getRequestCount());assertEquals(BotConnectionState.READY,client.getState());
        Frame fresh=message("fresh");assertTrue(fresh.getGeneration()>generation);
        assertEquals(AiBotException.Code.STALE_CONTEXT,((AiBotException)failure(client.replyMarkdownAsync(old,"late"))).getCode());
        client.replyMarkdownAsync(fresh,"ok").toCompletableFuture().get(2,TimeUnit.SECONDS);
    }
    @Test public void concurrentUpdateAndFinishCannotWriteAfterFinalFrame() throws Exception {
        upgrade();start(options());StreamSession stream=client.newStream(message("r"));ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);
        try {
            Future<?> update=workers.submit(()->{try{gate.await();stream.updateAsync("progress").toCompletableFuture().get(2,TimeUnit.SECONDS);}catch(Exception expected){}});
            Future<?> finish=workers.submit(()->{try{gate.await();stream.finishAsync("done").toCompletableFuture().get(2,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}});
            gate.countDown();update.get(3,TimeUnit.SECONDS);finish.get(3,TimeUnit.SECONDS);assertTrue(stream.isFinished());
            JsonNode frame;boolean finished=false;while((frame=outbound.poll(50,TimeUnit.MILLISECONDS))!=null){assertFalse("frame after finish",finished);finished=frame.path("body").path("stream").path("finish").asBoolean();}assertTrue(finished);
        }finally{workers.shutdownNow();}
    }
    @Test public void sharedConversationQuotaIncludesPassiveAndActiveMessages() throws Exception {
        upgrade();start(options());Frame callback=message("r");for(int i=0;i<30;i++)client.replyMarkdownAsync(callback,"text").toCompletableFuture().get(2,TimeUnit.SECONDS);
        SendMsgBody proactive=markdown("user-1","extra");proactive.setChatType(0);Throwable error=failure(client.sendMessageAsync(proactive));assertTrue(error instanceof RateLimitException);assertTrue(((RateLimitException)error).getRetryAfterMs()>0);
        assertEquals(AiBotException.Code.RATE_LIMITED,((AiBotException)error).getCode());
    }
    @Test public void chunkUploadResumesSameSessionAfterConnectionLoss() throws Exception {
        AtomicBoolean disconnected=new AtomicBoolean();uploadPolicy(false);BiConsumer<WebSocket,JsonNode> normal=policy;
        policy=(ws,frame)->{if(Constants.CMD_UPLOAD_MEDIA_CHUNK.equals(frame.path("cmd").asText()) && disconnected.compareAndSet(false,true))ws.close(1001,"simulate disconnect");else normal.accept(ws,frame);};
        upgrade();upgrade();start(options());client.uploadMediaAsync("file","file.bin",new byte[5]).toCompletableFuture().get(4,TimeUnit.SECONDS);
        sent();JsonNode lost=sent(),retried=sent();assertEquals(lost.path("body"),retried.path("body"));assertEquals(2,server.getRequestCount());
    }
    @Test public void downloadEnforcesHeaderAndStreamingLimitsAndFilename() throws Exception {
        server.enqueue(new MockResponse().setBody("12345").setHeader("Content-Disposition","attachment; filename=\"report.txt\""));
        MediaUtils.DownloadResult result=MediaUtils.downloadFile(server.url("/opaque").toString(),null);assertEquals("report.txt",result.getFilename());assertEquals(5,result.getData().length);
        server.enqueue(new MockResponse().setChunkedBody("123456",2));
        try{MediaUtils.downloadFile(server.url("/too-large").toString(),null,5,1000,1000);fail();}catch(java.io.IOException expected){}
    }
    @Test public void streamFeedbackIsAcceptedOnAnyFramePerOfficialDoc() throws Exception {
        upgrade();start(options());Frame callback=message("r");ReplyFeedback feedback=new ReplyFeedback();feedback.setId("f");
        client.replyStreamAsync(callback,"s","first",false,feedback).toCompletableFuture().get(2,TimeUnit.SECONDS);
        // 官方文档未限制 feedback 仅首帧：续帧携带 feedback 应照常发送成功
        client.replyStreamAsync(callback,"s","second",true,feedback).toCompletableFuture().get(2,TimeUnit.SECONDS);
    }
    @Test public void uploadRootPathFailsFastWithoutOccupyingTaskSlot() throws Exception {
        upgrade();start(options());
        CompletionStage<?> stage=client.uploadMediaAsync("file",java.nio.file.Paths.get("/"));
        assertTrue(stage.toCompletableFuture().isDone()); // 调用线程同步失败，不占媒体任务槽位
        AiBotException error=(AiBotException)failure(stage);
        assertEquals(AiBotException.Code.INVALID_ARGUMENT,error.getCode());
    }
    @Test public void handlerExceptionCarriesThrowableToEventErrorHandler() throws Exception {
        upgrade();start(options());
        AtomicReference<Throwable> captured=new AtomicReference<>();
        client.setEventErrorHandler((event,error)->captured.compareAndSet(null,error));
        client.on(Constants.EVENT_MESSAGE_TEXT,(frame,body)->{throw new IllegalStateException("boom");});
        message("err-req");
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(captured.get()==null&&System.nanoTime()<until)Thread.sleep(5);
        assertNotNull(captured.get());assertEquals("boom",captured.get().getMessage());
    }

    @Test public void normalCloseDoesNotLookLikeCallbackOverloadAndRunInterruptCloses() throws Exception {
        upgrade();start(options());AtomicBoolean interrupted=new AtomicBoolean();
        Thread runner=new Thread(()->{try{client.run();}catch(InterruptedException e){interrupted.set(Thread.currentThread().isInterrupted());}});
        runner.start();runner.interrupt();runner.join(2000);assertFalse(runner.isAlive());assertTrue(interrupted.get());assertEquals(BotConnectionState.CLOSED,client.getState());
        Thread.sleep(30);assertEquals(0,client.getRejectedCallbackCount());
    }
    static SendMsgBody markdown(String chat,String content) {SendMsgBody body=new SendMsgBody();body.setChatId(chat);body.setChatType(1);body.setMsgType("markdown");body.setMarkdown(new MarkdownContent(content));return body;}
}
