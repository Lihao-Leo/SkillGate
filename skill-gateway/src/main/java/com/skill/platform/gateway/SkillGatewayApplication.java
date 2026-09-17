package com.skill.platform.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 接入与管理服务（skill_platform 库）。
 *
 * <p>接入面（§4.2 受理五关）：AppKey HMAC 鉴权 → Redis 限流/配额 → 计费预校验+冻结（billing）
 * → 调度（skill-execute MQ）→ 返回 taskId；结果获取双通道（回调 / 轮询）。
 *
 * <p>管理面：Skill 上传校验与审核、市场与可见性、版本与定价、invocation_spec 导出、
 * 素材上传与 presign、执行查询/取消、AppKey 与账户管理、FREE 埋点。
 */
@EnableScheduling
@SpringBootApplication
@MapperScan("com.skill.platform.gateway.dal.mapper")
public class SkillGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillGatewayApplication.class, args);
    }
}
