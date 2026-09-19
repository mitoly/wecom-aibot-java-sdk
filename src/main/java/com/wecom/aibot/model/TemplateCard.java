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
public class TemplateCard extends ProtocolModel {

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
    @JsonProperty("source") private CardSource source;
    public CardSource getSource() { return source; }
    public void setSource(CardSource value) { this.source = value; }
    @JsonProperty("action_menu") private CardActionMenu actionMenu;
    public CardActionMenu getActionMenu() { return actionMenu; }
    public void setActionMenu(CardActionMenu value) { this.actionMenu = value; }
    @JsonProperty("emphasis_content") private CardEmphasisContent emphasisContent;
    public CardEmphasisContent getEmphasisContent() { return emphasisContent; }
    public void setEmphasisContent(CardEmphasisContent value) { this.emphasisContent = value; }
    @JsonProperty("quote_area") private CardQuoteArea quoteArea;
    public CardQuoteArea getQuoteArea() { return quoteArea; }
    public void setQuoteArea(CardQuoteArea value) { this.quoteArea = value; }
    @JsonProperty("jump_list") private List<CardJumpAction> jumpList;
    public List<CardJumpAction> getJumpList() { return jumpList; }
    public void setJumpList(List<CardJumpAction> value) { this.jumpList = value; }
    @JsonProperty("card_action") private CardAction cardAction;
    public CardAction getCardAction() { return cardAction; }
    public void setCardAction(CardAction value) { this.cardAction = value; }
    @JsonProperty("card_image") private CardImage cardImage;
    public CardImage getCardImage() { return cardImage; }
    public void setCardImage(CardImage value) { this.cardImage = value; }
    @JsonProperty("image_text_area") private CardImageTextArea imageTextArea;
    public CardImageTextArea getImageTextArea() { return imageTextArea; }
    public void setImageTextArea(CardImageTextArea value) { this.imageTextArea = value; }
    @JsonProperty("vertical_content_list") private List<CardTitle> verticalContentList;
    public List<CardTitle> getVerticalContentList() { return verticalContentList; }
    public void setVerticalContentList(List<CardTitle> value) { this.verticalContentList = value; }
    @JsonProperty("button_selection") private CardSelectionItem buttonSelection;
    public CardSelectionItem getButtonSelection() { return buttonSelection; }
    public void setButtonSelection(CardSelectionItem value) { this.buttonSelection = value; }
    @JsonProperty("checkbox") private CardCheckbox checkbox;
    public CardCheckbox getCheckbox() { return checkbox; }
    public void setCheckbox(CardCheckbox value) { this.checkbox = value; }
    @JsonProperty("select_list") private List<CardSelectionItem> selectList;
    public List<CardSelectionItem> getSelectList() { return selectList; }
    public void setSelectList(List<CardSelectionItem> value) { this.selectList = value; }
    @JsonProperty("submit_button") private CardSubmitButton submitButton;
    public CardSubmitButton getSubmitButton() { return submitButton; }
    public void setSubmitButton(CardSubmitButton value) { this.submitButton = value; }
    @JsonProperty("feedback") private ReplyFeedback feedback;
    public ReplyFeedback getFeedback() { return feedback; }
    public void setFeedback(ReplyFeedback value) { this.feedback = value; }
}
