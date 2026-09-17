package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.skill.platform.gateway.billing.BillingClient;
import com.skill.platform.gateway.billing.BillingViews.RechargeResult;
import com.skill.platform.gateway.common.Ids;
import com.skill.platform.gateway.dal.entity.AppKey;
import com.skill.platform.gateway.dal.mapper.AppKeyMapper;
import com.skill.platform.gateway.security.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AppKey 签发与管理（§4.8/§5.7/§10.2）：签发（secret 明文一次性返回 + billing 开户）、
 * 列表、AppSecret 重置=轮换（旧值立即失效，请求与回调签名同步更新）、禁用/启用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppKeyAdminService {

    private final AppKeyMapper appKeyMapper;
    private final BillingClient billingClient;
    private final CryptoService cryptoService;

    public Map<String, Object> issue(String tenantId, String quota, String callbackDomains) {
        for (String json : new String[]{quota, callbackDomains}) {
            if (json != null && !json.isBlank() && !JsonCodec.isValidJson(json)) {
                throw new IllegalArgumentException("quota/callbackDomains 必须为合法 JSON");
            }
        }
        String appKeyId = Ids.newAppKey();
        String secret = Ids.newAppSecret();

        AppKey appKey = new AppKey();
        appKey.setAppKeyId(appKeyId);
        appKey.setTenantId(tenantId);
        appKey.setSecretCipher(cryptoService.encrypt(secret));
        appKey.setQuota(quota);
        appKey.setCallbackDomains(callbackDomains);
        appKey.setStatus(1);
        appKeyMapper.insert(appKey);

        billingClient.ensureAccount(appKeyId, tenantId);
        log.info("app key issued: tenant={}, appKey={}", tenantId, appKeyId);
        // secret 仅签发时返回一次
        return Map.of(
                "appKeyId", appKeyId,
                "appSecret", secret,
                "tenantId", tenantId);
    }

    /** key 列表（运营后管 / 充值用户端 BFF 数据源；完整值不出库，仅标识与状态） */
    public Map<String, Object> listKeys(String tenantId, int pageNo, int pageSize) {
        Page<AppKey> page = appKeyMapper.selectPage(
                new Page<>(Math.max(pageNo, 1), Math.min(Math.max(pageSize, 1), 100)),
                new LambdaQueryWrapper<AppKey>()
                        .eq(tenantId != null && !tenantId.isBlank(), AppKey::getTenantId, tenantId)
                        .orderByDesc(AppKey::getId));
        List<Map<String, Object>> items = page.getRecords().stream()
                .map(k -> Map.<String, Object>of(
                        "appKeyId", k.getAppKeyId(),
                        "tenantId", k.getTenantId(),
                        "quota", k.getQuota() == null ? Map.of() : JsonCodec.treeOrEmpty(k.getQuota()),
                        "callbackDomains", k.getCallbackDomains() == null ? List.of()
                                : JsonCodec.treeOrEmpty(k.getCallbackDomains()),
                        "status", k.getStatus(),
                        "createdAt", String.valueOf(k.getCreatedAt())))
                .toList();
        return Map.of(
                "total", page.getTotal(),
                "pageNo", page.getCurrent(),
                "pageSize", page.getSize(),
                "items", items);
    }

    /**
     * AppSecret 重置=轮换：旧值立即失效（鉴权实时读库），新值仅此一次返回。
     */
    public Map<String, String> resetSecret(String appKeyId) {
        AppKey appKey = loadKey(appKeyId);
        String secret = Ids.newAppSecret();
        appKey.setSecretCipher(cryptoService.encrypt(secret));
        appKey.setUpdatedAt(LocalDateTime.now());
        appKeyMapper.updateById(appKey);
        log.info("app key secret rotated: appKey={}", appKeyId);
        return Map.of("appKeyId", appKeyId, "appSecret", secret);
    }

    /** 禁用（0）/ 启用（1）：禁用后 execute 立即 40101 */
    public Map<String, Object> updateStatus(String appKeyId, int status) {
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("status 仅支持 0=禁用 1=启用");
        }
        AppKey appKey = loadKey(appKeyId);
        appKey.setStatus(status);
        appKey.setUpdatedAt(LocalDateTime.now());
        appKeyMapper.updateById(appKey);
        log.info("app key status updated: appKey={}, status={}", appKeyId, status);
        return Map.of("appKeyId", appKeyId, "status", status);
    }

    public RechargeResult recharge(String appKeyId, long points, String orderNo) {
        AppKey appKey = loadKey(appKeyId);
        return billingClient.recharge(appKeyId, appKey.getTenantId(), points, orderNo);
    }

    private AppKey loadKey(String appKeyId) {
        AppKey appKey = appKeyMapper.selectOne(new LambdaQueryWrapper<AppKey>()
                .eq(AppKey::getAppKeyId, appKeyId)
                .last("LIMIT 1"));
        if (appKey == null) {
            throw new IllegalArgumentException("appKey 不存在: " + appKeyId);
        }
        return appKey;
    }
}
