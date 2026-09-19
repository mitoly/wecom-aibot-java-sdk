package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 官方协议 FeedbackEvent 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackEvent extends ProtocolModel {
    @JsonProperty("id") private String id;
    @JsonProperty("type") private Integer type;
    @JsonProperty("content") private String content;
    @JsonProperty("inaccurate_reason_list") private List<Integer> inaccurateReasonList;
    public String getId() { return id; }
    public void setId(String value) { this.id = value; }
    public Integer getType() { return type; }
    public void setType(Integer value) { this.type = value; }
    public String getContent() { return content; }
    public void setContent(String value) { this.content = value; }
    public List<Integer> getInaccurateReasonList() { return inaccurateReasonList; }
    public void setInaccurateReasonList(List<Integer> value) { this.inaccurateReasonList = value; }
}
