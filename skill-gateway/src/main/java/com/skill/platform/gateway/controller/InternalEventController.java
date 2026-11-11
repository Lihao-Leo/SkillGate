package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.service.AlertRuleService;
import com.skill.platform.gateway.service.ExecutionEventCollectorService;
import com.skill.platform.gateway.service.ModelCallCollectorService;
import com.skill.platform.gateway.service.SysConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内部接口（X-Internal-Token 鉴权）：执行事件上报（§1.4，gateway 单点写库）+
 * 模型调用明细上报（V2.1）+ worker 运行时配置下发（告警配置统一维护于 sys_config，
 * 管理后台系统配置页可改；V2.1 追加告警规则列表）。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalEventController {

    private final ExecutionEventCollectorService eventCollector;
    private final ModelCallCollectorService modelCallCollector;
    private final com.skill.platform.gateway.service.AlertRecordCollectorService alertRecordCollector;
    private final AlertRuleService alertRuleService;
    private final SysConfigService sysConfigService;

    @Value("${INTERNAL_TOKEN:dev-internal-token}")
    private String internalToken;

    /** 上报批量请求体：{"events":[{taskId,tenantId,stage,status,service,durationMs,detail,traceId,occurredAt}]} */
    public record EventsBatchRequest(List<ExecutionEventCollectorService.EventInput> events) {
    }

    /** 模型调用批量请求体：{"calls":[{taskId,tenantId,skillCode,seq,alias,promptTokens,...}]} */
    public record ModelCallsBatchRequest(List<ModelCallCollectorService.ModelCallInput> calls) {
    }

    /** 告警记录批量请求体：{"records":[{ruleCode,ruleType,severity,taskId,title,...}]} */
    public record AlertRecordsBatchRequest(
            List<com.skill.platform.gateway.service.AlertRecordCollectorService.AlertRecordInput> records) {
    }

    /** 运行时告警配置（统一维护入口 = 管理后台系统配置页 sys_config，DB 覆盖默认值）：
     *  worker 每 60s 拉取热生效，无需重启。键：alert.feishu-webhook-url / alert.feishu-secret /
     *  alert.running-seconds / alert.tokens。V2.1 追加 rules 告警规则列表（additive，
     *  旧 worker 忽略；新 worker 规则为空时回退 legacy 阈值）。 */
    @GetMapping("/runtime-config/alerts")
    public Map<String, Object> runtimeAlertConfig(HttpServletRequest request) {
        String token = request.getHeader("X-Internal-Token");
        if (token == null || !token.equals(internalToken)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid internal token");
        }
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("webhookUrl", sysConfigService.get("alert.feishu-webhook-url"));
        config.put("secret", sysConfigService.get("alert.feishu-secret"));
        config.put("runningSeconds",
                sysConfigService.intOf("alert.running-seconds", 600));
        config.put("tokensThreshold",
                sysConfigService.intOf("alert.tokens", 0));
        config.put("rules", alertRuleService.enabledRules());
        return config;
    }

    /**
     * 批量上报执行事件。
     *
     * @return {"accepted": 实际落库条数}
     */
    @PostMapping("/execution-events")
    public Map<String, Object> accept(HttpServletRequest request,
                                      @RequestBody EventsBatchRequest batch) {
        String token = request.getHeader("X-Internal-Token");
        if (token == null || !token.equals(internalToken)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid internal token");
        }
        int accepted = eventCollector.collectBatch(batch.events());
        return Map.of("accepted", accepted);
    }

    /**
     * 批量上报模型调用明细（V2.1：逐次 LLM 调用的 token 消耗；费率折算在 gateway 侧完成）。
     *
     * @return {"accepted": 实际落库条数}
     */
    @PostMapping("/model-calls")
    public Map<String, Object> acceptModelCalls(HttpServletRequest request,
                                                @RequestBody ModelCallsBatchRequest batch) {
        String token = request.getHeader("X-Internal-Token");
        if (token == null || !token.equals(internalToken)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid internal token");
        }
        int accepted = modelCallCollector.collectBatch(batch.calls());
        return Map.of("accepted", accepted);
    }

    /**
     * 批量上报告警记录（V2.1：worker 规则化告警落库；同任务同规则幂等刷 last_seen_at）。
     *
     * @return {"accepted": 新增条数}
     */
    @PostMapping("/alert-records")
    public Map<String, Object> acceptAlertRecords(HttpServletRequest request,
                                                  @RequestBody AlertRecordsBatchRequest batch) {
        String token = request.getHeader("X-Internal-Token");
        if (token == null || !token.equals(internalToken)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid internal token");
        }
        int accepted = alertRecordCollector.collectBatch(batch.records());
        return Map.of("accepted", accepted);
    }
}
