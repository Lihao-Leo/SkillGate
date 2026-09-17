package com.skill.platform.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSON 工具：统一异常包装（DB JSON 列 ↔ 对象）。
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {
    }

    public static <T> T read(String json, Class<T> type) throws JsonProcessingException {
        return MAPPER.readValue(json, type);
    }

    public static <T> T read(String json, TypeReference<T> type) throws JsonProcessingException {
        return MAPPER.readValue(json, type);
    }

    /** 宽松读取：解析失败抛 IllegalArgumentException（调用方按需兜底） */
    public static <T> T readUnchecked(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("json parse failed", e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("json serialize failed", e);
        }
    }

    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalArgumentException("json parse failed: " + json, e);
        }
    }

    /** 解析失败返回空对象（运营配置容错） */
    public static JsonNode treeOrEmpty(String json) {
        try {
            return tree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    public static boolean isValidJson(String json) {
        try {
            MAPPER.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 对 JSON 列做字段级合并更新，返回新 JSON 字符串 */
    public static String mergeJson(String original, Map<String, Object> patch) {
        try {
            Map<String, Object> current = original == null || original.isBlank()
                    ? new java.util.HashMap<>()
                    : MAPPER.readValue(original, new TypeReference<Map<String, Object>>() {
                    });
            current.putAll(patch);
            return MAPPER.writeValueAsString(current);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("json merge failed", e);
        }
    }
}
