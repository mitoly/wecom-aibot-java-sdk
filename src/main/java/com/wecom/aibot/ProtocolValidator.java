package com.wecom.aibot;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 根据长连接文档校验实际发送 JSON，而不是只校验便捷方法。 */
final class ProtocolValidator {
    private static final Set<String> ORDINARY = new HashSet<>(Arrays.asList("stream","markdown","template_card","image","file","voice","video"));
    private static final Set<String> CARDS = new HashSet<>(Arrays.asList("text_notice","news_notice","button_interaction","vote_interaction","multiple_interaction"));
    private ProtocolValidator() {}
    static void body(String cmd, JsonNode body) throws AiBotException {
        if(body==null || !body.isObject())invalid("消息体必须为对象");
        if(Constants.CMD_RESPOND_UPDATE_MSG.equals(cmd)) {
            if(!"update_template_card".equals(body.path("response_type").asText()))invalid("卡片更新类型无效");
            card(body.path("template_card"));
            if(body.has("userids")) {
                if(!body.get("userids").isArray() || body.get("userids").isEmpty())invalid("userids 必须为非空列表");
                for(JsonNode id:body.get("userids"))text(id,256,true,"userid");
            }
            return;
        }
        if(Constants.CMD_SUBSCRIBE.equals(cmd)) { text(body.path("bot_id"),256,true,"bot_id"); text(body.path("secret"),4096,true,"secret");return; }
        if(Constants.CMD_PING.equals(cmd))return;
        if(Constants.CMD_UPLOAD_MEDIA_INIT.equals(cmd)) {
            if(!body.path("total_size").isIntegralNumber() || !body.path("total_size").canConvertToLong())invalid("total_size 必须为整数");
            MediaTransfer.validateUpload(body.path("type").asText(),body.path("filename").asText(),body.path("total_size").asLong());
            int chunks=body.path("total_chunks").asInt();
            if(!body.path("total_chunks").isIntegralNumber() || chunks<1 || chunks>100)invalid("分片数量无效");
            if(body.has("md5") && !body.path("md5").asText().matches("[0-9a-fA-F]{32}"))invalid("MD5 无效");return;
        }
        if(Constants.CMD_UPLOAD_MEDIA_CHUNK.equals(cmd)) {
            text(body.path("upload_id"),256,true,"upload_id");
            if(!body.path("chunk_index").isIntegralNumber() || body.path("chunk_index").asInt(-1)<0 || body.path("chunk_index").asInt()>99)invalid("分片序号必须在 0..99");
            try {
                byte[] bytes=java.util.Base64.getDecoder().decode(body.path("base64_data").asText());
                if(bytes.length==0 || bytes.length>Constants.UPLOAD_CHUNK_SIZE)invalid("分片大小无效");
            } catch(IllegalArgumentException e){invalid("分片 Base64 无效");}return;
        }
        if(Constants.CMD_UPLOAD_MEDIA_FINISH.equals(cmd)) {text(body.path("upload_id"),256,true,"upload_id");return;}
        boolean welcome=Constants.CMD_RESPOND_WELCOME_MSG.equals(cmd);
        if(!welcome && !Constants.CMD_RESPOND_MSG.equals(cmd) && !Constants.CMD_SEND_MSG.equals(cmd))invalid("未知发送命令");
        String type=body.path("msgtype").asText();
        if(welcome) {
            if(!"text".equals(type) && !"template_card".equals(type))invalid("欢迎语仅支持 text/template_card");
        } else if("text".equals(type) && Constants.CMD_SEND_MSG.equals(cmd)) {
            invalid("主动推送不支持 text；纯文本请用欢迎语回复（replyWelcomeAsync）或 markdown");
        } else if(!ORDINARY.contains(type) || (Constants.CMD_SEND_MSG.equals(cmd) && "stream".equals(type)))invalid("长连接不支持该消息类型");
        if(Constants.CMD_SEND_MSG.equals(cmd)) {
            text(body.path("chatid"),256,true,"chatid");
            if(body.has("chat_type") && (!body.path("chat_type").isIntegralNumber() || body.path("chat_type").asInt()<0 || body.path("chat_type").asInt()>2))invalid("chat_type 无效");
        }
        JsonNode content=body.path(type);if(!content.isObject())invalid("缺少消息内容: "+type);
        if("text".equals(type) || "markdown".equals(type) || "stream".equals(type)) {
            text(content.path("content"),20480,!"stream".equals(type),"content");
            if("stream".equals(type)) {
                text(content.path("id"),256,true,"stream.id");
                if(content.has("msg_item"))invalid("长连接暂不支持 msg_item");
                if(content.has("finish") && !content.path("finish").isBoolean())invalid("finish 必须为布尔值");
            }
        } else if("template_card".equals(type))card(content);
        else {
            text(content.path("media_id"),4096,true,"media_id");
            if("video".equals(type)) {requireTextual(content.path("title"),"title");requireTextual(content.path("description"),"description");truncateUtf8(content,"title",64);truncateUtf8(content,"description",512);}
        }
        if(content.has("feedback"))text(content.path("feedback").path("id"),256,true,"feedback.id");
    }
    static void card(JsonNode card) throws AiBotException {
        if(!card.isObject() || !CARDS.contains(card.path("card_type").asText()))invalid("卡片类型无效");
        if(card.has("task_id")) {text(card.path("task_id"),128,true,"task_id");if(!card.path("task_id").asText().matches("[A-Za-z0-9_@-]+"))invalid("task_id 含非法字符");}
        limit(card,"horizontal_content_list",6);limit(card,"jump_list",3);limit(card,"vertical_content_list",4);limit(card,"button_list",6);limit(card,"select_list",3);
        if(card.has("button_list")) for(JsonNode button:card.get("button_list")) {text(button.path("text"),256,true,"button.text");text(button.path("key"),1024,true,"button.key");}
        if(card.has("select_list"))for(JsonNode selection:card.get("select_list"))selection(selection,10);
        if(card.has("button_selection"))selection(card.get("button_selection"),10);
        if(card.has("checkbox"))selection(card.get("checkbox"),20);
        if(card.has("feedback"))text(card.path("feedback").path("id"),256,true,"feedback.id");
    }
    private static void selection(JsonNode value,int max) throws AiBotException {
        text(value.path("question_key"),1024,true,"question_key");limit(value,"option_list",max);
        if(!value.has("option_list") || value.path("option_list").isEmpty())invalid("选项不能为空");
        Set<String> ids=new HashSet<>();for(JsonNode option:value.get("option_list")) {
            text(option.path("id"),128,true,"option.id");text(option.path("text"),256,true,"option.text");if(!ids.add(option.path("id").asText()))invalid("选项 ID 重复");
        }
    }
    private static void limit(JsonNode node,String key,int max) throws AiBotException {
        if(node.has(key) && (!node.get(key).isArray() || node.get(key).size()>max))invalid(key+" 数量无效");
    }
    static void text(JsonNode value,int max,boolean required,String field) throws AiBotException {
        if(value.isMissingNode() || value.isNull()) {if(required)invalid(field+" 不能为空");return;}
        if(!value.isTextual() || (required && value.asText().trim().isEmpty()) || value.asText().getBytes(StandardCharsets.UTF_8).length>max)invalid(field+" 类型或 UTF-8 字节数无效");
    }
    private static void requireTextual(JsonNode value,String field) throws AiBotException {
        if(!value.isMissingNode() && !value.isNull() && !value.isTextual())invalid(field+" 类型无效");
    }
    /** 官方语义：视频标题/描述超长自动截断，按 UTF-8 码点边界不截半个字符；只改可变节点。 */
    private static void truncateUtf8(JsonNode node,String field,int maxBytes) throws AiBotException {
        if(!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode))return;
        JsonNode value=node.path(field);
        if(!value.isTextual())return;
        String text=value.asText();
        if(text.getBytes(StandardCharsets.UTF_8).length<=maxBytes)return;
        StringBuilder kept=new StringBuilder();int used=0,i=0;
        while(i<text.length()) {
            int code=text.codePointAt(i);
            int size=new String(Character.toChars(code)).getBytes(StandardCharsets.UTF_8).length;
            if(used+size>maxBytes)break;
            kept.appendCodePoint(code);used+=size;i+=Character.charCount(code);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).put(field,kept.toString());
    }
    static void invalid(String message) throws AiBotException {throw new AiBotException(AiBotException.Code.INVALID_ARGUMENT,message);}
}
