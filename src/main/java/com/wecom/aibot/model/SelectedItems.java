package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 官方协议 SelectedItems 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SelectedItems extends ProtocolModel {
    @JsonProperty("selected_item") private List<SelectedItem> selectedItem;
    public List<SelectedItem> getSelectedItem() { return selectedItem; }
    public void setSelectedItem(List<SelectedItem> value) { this.selectedItem = value; }
}
