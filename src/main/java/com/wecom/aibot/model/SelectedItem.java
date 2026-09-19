package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 官方协议 SelectedItem 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SelectedItem extends ProtocolModel {
    @JsonProperty("question_key") private String questionKey;
    @JsonProperty("option_ids") private OptionIds optionIds;
    public String getQuestionKey() { return questionKey; }
    public void setQuestionKey(String value) { this.questionKey = value; }
    public OptionIds getOptionIds() { return optionIds; }
    public void setOptionIds(OptionIds value) { this.optionIds = value; }
}
