package com.skill.platform.billing.service;

/**
 * 告警出口端口。生产接监控（Prometheus Alertmanager / 企业微信）；V1 默认日志实现。
 */
public interface AlertNotifier {

    /** level: INFO / WARN / HIGH */
    void notify(String level, String title, String detail);
}
