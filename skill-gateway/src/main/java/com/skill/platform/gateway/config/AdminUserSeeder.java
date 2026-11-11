package com.skill.platform.gateway.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.dal.entity.AdminUser;
import com.skill.platform.gateway.dal.mapper.AdminUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 超管账号种子器：admin_user 空表时创建 admin 账号（SUPER_ADMIN）。
 *
 * <p>密码哈希不在 SQL 里种子（BCrypt 不宜硬编码），启动期生成；密码经
 * {@code skill-platform.admin.init-password} 注入（默认本地开发值，生产必须换）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserSeeder implements ApplicationRunner {

    private final AdminUserMapper adminUserMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${skill-platform.admin.init-password}")
    private String initPassword;

    @Override
    public void run(ApplicationArguments args) {
        Long count = adminUserMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        AdminUser admin = new AdminUser();
        admin.setUsername("admin");
        admin.setPasswordHash(passwordEncoder.encode(initPassword));
        admin.setDisplayName("超级管理员");
        admin.setRoleCode("SUPER_ADMIN");
        admin.setStatus(1);
        adminUserMapper.insert(admin);
        log.warn("已种子超管账号 admin（初始密码来自 skill-platform.admin.init-password）；"
                + "生产环境请立即登录修改密码");
    }
}
