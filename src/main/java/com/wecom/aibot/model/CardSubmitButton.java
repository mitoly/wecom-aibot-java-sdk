package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 官方协议 CardSubmitButton 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardSubmitButton extends ProtocolModel {
    @JsonProperty("text") private String text;
    @JsonProperty("key") private String key;
    public String getText() { return text; }
    public void setText(String value) { this.text = value; }
    public String getKey() { return key; }
    public void setKey(String value) { this.key = value; }
}
