package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * aibot_msg_callback 帧的消息体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MsgCallbackBody {

    @JsonProperty("msgid")
    private String msgId;

    @JsonProperty("aibotid")
    private String aiBotId;

    @JsonProperty("chatid")
    private String chatId;

    @JsonProperty("chattype")
    private String chatType;

    @JsonProperty("from")
    private Sender from;

    @JsonProperty("msgtype")
    private String msgType;

    @JsonProperty("text")
    private TextContent text;

    @JsonProperty("image")
    private MediaContent image;

    @JsonProperty("mixed")
    private MixedContent mixed;

    @JsonProperty("voice")
    private VoiceContent voice;

    @JsonProperty("file")
    private MediaContent file;

    @JsonProperty("video")
    private MediaContent video;

    public MsgCallbackBody() {
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
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

    public String getChatType() {
        return chatType;
    }

    public void setChatType(String chatType) {
        this.chatType = chatType;
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

    public TextContent getText() {
        return text;
    }

    public void setText(TextContent text) {
        this.text = text;
    }

    public MediaContent getImage() {
        return image;
    }

    public void setImage(MediaContent image) {
        this.image = image;
    }

    public MixedContent getMixed() {
        return mixed;
    }

    public void setMixed(MixedContent mixed) {
        this.mixed = mixed;
    }

    public VoiceContent getVoice() {
        return voice;
    }

    public void setVoice(VoiceContent voice) {
        this.voice = voice;
    }

    public MediaContent getFile() {
        return file;
    }

    public void setFile(MediaContent file) {
        this.file = file;
    }

    public MediaContent getVideo() {
        return video;
    }

    public void setVideo(MediaContent video) {
        this.video = video;
    }
}
