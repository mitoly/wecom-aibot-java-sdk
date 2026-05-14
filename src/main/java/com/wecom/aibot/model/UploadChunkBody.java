package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * aibot_upload_media_chunk 的请求体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UploadChunkBody {

    @JsonProperty("upload_id")
    private String uploadId;

    @JsonProperty("chunk_index")
    private int chunkIndex;

    @JsonProperty("base64_data")
    private String base64Data;

    public UploadChunkBody() {
    }

    public UploadChunkBody(String uploadId, int chunkIndex, String base64Data) {
        this.uploadId = uploadId;
        this.chunkIndex = chunkIndex;
        this.base64Data = base64Data;
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getBase64Data() {
        return base64Data;
    }

    public void setBase64Data(String base64Data) {
        this.base64Data = base64Data;
    }
}
