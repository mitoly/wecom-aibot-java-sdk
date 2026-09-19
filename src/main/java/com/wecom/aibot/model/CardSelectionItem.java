package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 官方协议 CardSelectionItem 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardSelectionItem extends ProtocolModel {
    @JsonProperty("question_key") private String questionKey;
    @JsonProperty("title") private String title;
    @JsonProperty("disable") private Boolean disable;
    @JsonProperty("selected_id") private String selectedId;
    @JsonProperty("option_list") private List<CardOption> optionList;
    public String getQuestionKey() { return questionKey; }
    public void setQuestionKey(String value) { this.questionKey = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = value; }
    public Boolean getDisable() { return disable; }
    public void setDisable(Boolean value) { this.disable = value; }
    public String getSelectedId() { return selectedId; }
    public void setSelectedId(String value) { this.selectedId = value; }
    public List<CardOption> getOptionList() { return optionList; }
    public void setOptionList(List<CardOption> value) { this.optionList = value; }
}
