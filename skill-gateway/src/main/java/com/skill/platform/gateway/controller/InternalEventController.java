package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.service.ExecutionEventCollectorService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内部事件上报端点（技术方案 v2.0 §1.4）：worker / billing 产生的执行事件经此批量落
 * execution_event（gateway 单点写库）。X-Internal-Token 鉴权；单批 ≤100、缺参整批 40001；
 * DB 故障由 collector 静默降级（返回 accepted=0），不阻断上报方主链路。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalEventController {

    private final ExecutionEventCollectorService eventCollector;

    @Value("${INTERNAL_TOKEN:dev-internal-token}")
    private String internalToken;

    /** 上报批量请求体：{"events":[{taskId,tenantId,stage,status,service,durationMs,detail,traceId,occurredAt}]} */
    public record EventsBatchRequest(List<ExecutionEventCollectorService.EventInput> events) {
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
}
