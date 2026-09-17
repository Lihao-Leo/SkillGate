package com.skill.platform.gateway;

import com.skill.platform.gateway.dal.entity.Skill;
import com.skill.platform.gateway.service.InvocationSpecExporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调用说明导出测试（§5.2）：markdown / openapi / tool-schema 与真实 API 同源。
 */
class InvocationSpecExporterTest {

    private final InvocationSpecExporter exporter =
            new InvocationSpecExporter("https://api.example.com");

    private Skill skill() {
        Skill skill = new Skill();
        skill.setSkillCode("video-generation");
        skill.setName("视频生成");
        skill.setDescription("输入素材与指令生成短视频");
        skill.setDefaultVersion("1.2.0");
        skill.setOutputConfig("{\"countable\":true,\"defaultCount\":1,\"maxCount\":20}");
        skill.setPricingConfig("{\"mode\":\"PER_EXECUTION\",\"points\":10}");
        skill.setInvocationSpec("{\"materialRequirements\":\"至少 1 条视频素材\",\"typicalDurationSeconds\":180}");
        return skill;
    }

    @Test
    void markdown_contains_contract_sections() {
        String markdown = exporter.export(skill(), InvocationSpecExporter.Format.MARKDOWN);

        assertThat(markdown).contains("video-generation");
        assertThat(markdown).contains("POST https://api.example.com/api/v1/execute");
        assertThat(markdown).contains("GET https://api.example.com/api/v1/executions/{taskId}");
        assertThat(markdown).contains("X-Skill-Signature");
        assertThat(markdown).contains("至少 1 条视频素材");
        assertThat(markdown).contains("40201");
    }

    @Test
    void openapi_is_valid_json_with_execute_and_polling() {
        String openapi = exporter.export(skill(), InvocationSpecExporter.Format.OPENAPI);

        com.fasterxml.jackson.databind.JsonNode node =
                com.skill.platform.gateway.service.JsonCodec.tree(openapi);
        assertThat(node.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(node.path("paths").has("/api/v1/execute")).isTrue();
        assertThat(node.path("paths").has("/api/v1/executions/{taskId}")).isTrue();
        assertThat(node.path("paths").has("/api/v1/executions/{taskId}/cancel")).isTrue();
        assertThat(node.path("paths").path("/api/v1/execute").path("post")
                .path("requestBody").path("content").path("application/json")
                .path("example").path("skillCode").asText()).isEqualTo("video-generation");
    }

    @Test
    void tool_schema_matches_function_calling_shape() {
        String schema = exporter.export(skill(), InvocationSpecExporter.Format.TOOL_SCHEMA);

        com.fasterxml.jackson.databind.JsonNode node =
                com.skill.platform.gateway.service.JsonCodec.tree(schema);
        assertThat(node.path("name").asText()).isEqualTo("video-generation");
        assertThat(node.path("parameters").path("type").asText()).isEqualTo("object");
        assertThat(node.path("parameters").path("properties").has("materials")).isTrue();
        assertThat(node.path("parameters").path("properties").has("instructions")).isTrue();
        assertThat(node.path("description").asText()).contains("taskId");
    }
}
