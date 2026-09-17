package com.skill.platform.gateway.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 执行受理相关 DTO（§5.3）。
 */
public final class ExecuteDtos {

    private ExecuteDtos() {
    }

    public record ExecuteRequest(
            @NotBlank(message = "skillCode 不能为空") String skillCode,
            /** null = 默认版本 */
            String version,
            Integer count,
            Boolean override,
            String clientRequestId,
            @Valid List<MaterialInput> materials,
            String instructions,
            JsonNode context,
            String callbackUrl) {
    }

    /**
     * 素材两层输入（§4.3）：语义类型 video/image/audio/document/data/other 走 OSS（url=oss://...）；
     * text 内联 content；link 为外部 URL 由 Skill 沙箱自行下载。
     */
    public record MaterialInput(String type, String url, String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecuteResult(String taskId, String resolvedVersion, Integer count,
                                String status, boolean idempotentHit) {
    }

    /** 终态/非终态同构视图（§5.4：终态查询响应 = 完整结果体，与回调 body 同构） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecutionView(
            String taskId,
            String skillCode,
            String resolvedVersion,
            String status,
            Integer progress,
            String callbackStatus,
            String callbackUrl,
            Integer expectedCount,
            JsonNode context,
            List<ArtifactView> artifacts,
            BillingBlock billing,
            Integer modelCalls,
            Integer tokensUsed,
            Long durationMs,
            ErrorBlock error,
            String timestamp) {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record ArtifactView(String artifactId, String type, String url, Long sizeBytes) {
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record BillingBlock(String mode, Long pointsCharged, String holdId, Boolean settled) {
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record ErrorBlock(String code, String message) {
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CancelResult(String taskId, String status, boolean cancelled) {
    }
}
