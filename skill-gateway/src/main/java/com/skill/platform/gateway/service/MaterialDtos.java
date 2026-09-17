package com.skill.platform.gateway.service;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 素材相关 DTO（§5.1）。
 */
public final class MaterialDtos {

    private MaterialDtos() {
    }

    public record PresignRequest(String materialType, String filename, long sizeBytes) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PresignResult(String materialId, String uploadUrl, String expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MaterialView(String materialId, String materialType, String materialUrl,
                               String filename, Long sizeBytes, String status) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TelemetryReport(String version, Object input, Object output,
                                  String status, Long durationMs, String callerHint) {
    }
}
