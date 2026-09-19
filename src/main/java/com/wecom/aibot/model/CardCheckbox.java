package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 官方协议 CardCheckbox 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardCheckbox extends ProtocolModel {
    @JsonProperty("question_key") private String questionKey;
    @JsonProperty("disable") private Boolean disable;
    @JsonProperty("mode") private Integer mode;
    @JsonProperty("option_list") private List<CardOption> optionList;
    public String getQuestionKey() { return questionKey; }
    public void setQuestionKey(String value) { this.questionKey = value; }
    public Boolean getDisable() { return disable; }
    public void setDisable(Boolean value) { this.disable = value; }
    public Integer getMode() { return mode; }
    public void setMode(Integer value) { this.mode = value; }
    public List<CardOption> getOptionList() { return optionList; }
    public void setOptionList(List<CardOption> value) { this.optionList = value; }
}
