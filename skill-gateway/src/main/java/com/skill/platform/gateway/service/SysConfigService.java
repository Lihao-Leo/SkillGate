package com.skill.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.gateway.dal.entity.SysConfig;
import com.skill.platform.gateway.dal.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统配置（§10.1）：DB 覆盖 yml 默认值；短缓存（30s）避免每次请求查库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigMapper sysConfigMapper;

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile long cachedAt = 0;
    private static final long CACHE_TTL_MS = 30_000;

    public String get(String key) {
        if (System.currentTimeMillis() - cachedAt > CACHE_TTL_MS) {
            refresh();
        }
        return cache.get(key);
    }

    public int intOf(String key, int fallback) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("sys_config {} 非整数: {}", key, value);
            return fallback;
        }
    }

    public Map<String, String> all() {
        refresh();
        return new LinkedHashMap<>(cache);
    }

    public void put(String key, String value, String remark) {
        SysConfig existing = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key).last("LIMIT 1"));
        if (existing == null) {
            SysConfig config = new SysConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setRemark(remark);
            config.setUpdatedAt(LocalDateTime.now());
            sysConfigMapper.insert(config);
        } else {
            existing.setConfigValue(value);
            if (remark != null) {
                existing.setRemark(remark);
            }
            existing.setUpdatedAt(LocalDateTime.now());
            sysConfigMapper.updateById(existing);
        }
        cache.put(key, value);
    }

    private void refresh() {
        synchronized (cache) {
            if (System.currentTimeMillis() - cachedAt <= CACHE_TTL_MS) {
                return;
            }
            for (SysConfig config : sysConfigMapper.selectList(null)) {
                cache.put(config.getConfigKey(), config.getConfigValue());
            }
            cachedAt = System.currentTimeMillis();
        }
    }
}
