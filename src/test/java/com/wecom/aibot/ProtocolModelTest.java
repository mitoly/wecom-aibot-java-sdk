package com.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.aibot.model.*;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.Assert.*;

public class ProtocolModelTest {
    final ObjectMapper mapper=new ObjectMapper();
    static String fixture(String name) throws IOException {
        try(InputStream in=ProtocolModelTest.class.getResourceAsStream("/protocol/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            assertNotNull(in);byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return new String(out.toByteArray(),"UTF-8");
        }
    }
    @Test public void nestedCardAndLegacyAccessors() throws Exception {
        Frame frame=mapper.readValue(fixture("card-event.json"),Frame.class);
        EventCallbackBody event=mapper.treeToValue(frame.getBody(),EventCallbackBody.class);
        assertEquals("approve",event.getEvent().getEventKey());assertEquals("approve",event.getEvent().getButtonKey());assertEquals("task_1",event.getEvent().getTaskId());
        assertEquals("opt1",event.getEvent().getTemplateCardEvent().getSelectedItems().getSelectedItem().get(0).getOptionIds().getOptionId().get(0));
        assertEquals("group",event.getChatType());assertEquals("corp-1",event.getFrom().getCorpId());
        JsonNode serialized=mapper.valueToTree(event);assertEquals("approve",serialized.path("event").path("template_card_event").path("event_key").asText());
        assertFalse(serialized.path("event").has("buttonKey"));assertFalse(serialized.path("event").has("taskId"));
    }
    @Test public void flatLegacyInputRetainsEventKey() throws Exception {
        EventInfo event=mapper.readValue("{\"eventtype\":\"template_card_event\",\"event_key\":\"approve\",\"task_id\":\"task_1\"}",EventInfo.class);
        assertEquals("approve",event.getButtonKey());assertEquals("task_1",event.getTaskId());
    }
    @Test public void feedbackDetailsAndUnknownFieldsSurvive() throws Exception {
        Frame frame=mapper.readValue(fixture("feedback-event.json"),Frame.class);EventCallbackBody event=mapper.treeToValue(frame.getBody(),EventCallbackBody.class);
        FeedbackEvent feedback=event.getEvent().getFeedbackEvent();assertEquals("feedback-id",feedback.getId());assertEquals(Arrays.asList(2,4),feedback.getInaccurateReasonList());
        frame=mapper.readValue(fixture("text-callback.json"),Frame.class);MsgCallbackBody message=mapper.treeToValue(frame.getBody(),MsgCallbackBody.class);
        assertEquals(1,mapper.valueToTree(message).path("future_field").path("v").asInt());
    }
    @Test public void ackPresenceAndInternalContextAreNotSerialized() throws Exception {
        Frame missing=mapper.readValue("{\"headers\":{\"req_id\":\"r\"}}",Frame.class);assertFalse(missing.hasErrCode());
        Frame ack=mapper.readValue("{\"headers\":{\"req_id\":\"r\"},\"errcode\":0}",Frame.class);assertTrue(ack.hasErrCode());
        ack.markReceived(10,1,"private-context");String encoded=mapper.writeValueAsString(ack);assertFalse(encoded.contains("private-context"));assertFalse(encoded.contains("generation"));
    }
    @Test public void allFiveCardModelsRoundTrip() throws Exception {
        for(String type:Arrays.asList("text_notice","news_notice","button_interaction","vote_interaction","multiple_interaction")) {
            String raw="{\"card_type\":\""+type+"\",\"source\":{\"icon_url\":\"https://example.com/a.png\"},\"card_action\":{\"type\":1,\"url\":\"https://example.com\"},\"checkbox\":{\"question_key\":\"q\",\"option_list\":[{\"id\":\"1\",\"text\":\"yes\"}]},\"select_list\":[{\"question_key\":\"q2\",\"option_list\":[{\"id\":\"2\",\"text\":\"two\"}]}],\"card_image\":{\"url\":\"https://example.com/img\"},\"submit_button\":{\"key\":\"submit\",\"text\":\"ok\"},\"future_style\":1}";
            TemplateCard card=mapper.readValue(raw,TemplateCard.class);assertEquals(mapper.readTree(raw),mapper.valueToTree(card));ProtocolValidator.card(mapper.valueToTree(card));
        }
    }
    @Test public void rejectsUnsupportedStreamCombinationAndUtf8Overflow() throws Exception {
        for(String raw:Arrays.asList("{\"msgtype\":\"stream_with_template_card\"}","{\"msgtype\":\"stream\",\"stream\":{\"id\":\"x\",\"msg_item\":[]}}")) {
            try{ProtocolValidator.body(Constants.CMD_RESPOND_MSG,mapper.readTree(raw));fail();}catch(AiBotException expected){assertEquals(AiBotException.Code.INVALID_ARGUMENT,expected.getCode());}
        }
        StringBuilder large=new StringBuilder();for(int i=0;i<7000;i++)large.append('中');MarkdownContent markdown=new MarkdownContent(large.toString());ReplyBody reply=new ReplyBody();reply.setMsgType("markdown");reply.setMarkdown(markdown);
        try{ProtocolValidator.body(Constants.CMD_RESPOND_MSG,mapper.valueToTree(reply));fail();}catch(AiBotException expected){}
    }

    @Test public void videoTitleAndDescriptionOverflowTruncateOnUtf8Boundary() throws Exception {
        StringBuilder ascii=new StringBuilder();for(int i=0;i<65;i++)ascii.append('a');
        StringBuilder cjk=new StringBuilder();for(int i=0;i<172;i++)cjk.append('中');
        String raw="{\"msgtype\":\"video\",\"video\":{\"media_id\":\"m\",\"title\":\""+ascii+"\",\"description\":\""+cjk+"\"}}";
        com.fasterxml.jackson.databind.node.ObjectNode body=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(raw);
        ProtocolValidator.body(Constants.CMD_RESPOND_MSG,body);
        byte[] title=body.path("video").path("title").asText().getBytes("UTF-8");
        byte[] description=body.path("video").path("description").asText().getBytes("UTF-8");
        assertEquals(64,title.length);assertEquals(510,description.length); // 510=512 内最大 3 字节对齐，未截出半个字符
        assertEquals(cjk.substring(0,170),body.path("video").path("description").asText());
    }

    @Test public void uploadInitAcceptsAnyChunkLayoutWithinOfficialLimits() throws Exception {
        String raw="{\"type\":\"file\",\"filename\":\"a.pdf\",\"total_size\":23330,\"total_chunks\":100,\"md5\":\"0123456789abcdef0123456789abcdef\"}";
        ProtocolValidator.body(Constants.CMD_UPLOAD_MEDIA_INIT,mapper.readTree(raw)); // 自定义更小分片合法：单片≤512KB 且总数≤100
        try{ProtocolValidator.body(Constants.CMD_UPLOAD_MEDIA_INIT,mapper.readTree(raw.replace("\"total_chunks\":100","\"total_chunks\":101")));fail();}catch(AiBotException expected){assertEquals(AiBotException.Code.INVALID_ARGUMENT,expected.getCode());}
    }

    @Test public void sendMsgTextRejectionCarriesGuidance() throws Exception {
        SendMsgBody body=new SendMsgBody();body.setChatId("u");body.setChatType(1);body.setMsgType("text");body.setText(new TextContent("hi"));
        try{ProtocolValidator.body(Constants.CMD_SEND_MSG,mapper.valueToTree(body));fail();}
        catch(AiBotException expected){assertTrue(expected.getMessage().contains("markdown"));}
    }

    @Test public void officialMixedFieldAndLegacyAliasBothExposeOrderedItems() throws Exception {
        String items="[{\"msgtype\":\"text\",\"text\":{\"content\":\"caption\"}},{\"msgtype\":\"image\",\"image\":{\"url\":\"https://example.com/a\",\"aeskey\":\"key\"}}]";
        for(String field:Arrays.asList("msg_item","items")) {
            MixedContent mixed=mapper.readValue("{\""+field+"\":"+items+"}",MixedContent.class);
            assertEquals(2,mixed.getItems().size());assertEquals("caption",mixed.getItems().get(0).getText().getContent());assertEquals("key",mixed.getItems().get(1).getImage().getAesKey());
            JsonNode encoded=mapper.valueToTree(mixed);assertTrue(encoded.has("msg_item"));assertFalse(encoded.has("items"));
        }
    }
}
