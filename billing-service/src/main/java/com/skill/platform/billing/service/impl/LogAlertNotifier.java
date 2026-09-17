package com.skill.platform.billing.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.skill.platform.billing.service.AlertNotifier;

/**
 * 日志告警实现（V1 默认）：高级别以 error 输出，便于日志告警规则采集。
 */
@Slf4j
@Component
public class LogAlertNotifier implements AlertNotifier {

    @Override
    public void notify(String level, String title, String detail) {
        switch (level) {
            case "HIGH" -> log.error("[ALERT][HIGH] {} | {}", title, detail);
            case "WARN" -> log.warn("[ALERT][WARN] {} | {}", title, detail);
            default -> log.info("[ALERT][INFO] {} | {}", title, detail);
        }
    }
}
