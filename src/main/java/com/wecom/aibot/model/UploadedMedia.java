package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 临时素材信息；createdAt 为秒级 Unix 时间戳。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UploadedMedia extends ProtocolModel {
    @JsonProperty("type") private String type;
    @JsonProperty("media_id") private String mediaId;
    @JsonProperty("created_at") private Long createdAt;
    public String getType() { return type; }
    public void setType(String value) { this.type = value; }
    public String getMediaId() { return mediaId; }
    public void setMediaId(String value) { this.mediaId = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
}
