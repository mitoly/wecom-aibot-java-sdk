package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 官方协议 CardSource 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardSource extends ProtocolModel {
    @JsonProperty("icon_url") private String iconUrl;
    @JsonProperty("desc") private String desc;
    @JsonProperty("desc_color") private Integer descColor;
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String value) { this.iconUrl = value; }
    public String getDesc() { return desc; }
    public void setDesc(String value) { this.desc = value; }
    public Integer getDescColor() { return descColor; }
    public void setDescColor(Integer value) { this.descColor = value; }
}
