package com.skill.platform.recharge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.recharge.common.BizException;
import com.skill.platform.recharge.common.ErrorCode;
import com.skill.platform.recharge.dal.entity.UserAppKey;
import com.skill.platform.recharge.dal.mapper.UserAppKeyMapper;
import com.skill.platform.recharge.platform.SkillPlatformClient;
import com.skill.platform.recharge.security.CryptoService;
import com.skill.platform.recharge.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户 APIKey 管理 BFF（§10.2）：key 脱敏列表（完整值仅签发/重置时一次性展示）、
 * 新增 key、AppSecret 重置=轮换、手动禁用——均转发 gateway 管理面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserKeyService {

    private final UserAppKeyMapper userAppKeyMapper;
    private final SkillPlatformClient platformClient;
    private final CryptoService cryptoService;

    /** key 脱敏列表：sk-****xxxx（完整值永不在列表出现） */
    public List<Map<String, Object>> list(UserContext user) {
        return userAppKeyMapper.selectList(new LambdaQueryWrapper<UserAppKey>()
                        .eq(UserAppKey::getUserId, user.userId())
                        .orderByDesc(UserAppKey::getIsPrimary)
                        .orderByDesc(UserAppKey::getId))
                .stream()
                .map(k -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("appKeyId", mask(k.getAppKeyId()));
                    item.put("isPrimary", k.getIsPrimary() == 1);
                    item.put("hasPendingSecret", k.getSecretPending() != null);
                    item.put("createdAt", k.getCreatedAt() == null ? null : k.getCreatedAt().toString());
                    return item;
                })
                .toList();
    }

    /** 新增 key（user 与 AppKey 一对多）；完整值一次性返回 */
    public Map<String, String> issue(UserContext user) {
        SkillPlatformClient.IssuedKey issued = platformClient.issueKey(
                "recharge-user-" + user.userId());
        UserAppKey mapping = new UserAppKey();
        mapping.setUserId(user.userId());
        mapping.setAppKeyId(issued.appKeyId());
        mapping.setIsPrimary(countOf(user.userId()) == 0 ? 1 : 0);
        mapping.setSecretPending(cryptoService.encrypt(issued.appSecret()));
        userAppKeyMapper.insert(mapping);
        log.info("user key issued: user={}, appKey={}", user.userId(), issued.appKeyId());
        return Map.of("appKeyId", issued.appKeyId(), "appSecret", issued.appSecret());
    }

    /** AppSecret 重置=轮换（旧值立即失效）；完整值一次性返回 */
    public Map<String, String> resetSecret(UserContext user, String appKeyId) {
        UserAppKey mapping = ownedKey(user, appKeyId);
        SkillPlatformClient.ResetSecret reset = platformClient.resetSecret(appKeyId);
        mapping.setSecretPending(cryptoService.encrypt(reset.appSecret()));
        userAppKeyMapper.updateById(mapping);
        return Map.of("appKeyId", reset.appKeyId(), "appSecret", reset.appSecret());
    }

    public Map<String, Object> disable(UserContext user, String appKeyId) {
        UserAppKey mapping = ownedKey(user, appKeyId);
        platformClient.updateKeyStatus(appKeyId, 0);
        return Map.of("appKeyId", mapping.getAppKeyId(), "status", 0);
    }

    private UserAppKey ownedKey(UserContext user, String appKeyId) {
        UserAppKey mapping = userAppKeyMapper.selectOne(new LambdaQueryWrapper<UserAppKey>()
                .eq(UserAppKey::getUserId, user.userId())
                .eq(UserAppKey::getAppKeyId, appKeyId)
                .last("LIMIT 1"));
        if (mapping == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "key 不存在或不属于当前用户");
        }
        return mapping;
    }

    private long countOf(Long userId) {
        return userAppKeyMapper.selectCount(new LambdaQueryWrapper<UserAppKey>()
                .eq(UserAppKey::getUserId, userId));
    }

    static String mask(String appKeyId) {
        if (appKeyId == null || appKeyId.length() <= 6) {
            return "sk-****";
        }
        return appKeyId.substring(0, 5) + "****" + appKeyId.substring(appKeyId.length() - 4);
    }
}
