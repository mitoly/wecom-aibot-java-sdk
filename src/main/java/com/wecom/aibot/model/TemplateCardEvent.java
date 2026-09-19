package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 官方协议 TemplateCardEvent 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TemplateCardEvent extends ProtocolModel {
    @JsonProperty("card_type") private String cardType;
    @JsonProperty("event_key") private String eventKey;
    @JsonProperty("task_id") private String taskId;
    @JsonProperty("selected_items") private SelectedItems selectedItems;
    public String getCardType() { return cardType; }
    public void setCardType(String value) { this.cardType = value; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String value) { this.eventKey = value; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String value) { this.taskId = value; }
    public SelectedItems getSelectedItems() { return selectedItems; }
    public void setSelectedItems(SelectedItems value) { this.selectedItems = value; }
}
