package com.skill.platform.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.skill.platform.gateway.dal.entity.Skill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * invocation_spec 导出器（§4.1/§5.2）：从 Skill 元数据一键导出
 * markdown / openapi / function-calling tool schema——「前后端拆分」的前端部分，
 * 说明与真实 API 同源不漂移。
 */
@Slf4j
@Component
public class InvocationSpecExporter {

    private final String baseUrl;

    public InvocationSpecExporter(@Value("${skill-platform.public-base-url:https://api.skill-platform.example.com}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public enum Format {
        MARKDOWN, OPENAPI, TOOL_SCHEMA
    }

    public String export(Skill skill, Format format) {
        return switch (format) {
            case MARKDOWN -> markdown(skill);
            case OPENAPI -> openapi(skill);
            case TOOL_SCHEMA -> toolSchema(skill);
        };
    }

    // ------------------------------------------------------------------

    private String markdown(Skill skill) {
        JsonNode spec = JsonCodec.treeOrEmpty(skill.getInvocationSpec());
        JsonNode output = JsonCodec.treeOrEmpty(skill.getOutputConfig());
        JsonNode pricing = JsonCodec.treeOrEmpty(skill.getPricingConfig());
        StringBuilder md = new StringBuilder();
        md.append("# ").append(skill.getName() == null ? skill.getSkillCode() : skill.getName())
                .append("（").append(skill.getSkillCode()).append("）调用说明\n\n");
        if (notBlank(skill.getDescription())) {
            md.append(skill.getDescription()).append("\n\n");
        }
        md.append("- 默认版本：").append(skill.getDefaultVersion() == null ? "-" : skill.getDefaultVersion()).append('\n');
        md.append("- 计费模式：").append(pricing.path("mode").asText("PER_EXECUTION"));
        if (pricing.has("points")) {
            md.append("（").append(pricing.path("points").asLong()).append(" 点/个）");
        }
        if (pricing.has("capPoints")) {
            md.append("（封顶 ").append(pricing.path("capPoints").asLong()).append(" 点）");
        }
        md.append('\n');
        md.append("- 产出数量：").append(output.path("countable").asBoolean(false)
                        ? "支持 count（默认 " + output.path("defaultCount").asInt(1)
                        + "，上限 " + (output.path("maxCount").isMissingNode() ? "-" : output.path("maxCount").asInt()) + "）"
                        : "固定，不支持 count")
                .append('\n');
        String typical = spec.path("typicalDurationSeconds").asText(null);
        md.append("- 典型耗时：").append(typical == null ? "分钟级" : typical + "s").append('\n');
        String materialReq = spec.path("materialRequirements").asText(null);
        md.append("\n## 素材要求\n\n").append(materialReq == null
                ? "video/image/audio/document/data 类型经素材上传接口获取 oss:// 引用；text 直接内联；link 为外部 URL 由 Skill 自行下载。"
                : materialReq).append('\n');
        String guide = spec.path("instructionGuide").asText(null);
        md.append("\n## instructions 写法建议\n\n").append(guide == null
                ? "用自然语言描述目标、风格与约束（如「生成 vlog 风格短视频，控制在 60 秒内」）。"
                : guide).append('\n');
        md.append("""

                ## 调用示例

                ```json
                POST %s/api/v1/execute
                {
                  "skillCode": "%s",
                  "materials": [{ "type": "video", "url": "oss://{tenant}/materials/mat_xxx.mp4" }],
                  "instructions": "...",
                  "context": { "session": "..." },
                  "callbackUrl": "https://your-backend/skill-callback"
                }
                ```

                ## 获取结果（双通道任选其一）

                - 轮询：`GET %s/api/v1/executions/{taskId}`（建议 3-5s 间隔；终态响应即完整结果体）
                - 回调：POST callbackUrl，Header `X-Skill-Signature: HMAC-SHA256(timestamp + body, AppSecret)`、`X-Skill-Timestamp`（容差 5min；请验签）

                ## 进度

                - `progress`（0-100）经轮询可见；可见粒度 = 轮询间隔（3-5s），不支持更细

                ## 错误码

                | code | 含义 |
                |-|-|
                | 40201 | 点数余额不足（响应带 rechargeUrl） |
                | 42901 | 限流 / 超配额 |
                | 40301 | 无权限（回调域名 / 素材归属） |
                | 40401 | 资源不存在 |
                """.formatted(baseUrl, skill.getSkillCode(), baseUrl));
        String billingNote = spec.path("billingNote").asText(null);
        if (billingNote != null) {
            md.append("\n## 计费说明\n\n").append(billingNote).append('\n');
        }
        return md.toString();
    }

    private String openapi(Skill skill) {
        Map<String, Object> executeBody = new LinkedHashMap<>();
        executeBody.put("skillCode", skill.getSkillCode());
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.1.0");
        doc.put("info", Map.of(
                "title", "Skill 执行 API - " + skill.getSkillCode(),
                "version", skill.getDefaultVersion() == null ? "1.0.0" : skill.getDefaultVersion()));
        doc.put("servers", java.util.List.of(Map.of("url", baseUrl)));
        doc.put("paths", Map.of(
                "/api/v1/execute", Map.of("post", Map.of(
                        "summary", "提交执行（统一异步）",
                        "requestBody", Map.of("content", Map.of("application/json", Map.of(
                                "example", executeBody))),
                        "responses", Map.of("200", Map.of("description", "taskId，结果经回调/轮询获取")))),
                "/api/v1/executions/{taskId}", Map.of("get", Map.of(
                        "summary", "查询执行状态（终态响应与回调 body 同构）",
                        "parameters", java.util.List.of(Map.of(
                                "name", "taskId", "in", "path", "required", true,
                                "schema", Map.of("type", "string"))),
                        "responses", Map.of("200", Map.of("description", "状态或完整结果体")))),
                "/api/v1/executions/{taskId}/cancel", Map.of("post", Map.of(
                        "summary", "取消执行（PENDING 直接退款；RUNNING 转取消中）",
                        "responses", Map.of("200", Map.of("description", "取消结果"))))));
        doc.put("x-auth", "Header: X-Skill-AppKey / X-Skill-Timestamp / X-Skill-Signature(HMAC-SHA256)");
        return JsonCodec.write(doc);
    }

    private String toolSchema(Skill skill) {
        JsonNode spec = JsonCodec.treeOrEmpty(skill.getInvocationSpec());
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", skill.getSkillCode());
        schema.put("description", (skill.getDescription() == null ? "" : skill.getDescription())
                + " 统一异步执行：返回 taskId，结果凭 taskId 轮询（建议 3-5s）或等回调。");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("materials", Map.of(
                "type", "array",
                "description", "素材列表：oss:// 引用 / text 内联 / link 外部 URL"));
        properties.put("instructions", Map.of(
                "type", "string",
                "description", spec.path("instructionGuide").asText("自然语言指令，Skill 黑盒自行理解")));
        properties.put("count", Map.of(
                "type", "integer",
                "description", "产出数量（可选，Skill 声明支持时生效）"));
        properties.put("callbackUrl", Map.of(
                "type", "string",
                "description", "回调地址（可选，需域名白名单）"));
        schema.put("parameters", Map.of(
                "type", "object",
                "properties", properties));
        return JsonCodec.write(schema);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
