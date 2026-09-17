package com.skill.platform.gateway.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AppKey（技术方案 v6.5 §6.7）：充值平台支付成功后签发；V1 key 即账户主体。
 */
@Data
@TableName("app_key")
public class AppKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外 AppKey（sk- 前缀），即调用方 apikey */
    private String appKeyId;

    private String tenantId;

    /** AppSecret（AES 加密存储；请求与回调 HMAC 签名共用） */
    private String secretCipher;

    /** 限流配额 JSON：{"qps":10,"maxRunning":50,"dailyLimit":10000}；空用全局默认 */
    private String quota;

    /** 回调域名白名单 JSON 数组；空=仅做保留地址段校验 */
    private String callbackDomains;

    /** 1=启用 0=禁用 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
