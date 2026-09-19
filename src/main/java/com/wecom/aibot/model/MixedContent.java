package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 图文混排消息内容。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MixedContent extends ProtocolModel {

    @JsonProperty("msg_item")
    @JsonAlias("items")
    private List<MixedItem> items;

    public MixedContent() {
    }

    public List<MixedItem> getItems() {
        return items;
    }

    public void setItems(List<MixedItem> items) {
        this.items = items;
    }
}
