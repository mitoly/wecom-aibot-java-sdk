package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 事件的详细信息。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventInfo {

    @JsonProperty("eventtype")
    private String eventType;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("option_id")
    private String optionId;

    @JsonProperty("button_key")
    private String buttonKey;

    @JsonProperty("feedback_val")
    private String feedbackVal;

    public EventInfo() {
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getOptionId() {
        return optionId;
    }

    public void setOptionId(String optionId) {
        this.optionId = optionId;
    }

    public String getButtonKey() {
        return buttonKey;
    }

    public void setButtonKey(String buttonKey) {
        this.buttonKey = buttonKey;
    }

    public String getFeedbackVal() {
        return feedbackVal;
    }

    public void setFeedbackVal(String feedbackVal) {
        this.feedbackVal = feedbackVal;
    }
}
