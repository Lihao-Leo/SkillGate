package com.skill.platform.recharge.platform;

import java.util.List;

/**
 * 执行平台（gateway 管理面）客户端端口：两者间只有 §5.7 两个 admin API 为主路径
 * （签发 / 入账），key 管理三接口为用户端 BFF 转发。
 */
public interface SkillPlatformClient {

    IssuedKey issueKey(String tenantId);

    /** 点数入账（orderNo 幂等，重复调用返回已入账流水） */
    Recharged recharge(String appKeyId, long points, String orderNo);

    ResetSecret resetSecret(String appKeyId);

    void updateKeyStatus(String appKeyId, int status);

    List<KeyInfo> listKeys(String tenantId);

    record IssuedKey(String appKeyId, String appSecret) {
    }

    record Recharged(String txId, long points, long balance, boolean alreadyCredited) {
    }

    record ResetSecret(String appKeyId, String appSecret) {
    }

    record KeyInfo(String appKeyId, String tenantId, int status, String quota) {
    }
}
