package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * aibot_event_callback 帧的消息体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventCallbackBody {

    @JsonProperty("msgid")
    private String msgId;

    @JsonProperty("create_time")
    private long createTime;

    @JsonProperty("aibotid")
    private String aiBotId;

    @JsonProperty("chatid")
    private String chatId;

    @JsonProperty("from")
    private Sender from;

    @JsonProperty("msgtype")
    private String msgType;

    @JsonProperty("event")
    private EventInfo event;

    public EventCallbackBody() {
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public String getAiBotId() {
        return aiBotId;
    }

    public void setAiBotId(String aiBotId) {
        this.aiBotId = aiBotId;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public Sender getFrom() {
        return from;
    }

    public void setFrom(Sender from) {
        this.from = from;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public EventInfo getEvent() {
        return event;
    }

    public void setEvent(EventInfo event) {
        this.event = event;
    }
}
