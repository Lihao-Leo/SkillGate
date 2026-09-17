package com.skill.platform.billing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 计费服务（skill_billing 库）。
 *
 * <p>职责（技术方案 v6.5 §3.2/§4.8）：点数账户（key 即账户）、预校验、冻结/结算/退款、
 * 不可变流水、对账兜底（FROZEN 超 24h 未结算自动处理）。
 *
 * <p>交互形态：gateway 同步调用（预校验/冻结），worker 结算主路径为同步 RPC，
 * {@code skill-settle} MQ 为兜底重试通道（按 taskId 幂等）。
 */
@EnableScheduling
@SpringBootApplication
@MapperScan("com.skill.platform.billing.dal.mapper")
public class BillingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingServiceApplication.class, args);
    }
}
