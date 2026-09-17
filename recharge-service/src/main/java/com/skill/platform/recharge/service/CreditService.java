package com.skill.platform.recharge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.recharge.channel.ChannelRegistry;
import com.skill.platform.recharge.dal.entity.RechargeOrder;
import com.skill.platform.recharge.dal.entity.UserAppKey;
import com.skill.platform.recharge.dal.mapper.RechargeOrderMapper;
import com.skill.platform.recharge.dal.mapper.UserAppKeyMapper;
import com.skill.platform.recharge.platform.SkillPlatformClient;
import com.skill.platform.recharge.security.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * PAID → CREDITED 入账（§10.4）：先落自身状态（PAID）再外调执行平台；
 * 外调失败由重试任务按 1/5/15min 退避推进，超 1h 告警人工。
 *
 * <p>首充签发：用户无主 key 时先调平台签发（AppKey+Secret，secret 加密暂存待一次性展示），
 * 再以 orderNo 幂等入账；后续充值默认入主 key 账户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditService {

    /** 退避序列（分钟）：1 / 5 / 15 封顶 */
    private static final int[] BACKOFF_MINUTES = {1, 5, 15};

    private final RechargeOrderMapper orderMapper;
    private final UserAppKeyMapper userAppKeyMapper;
    private final SkillPlatformClient platformClient;
    private final ChannelRegistry channelRegistry;
    private final CryptoService cryptoService;

    /**
     * 入账（幂等：非 PAID 直接返回；平台侧 orderNo 幂等消化重试重放）。
     *
     * @return true=本次推进到 CREDITED；false=无需处理（已终态）
     */
    public boolean credit(String orderNo) {
        RechargeOrder order = orderMapper.selectOne(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getOrderNo, orderNo).last("LIMIT 1"));
        if (order == null || !RechargeOrder.STATUS_PAID.equals(order.getStatus())) {
            return false; // 幂等：已 CREDITED / 未支付 / 不存在
        }
        try {
            String appKeyId = resolveOrCreateKey(order);
            platformClient.recharge(appKeyId, order.getSkuPoints(), order.getOrderNo());
            if (orderMapper.markCredited(order.getOrderNo()) == 1) {
                log.info("order credited: orderNo={}, points={}, appKey={}",
                        orderNo, order.getSkuPoints(), appKeyId);
                return true;
            }
            return false; // 并发推进，另一路已 CREDITED
        } catch (Exception e) {
            scheduleRetry(order, e);
            return false;
        }
    }

    /** 用户主 key（后续充值默认入账账户）；无则签发并暂存一次性 secret */
    private String resolveOrCreateKey(RechargeOrder order) {
        UserAppKey primary = userAppKeyMapper.selectOne(new LambdaQueryWrapper<UserAppKey>()
                .eq(UserAppKey::getUserId, order.getUserId())
                .eq(UserAppKey::getIsPrimary, 1)
                .last("LIMIT 1"));
        if (primary != null) {
            return primary.getAppKeyId();
        }
        SkillPlatformClient.IssuedKey issued = platformClient.issueKey(
                "recharge-user-" + order.getUserId());
        UserAppKey mapping = new UserAppKey();
        mapping.setUserId(order.getUserId());
        mapping.setAppKeyId(issued.appKeyId());
        mapping.setIsPrimary(1);
        // 一次性展示窗口：AES 暂存，用户首次查询 CREDITED 订单时返回并清空
        mapping.setSecretPending(cryptoService.encrypt(issued.appSecret()));
        try {
            userAppKeyMapper.insert(mapping);
        } catch (DuplicateKeyException e) {
            // 并发首充（同用户两单同时签发）：保留先入库者为主 key，本单入其账户
            UserAppKey winner = userAppKeyMapper.selectOne(new LambdaQueryWrapper<UserAppKey>()
                    .eq(UserAppKey::getUserId, order.getUserId())
                    .eq(UserAppKey::getIsPrimary, 1).last("LIMIT 1"));
            log.info("concurrent first-purchase issue, reuse winner key: user={}, key={}",
                    order.getUserId(), winner.getAppKeyId());
            return winner.getAppKeyId();
        }
        log.info("first purchase key issued: user={}, appKey={}", order.getUserId(), issued.appKeyId());
        return issued.appKeyId();
    }

    /** 外调失败：留在 PAID + 退避计划（1/5/15min 封顶） */
    private void scheduleRetry(RechargeOrder order, Exception cause) {
        int retryCount = (order.getRetryCount() == null ? 0 : order.getRetryCount()) + 1;
        int backoff = BACKOFF_MINUTES[Math.min(retryCount - 1, BACKOFF_MINUTES.length - 1)];
        String error = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        orderMapper.recordCreditFailure(order.getOrderNo(), retryCount,
                LocalDateTime.now().plusMinutes(backoff), abbreviate(error));
        log.warn("credit failed, retry scheduled: orderNo={}, retryCount={}, nextIn={}min",
                order.getOrderNo(), retryCount, backoff);
    }

    private static String abbreviate(String message) {
        return message.length() <= 250 ? message : message.substring(0, 250);
    }

    ChannelRegistry channelRegistry() {
        return channelRegistry;
    }
}
