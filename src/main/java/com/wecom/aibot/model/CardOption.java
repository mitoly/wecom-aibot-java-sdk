package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 官方协议 CardOption 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardOption extends ProtocolModel {
    @JsonProperty("id") private String id;
    @JsonProperty("text") private String text;
    @JsonProperty("is_checked") private Boolean isChecked;
    public String getId() { return id; }
    public void setId(String value) { this.id = value; }
    public String getText() { return text; }
    public void setText(String value) { this.text = value; }
    public Boolean getIsChecked() { return isChecked; }
    public void setIsChecked(Boolean value) { this.isChecked = value; }
}
