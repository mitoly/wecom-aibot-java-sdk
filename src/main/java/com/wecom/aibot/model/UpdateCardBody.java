package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * aibot_respond_update_msg 的消息体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateCardBody {

    @JsonProperty("response_type")
    private String responseType;

    @JsonProperty("template_card")
    private TemplateCard templateCard;

    public UpdateCardBody() {
    }

    public UpdateCardBody(String responseType, TemplateCard templateCard) {
        this.responseType = responseType;
        this.templateCard = templateCard;
    }

    public String getResponseType() {
        return responseType;
    }

    public void setResponseType(String responseType) {
        this.responseType = responseType;
    }

    public TemplateCard getTemplateCard() {
        return templateCard;
    }

    public void setTemplateCard(TemplateCard templateCard) {
        this.templateCard = templateCard;
    }
}
