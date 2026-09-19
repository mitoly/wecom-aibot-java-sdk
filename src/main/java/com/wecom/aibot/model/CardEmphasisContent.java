package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 官方协议 CardEmphasisContent 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardEmphasisContent extends ProtocolModel {
    @JsonProperty("title") private String title;
    @JsonProperty("desc") private String desc;
    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = value; }
    public String getDesc() { return desc; }
    public void setDesc(String value) { this.desc = value; }
}
