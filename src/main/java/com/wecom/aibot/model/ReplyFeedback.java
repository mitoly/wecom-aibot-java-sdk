package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 官方协议 ReplyFeedback 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReplyFeedback extends ProtocolModel {
    @JsonProperty("id") private String id;
    public String getId() { return id; }
    public void setId(String value) { this.id = value; }
}
