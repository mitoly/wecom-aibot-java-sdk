package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 官方协议 CardImage 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardImage extends ProtocolModel {
    @JsonProperty("url") private String url;
    @JsonProperty("aspect_ratio") private Double aspectRatio;
    public String getUrl() { return url; }
    public void setUrl(String value) { this.url = value; }
    public Double getAspectRatio() { return aspectRatio; }
    public void setAspectRatio(Double value) { this.aspectRatio = value; }
}
