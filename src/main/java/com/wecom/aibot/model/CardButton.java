package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 可交互的按钮。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardButton extends ProtocolModel {

    @JsonProperty("text")
    private String text;

    @JsonProperty("style")
    private Integer style;

    @JsonProperty("key")
    private String key;

    public CardButton() {
    }

    public CardButton(String text, String key) {
        this.text = text;
        this.key = key;
    }

    public CardButton(String text, Integer style, String key) {
        this.text = text;
        this.style = style;
        this.key = key;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Integer getStyle() {
        return style;
    }

    public void setStyle(Integer style) {
        this.style = style;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
