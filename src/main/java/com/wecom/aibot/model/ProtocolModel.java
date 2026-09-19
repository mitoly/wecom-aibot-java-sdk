package com.wecom.aibot.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** 保留尚未建模的协议字段，避免新增字段被静默丢弃。 */
public class ProtocolModel {
    private final Map<String, JsonNode> extensions = new LinkedHashMap<>();
    @JsonAnySetter public void putExtension(String name, JsonNode value) { extensions.put(name, value); }
    @JsonAnyGetter public Map<String, JsonNode> extensions() { return extensions; }
}
