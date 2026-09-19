package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** 事件详情按 eventtype 对应字段嵌套；旧 getter 兼容映射。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventInfo extends ProtocolModel {
    @JsonProperty("eventtype") private String eventType;
    @JsonProperty("template_card_event") private TemplateCardEvent templateCardEvent;
    @JsonProperty("feedback_event") private FeedbackEvent feedbackEvent;
    public String getEventType() { return eventType; }
    public void setEventType(String value) { this.eventType = value; }
    public TemplateCardEvent getTemplateCardEvent() { return templateCardEvent; }
    public void setTemplateCardEvent(TemplateCardEvent value) { this.templateCardEvent = value; }
    public FeedbackEvent getFeedbackEvent() { return feedbackEvent; }
    public void setFeedbackEvent(FeedbackEvent value) { this.feedbackEvent = value; }

    private String legacy(String name) {
        JsonNode node = extensions().get(name); return node == null || node.isNull() ? null : node.asText();
    }
    @JsonIgnore public String getEventKey() {
        return templateCardEvent != null ? templateCardEvent.getEventKey() : legacy("event_key") != null ? legacy("event_key") : legacy("button_key");
    }
    @JsonIgnore public String getTaskId() { return templateCardEvent != null ? templateCardEvent.getTaskId() : legacy("task_id"); }
    @Deprecated @JsonIgnore public String getButtonKey() { return getEventKey(); }
    @Deprecated public void setButtonKey(String value) { ensureCard().setEventKey(value); }
    public void setTaskId(String value) { ensureCard().setTaskId(value); }
    @Deprecated @JsonIgnore public String getOptionId() { return legacy("option_id"); }
    @Deprecated public void setOptionId(String value) { putExtension("option_id", com.fasterxml.jackson.databind.node.TextNode.valueOf(value)); }
    @Deprecated @JsonIgnore public String getFeedbackVal() { return feedbackEvent != null && feedbackEvent.getType() != null ? String.valueOf(feedbackEvent.getType()) : legacy("feedback_val"); }
    @Deprecated public void setFeedbackVal(String value) { putExtension("feedback_val", com.fasterxml.jackson.databind.node.TextNode.valueOf(value)); }
    private TemplateCardEvent ensureCard() { if (templateCardEvent == null) templateCardEvent = new TemplateCardEvent(); return templateCardEvent; }
}
