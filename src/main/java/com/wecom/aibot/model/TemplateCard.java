package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 富交互模板卡片消息。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TemplateCard {

    @JsonProperty("card_type")
    private String cardType;

    @JsonProperty("main_title")
    private CardTitle mainTitle;

    @JsonProperty("sub_title_text")
    private String subTitleText;

    @JsonProperty("horizontal_content_list")
    private List<CardKV> horizontalList;

    @JsonProperty("button_list")
    private List<CardButton> buttonList;

    @JsonProperty("task_id")
    private String taskId;

    public TemplateCard() {
    }

    public String getCardType() {
        return cardType;
    }

    public void setCardType(String cardType) {
        this.cardType = cardType;
    }

    public CardTitle getMainTitle() {
        return mainTitle;
    }

    public void setMainTitle(CardTitle mainTitle) {
        this.mainTitle = mainTitle;
    }

    public String getSubTitleText() {
        return subTitleText;
    }

    public void setSubTitleText(String subTitleText) {
        this.subTitleText = subTitleText;
    }

    public List<CardKV> getHorizontalList() {
        return horizontalList;
    }

    public void setHorizontalList(List<CardKV> horizontalList) {
        this.horizontalList = horizontalList;
    }

    public List<CardButton> getButtonList() {
        return buttonList;
    }

    public void setButtonList(List<CardButton> buttonList) {
        this.buttonList = buttonList;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }
}
