package com.skill.platform.recharge.channel;

import com.skill.platform.recharge.dal.entity.RechargeOrder;

import java.util.Map;

/**
 * 支付渠道抽象（§10.4）：新渠道只加适配器。
 * 商户号 / 证书 / 平台私钥经配置注入并 AES 加密存储，永不落日志。
 */
public interface PaymentChannel {

    /** 渠道标识：WECHAT / ALIPAY / MOCK */
    String channel();

    /** 统一下单：微信 Native 返回 code_url；支付宝 precreate 返回 qr_code */
    ChannelCreateResult create(RechargeOrder order) throws Exception;

    /**
     * 回调验签 + 解密：微信 APIv3 平台证书验签 + AES-256-GCM 解密；支付宝 RSA2 公钥验签。
     * 验签失败 / 报文异常返回 invalid=true（调用方留档告警，应答失败）。
     */
    NotifyVerification verifyNotify(String rawBody, Map<String, String> headers);

    /** 主动查单（过期判定 + 对账兜底） */
    TradeStatus query(RechargeOrder order) throws Exception;

    /** 关单（best-effort，过期未付时止损） */
    boolean closeOrder(RechargeOrder order) throws Exception;

    /** 渠道要求的回调应答体（微信 v3 JSON / 支付宝 plain text） */
    String ackResponse(boolean success, String message);

    record ChannelCreateResult(String payUrl, String channelTradeNo) {
    }

    record NotifyVerification(boolean invalid, String message, String outTradeNo,
                              String tradeNo, Long paidAmountFen) {

        public static NotifyVerification invalid(String message) {
            return new NotifyVerification(true, message, null, null, null);
        }
    }

    enum TradeStatus {
        PAID, UNPAID, UNKNOWN
    }
}
