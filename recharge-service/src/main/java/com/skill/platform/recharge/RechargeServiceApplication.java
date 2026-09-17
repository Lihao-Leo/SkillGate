package com.skill.platform.recharge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 充值平台 recharge-service（技术方案 v6.9 §10.4）：独立服务、独立 recharge 库。
 *
 * <p>职责边界：recharge-service 碰渠道和用户（订单 / 支付渠道 / C 端用户体系），
 * billing 只碰账户和流水，两者间只有 §5.7 两个 admin API——orderNo 幂等入账即防腐层。
 *
 * <p>核心原则与执行平台一脉相承：状态机条件更新做幂等、先落自身状态再外调、
 * 外调失败由重试任务推进、对账兜底先查成因再动钱。
 */
@EnableScheduling
@SpringBootApplication
@MapperScan("com.skill.platform.recharge.dal.mapper")
public class RechargeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RechargeServiceApplication.class, args);
    }
}
