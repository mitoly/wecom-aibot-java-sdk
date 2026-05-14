package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * WebSocket 通信的统一消息信封（顶层 JSON 结构）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Frame {

    @JsonProperty("cmd")
    private String cmd;

    @JsonProperty("headers")
    private Headers headers;

    @JsonProperty("body")
    private JsonNode body;

    @JsonProperty("errcode")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int errCode;

    @JsonProperty("errmsg")
    private String errMsg;

    public Frame() {
    }

    public Frame(String cmd, Headers headers, JsonNode body) {
        this.cmd = cmd;
        this.headers = headers;
        this.body = body;
    }

    public String getCmd() {
        return cmd;
    }

    public void setCmd(String cmd) {
        this.cmd = cmd;
    }

    public Headers getHeaders() {
        return headers;
    }

    public void setHeaders(Headers headers) {
        this.headers = headers;
    }

    public JsonNode getBody() {
        return body;
    }

    public void setBody(JsonNode body) {
        this.body = body;
    }

    public int getErrCode() {
        return errCode;
    }

    public void setErrCode(int errCode) {
        this.errCode = errCode;
    }

    public String getErrMsg() {
        return errMsg;
    }

    public void setErrMsg(String errMsg) {
        this.errMsg = errMsg;
    }
}
