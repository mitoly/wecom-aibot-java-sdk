package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Markdown 格式的内容。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarkdownContent extends ProtocolModel {

    @JsonProperty("content")
    private String content;

    public MarkdownContent() {
    }

    public MarkdownContent(String content) {
        this.content = content;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
    @JsonProperty("feedback") private ReplyFeedback feedback;
    public ReplyFeedback getFeedback() { return feedback; }
    public void setFeedback(ReplyFeedback value) { this.feedback = value; }
}
