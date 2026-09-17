package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.service.MaterialDtos.TelemetryReport;
import com.skill.platform.gateway.service.TelemetryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FREE 公开分发 Skill 埋点上报（§5.7）：X-Skill-Token（skill 级令牌）鉴权 → skill_io_log。
 */
@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetryService;

    @PostMapping(value = "/skill-io", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> report(
            @RequestHeader("X-Skill-Token") String telemetryToken,
            @Valid @RequestBody TelemetryReport report) {
        telemetryService.report(telemetryToken, report);
        return ApiResponse.ok();
    }
}
