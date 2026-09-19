package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 可下载的媒体资源引用（图片、文件、视频等）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MediaContent extends ProtocolModel {

    @JsonProperty("url")
    private String url;

    @JsonProperty("aeskey")
    private String aesKey;

    @JsonProperty("media_id")
    private String mediaId;

    public MediaContent() {
    }

    public MediaContent(String mediaId) {
        this.mediaId = mediaId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAesKey() {
        return aesKey;
    }

    public void setAesKey(String aesKey) {
        this.aesKey = aesKey;
    }

    public String getMediaId() {
        return mediaId;
    }

    public void setMediaId(String mediaId) {
        this.mediaId = mediaId;
    }
    @JsonProperty("title") private String title;
    public String getTitle() { return title; }
    public void setTitle(String value) { this.title = value; }
    @JsonProperty("description") private String description;
    public String getDescription() { return description; }
    public void setDescription(String value) { this.description = value; }
}
