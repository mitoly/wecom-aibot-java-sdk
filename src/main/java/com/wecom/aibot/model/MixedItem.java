package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 图文混排消息的单个元素。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MixedItem {

    @JsonProperty("msgtype")
    private String msgType;

    @JsonProperty("text")
    private TextContent text;

    @JsonProperty("image")
    private MediaContent image;

    public MixedItem() {
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
}
