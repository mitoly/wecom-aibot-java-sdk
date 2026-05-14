package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * aibot_respond_msg 的消息体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReplyBody {

    @JsonProperty("msgtype")
    private String msgType;

    @JsonProperty("text")
    private TextContent text;

    @JsonProperty("stream")
    private StreamContent stream;

    @JsonProperty("markdown")
    private MarkdownContent markdown;

    @JsonProperty("image")
    private MediaContent image;

    @JsonProperty("voice")
    private MediaContent voice;

    @JsonProperty("video")
    private MediaContent video;

    @JsonProperty("file")
    private MediaContent file;

    @JsonProperty("template_card")
    private TemplateCard templateCard;

    public ReplyBody() {
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

    public StreamContent getStream() {
        return stream;
    }

    public void setStream(StreamContent stream) {
        this.stream = stream;
    }

    public MarkdownContent getMarkdown() {
        return markdown;
    }

    public void setMarkdown(MarkdownContent markdown) {
        this.markdown = markdown;
    }

    public MediaContent getImage() {
        return image;
    }

    public void setImage(MediaContent image) {
        this.image = image;
    }

    public MediaContent getVoice() {
        return voice;
    }

    public void setVoice(MediaContent voice) {
        this.voice = voice;
    }

    public MediaContent getVideo() {
        return video;
    }

    public void setVideo(MediaContent video) {
        this.video = video;
    }

    public MediaContent getFile() {
        return file;
    }

    public void setFile(MediaContent file) {
        this.file = file;
    }

    public TemplateCard getTemplateCard() {
        return templateCard;
    }

    public void setTemplateCard(TemplateCard templateCard) {
        this.templateCard = templateCard;
    }
}
