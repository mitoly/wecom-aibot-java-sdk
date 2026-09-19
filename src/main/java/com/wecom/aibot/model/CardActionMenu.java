package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 官方协议 CardActionMenu 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardActionMenu extends ProtocolModel {
    @JsonProperty("desc") private String desc;
    @JsonProperty("action_list") private List<CardButton> actionList;
    public String getDesc() { return desc; }
    public void setDesc(String value) { this.desc = value; }
    public List<CardButton> getActionList() { return actionList; }
    public void setActionList(List<CardButton> value) { this.actionList = value; }
}
