package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 水平键值对条目。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardKV extends ProtocolModel {

    @JsonProperty("keyname")
    private String keyName;

    @JsonProperty("value")
    private String value;

    public CardKV() {
    }

    public CardKV(String keyName, String value) {
        this.keyName = keyName;
        this.value = value;
    }

    public String getKeyName() {
        return keyName;
    }

    public void setKeyName(String keyName) {
        this.keyName = keyName;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
    @JsonProperty("type") private Integer type;
    public Integer getType() { return type; }
    public void setType(Integer value) { this.type = value; }
    @JsonProperty("url") private String url;
    public String getUrl() { return url; }
    public void setUrl(String value) { this.url = value; }
    @JsonProperty("userid") private String userId;
    public String getUserId() { return userId; }
    public void setUserId(String value) { this.userId = value; }
}
