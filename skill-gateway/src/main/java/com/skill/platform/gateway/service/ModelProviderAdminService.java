package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.dal.entity.ModelProvider;
import com.skill.platform.gateway.dal.mapper.ModelProviderMapper;
import com.skill.platform.gateway.security.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型供应商配置维护（§10.1 模型与能力）：Skill 只认 alias；
 * API Key AES 加密落库、任何接口不回显明文。
 */
@Service
@RequiredArgsConstructor
public class ModelProviderAdminService {

    private final ModelProviderMapper modelProviderMapper;
    private final CryptoService cryptoService;

    public List<Map<String, Object>> list() {
        return modelProviderMapper.selectList(new LambdaQueryWrapper<ModelProvider>()
                        .orderByDesc(ModelProvider::getId))
                .stream().map(ModelProviderAdminService::toView).toList();
    }

    /** 按 alias upsert：有 apiKey 才更新密钥 */
    public Map<String, Object> upsert(String alias, String provider, String modelName,
                                      String endpoint, String apiKey, String fallbackAlias,
                                      Integer maxQps, String costPer1kInput, String costPer1kOutput,
                                      String costPerCall, Integer status) {
        if (alias == null || alias.isBlank() || provider == null || provider.isBlank()
                || modelName == null || modelName.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "alias / provider / modelName 必填");
        }
        ModelProvider existing = modelProviderMapper.selectOne(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getAlias, alias).last("LIMIT 1"));
        ModelProvider modelConfig = existing == null ? new ModelProvider() : existing;
        modelConfig.setAlias(alias);
        modelConfig.setProvider(provider);
        modelConfig.setModelName(modelName);
        if (endpoint != null) {
            modelConfig.setEndpoint(endpoint);
        }
        if (existing == null && (endpoint == null || endpoint.isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新建时 endpoint 必填");
        }
        if (apiKey != null && !apiKey.isBlank()) {
            modelConfig.setApiKeyCipher(cryptoService.encrypt(apiKey));
        } else if (existing == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新建时 apiKey 必填");
        }
        modelConfig.setFallbackAlias(fallbackAlias);
        modelConfig.setMaxQps(maxQps == null ? 10 : maxQps);
        modelConfig.setCostPer1kInputTokens(costPer1kInput == null ? null : new java.math.BigDecimal(costPer1kInput));
        modelConfig.setCostPer1kOutputTokens(costPer1kOutput == null ? null : new java.math.BigDecimal(costPer1kOutput));
        modelConfig.setCostPerCall(costPerCall == null ? null : new java.math.BigDecimal(costPerCall));
        modelConfig.setStatus(status == null ? 1 : status);
        if (existing == null) {
            modelProviderMapper.insert(modelConfig);
        } else {
            modelProviderMapper.updateById(modelConfig);
        }
        return toView(modelConfig);
    }

    public void delete(String alias) {
        ModelProvider provider = modelProviderMapper.selectOne(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getAlias, alias).last("LIMIT 1"));
        if (provider == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "模型别名不存在: " + alias);
        }
        modelProviderMapper.deleteById(provider.getId());
    }

    public Map<String, Object> updateStatus(String alias, int status) {
        ModelProvider provider = modelProviderMapper.selectOne(new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getAlias, alias).last("LIMIT 1"));
        if (provider == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "模型别名不存在: " + alias);
        }
        provider.setStatus(status);
        modelProviderMapper.updateById(provider);
        return toView(provider);
    }

    private static Map<String, Object> toView(ModelProvider provider) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("alias", provider.getAlias());
        view.put("provider", provider.getProvider());
        view.put("modelName", provider.getModelName());
        view.put("endpoint", provider.getEndpoint());
        view.put("hasApiKey", provider.getApiKeyCipher() != null && !provider.getApiKeyCipher().isBlank());
        view.put("fallbackAlias", provider.getFallbackAlias());
        view.put("maxQps", provider.getMaxQps());
        view.put("costPer1kInputTokens", provider.getCostPer1kInputTokens());
        view.put("costPer1kOutputTokens", provider.getCostPer1kOutputTokens());
        view.put("costPerCall", provider.getCostPerCall());
        view.put("status", provider.getStatus());
        return view;
    }
}
