package com.skill.platform.billing.dto;

import java.util.List;

/**
 * 对账报告（每轮执行结果，供监控与测试断言）。
 */
public record ReconcileReport(int scanned, int settled, int released, int cancelled,
                              int orphanReleased, List<String> alerts) {
}
