package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * aibot_upload_media_init 的请求体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UploadInitBody {

    @JsonProperty("type")
    private String type;

    @JsonProperty("filename")
    private String filename;

    @JsonProperty("total_size")
    private long totalSize;

    @JsonProperty("total_chunks")
    private int totalChunks;

    @JsonProperty("md5")
    private String md5;

    public UploadInitBody() {
    }

    public UploadInitBody(String type, String filename, long totalSize, int totalChunks, String md5) {
        this.type = type;
        this.filename = filename;
        this.totalSize = totalSize;
        this.totalChunks = totalChunks;
        this.md5 = md5;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }
}
