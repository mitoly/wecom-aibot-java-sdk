package com.wecom.aibot.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.aibot.*;
import com.wecom.aibot.model.EventCallbackBody;
import com.wecom.aibot.model.MixedContent;
import io.agentscope.core.message.UserMessage;
import org.junit.Test;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Mono;
import static org.junit.Assert.*;

/** 使用真实 agent-platform parent/BOM 的无网络消费测试，不修改平台业务模块。 */
public class SdkConsumerSmokeTest {
    @Test public void bootMapperReactorAndAgentScopeShareSdkClasspath() throws Exception {
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext(JacksonAutoConfiguration.class)) {
            ObjectMapper mapper=context.getBean(ObjectMapper.class);
            EventCallbackBody event=mapper.readValue("{\"msgtype\":\"event\",\"event\":{\"eventtype\":\"template_card_event\",\"template_card_event\":{\"event_key\":\"approve\",\"task_id\":\"t\"}}}",EventCallbackBody.class);
            assertEquals("approve",event.getEvent().getEventKey());
            MixedContent mixed=mapper.readValue("{\"msg_item\":[{\"msgtype\":\"text\",\"text\":{\"content\":\"caption\"}}]}",MixedContent.class);
            assertEquals("caption",mixed.getItems().get(0).getText().getContent());
            assertNotNull(UserMessage.builder().textContent("consumer smoke").build());
            try(WeComAiBotClient client=new WeComAiBotClient(new Options().setBotId("offline").setSecret("offline"),new AiBotLogger.NopLogger())) {
                assertEquals(BotConnectionState.STOPPED,Mono.just(client.getState()).block());
            }
        }
    }

    @Test public void singleOkHttpFiveRuntimeCanAuthenticateAndReply() throws Exception {
        assertTrue("实际加载 okhttp-jvm 5，而不是旧 okhttp 4",OkHttpClient.class.getProtectionDomain().getCodeSource().getLocation().toString().contains("okhttp-jvm-5.3.2"));
        assertEquals("OkHttpClient 不得有重复实现",1,java.util.Collections.list(getClass().getClassLoader().getResources("okhttp3/OkHttpClient.class")).size());
        ObjectMapper mapper=new ObjectMapper();BlockingQueue<com.wecom.aibot.model.Frame> incoming=new LinkedBlockingQueue<>();
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onMessage(WebSocket ws,String text) {
                    try {
                        JsonNode frame=mapper.readTree(text);
                        ws.send("{\"headers\":"+frame.path("headers")+",\"errcode\":0}");
                        if("aibot_subscribe".equals(frame.path("cmd").asText()))ws.send("{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"r\"},\"body\":{\"msgid\":\"m\",\"chattype\":\"single\",\"from\":{\"userid\":\"u\"},\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"}}}");
                    }catch(Exception e){throw new AssertionError(e);}
                }
            }));
            server.start();
            try(WeComAiBotClient client=new WeComAiBotClient(new Options().setBotId("offline").setSecret("offline").setWsUrl(server.url("/").toString().replace("http://","ws://")),new AiBotLogger.NopLogger())) {
                client.on(Constants.EVENT_MESSAGE_TEXT,(frame,payload)->incoming.add(frame));
                client.startAsync().toCompletableFuture().get(3,TimeUnit.SECONDS);
                com.wecom.aibot.model.Frame frame=incoming.poll(2,TimeUnit.SECONDS);assertNotNull(frame);
                assertEquals(0,Mono.fromCompletionStage(client.replyMarkdownAsync(frame,"compatible")).block(java.time.Duration.ofSeconds(3)).getErrCode());
            }
        }
    }
}
