package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 官方协议 OptionIds 结构。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OptionIds extends ProtocolModel {
    @JsonProperty("option_id") private List<String> optionId;
    public List<String> getOptionId() { return optionId; }
    public void setOptionId(List<String> value) { this.optionId = value; }
}
