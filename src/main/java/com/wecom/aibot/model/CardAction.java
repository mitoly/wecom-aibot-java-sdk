package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 官方协议 CardAction 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardAction extends ProtocolModel {
    @JsonProperty("type") private Integer type;
    @JsonProperty("url") private String url;
    @JsonProperty("appid") private String appId;
    @JsonProperty("pagepath") private String pagePath;
    public Integer getType() { return type; }
    public void setType(Integer value) { this.type = value; }
    public String getUrl() { return url; }
    public void setUrl(String value) { this.url = value; }
    public String getAppId() { return appId; }
    public void setAppId(String value) { this.appId = value; }
    public String getPagePath() { return pagePath; }
    public void setPagePath(String value) { this.pagePath = value; }
}
