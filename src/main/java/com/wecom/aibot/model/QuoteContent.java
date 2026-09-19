package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 官方协议 QuoteContent 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuoteContent extends ProtocolModel {
    @JsonProperty("msgtype") private String msgType;
    @JsonProperty("text") private TextContent text;
    @JsonProperty("image") private MediaContent image;
    @JsonProperty("mixed") private MixedContent mixed;
    @JsonProperty("voice") private VoiceContent voice;
    @JsonProperty("file") private MediaContent file;
    @JsonProperty("video") private MediaContent video;
    public String getMsgType() { return msgType; }
    public void setMsgType(String value) { this.msgType = value; }
    public TextContent getText() { return text; }
    public void setText(TextContent value) { this.text = value; }
    public MediaContent getImage() { return image; }
    public void setImage(MediaContent value) { this.image = value; }
    public MixedContent getMixed() { return mixed; }
    public void setMixed(MixedContent value) { this.mixed = value; }
    public VoiceContent getVoice() { return voice; }
    public void setVoice(VoiceContent value) { this.voice = value; }
    public MediaContent getFile() { return file; }
    public void setFile(MediaContent value) { this.file = value; }
    public MediaContent getVideo() { return video; }
    public void setVideo(MediaContent value) { this.video = value; }
}
