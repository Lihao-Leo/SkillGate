package com.skill.platform.recharge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.recharge.common.BizException;
import com.skill.platform.recharge.common.ErrorCode;
import com.skill.platform.recharge.common.Ids;
import com.skill.platform.recharge.dal.entity.RechargeOrder;
import com.skill.platform.recharge.dal.entity.UserAppKey;
import com.skill.platform.recharge.dal.mapper.RechargeOrderMapper;
import com.skill.platform.recharge.dal.mapper.UserAppKeyMapper;
import com.skill.platform.recharge.security.CryptoService;
import com.skill.platform.recharge.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 充值订单：创建（先落 CREATED 再外调渠道，渠道成功条件更新 PAYING）与用户侧查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    /** 二维码有效期 15min（§10.4） */
    public static final int QR_TTL_MINUTES = 15;

    private final RechargeOrderMapper orderMapper;
    private final UserAppKeyMapper userAppKeyMapper;
    private final SkuService skuService;
    private final CreditService creditService;
    private final com.skill.platform.recharge.channel.ChannelRegistry channelRegistry;
    private final CryptoService cryptoService;

    public Map<String, Object> create(UserContext user, String skuId, Long customAmountFen,
                                      String channel) {
        SkuService.OrderSpec spec = skuService.resolve(skuId, customAmountFen);
        channelRegistry.channel(channel); // 渠道合法性前置校验

        RechargeOrder order = new RechargeOrder();
        order.setOrderNo(Ids.nextOrderNo());
        order.setUserId(user.userId());
        order.setChannel(channel.toUpperCase());
        order.setSkuPoints(spec.points());
        order.setAmountFen(spec.amountFen());
        order.setStatus(RechargeOrder.STATUS_CREATED);
        order.setExpireAt(LocalDateTime.now().plusMinutes(QR_TTL_MINUTES));
        order.setRetryCount(0);
        orderMapper.insert(order);

        // 先落自身状态（CREATED），再外调渠道；渠道失败留在 CREATED 由过期任务收殓
        try {
            var created = channelRegistry.channel(channel).create(order);
            int updated = orderMapper.markPaying(order.getOrderNo(), created.payUrl(),
                    order.getExpireAt());
            if (updated == 1) {
                order.setCodeUrl(created.payUrl());
                order.setStatus(RechargeOrder.STATUS_PAYING);
            }
        } catch (Exception e) {
            log.error("channel create failed, order stays CREATED: orderNo={}", order.getOrderNo(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "渠道下单失败，请稍后重试");
        }
        return toCreateView(order);
    }

    /** 用户侧订单查询：CREDITED 时附带一次性展示的新签发 key（secret 首次展示后清空） */
    public Map<String, Object> view(UserContext user, String orderNo) {
        RechargeOrder order = orderMapper.selectOne(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getOrderNo, orderNo).last("LIMIT 1"));
        if (order == null || !order.getUserId().equals(user.userId())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "订单不存在: " + orderNo);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("orderNo", order.getOrderNo());
        view.put("status", order.getStatus());
        view.put("channel", order.getChannel());
        view.put("points", order.getSkuPoints());
        view.put("amountFen", order.getAmountFen());
        view.put("codeUrl", order.getCodeUrl());
        view.put("expiresAt", order.getExpireAt() == null ? null : order.getExpireAt().toString());
        view.put("paidAt", order.getPaidAt() == null ? null : order.getPaidAt().toString());
        view.put("creditedAt", order.getCreditedAt() == null ? null : order.getCreditedAt().toString());
        if (RechargeOrder.STATUS_CREDITED.equals(order.getStatus())) {
            view.put("creditedPoints", order.getSkuPoints());
            appendPendingSecret(user.userId(), view);
        }
        return view;
    }

    private void appendPendingSecret(Long userId, Map<String, Object> view) {
        UserAppKey pending = userAppKeyMapper.selectOne(new LambdaQueryWrapper<UserAppKey>()
                .eq(UserAppKey::getUserId, userId)
                .isNotNull(UserAppKey::getSecretPending)
                .last("LIMIT 1"));
        if (pending == null) {
            return;
        }
        try {
            view.put("newlyIssuedKey", Map.of(
                    "appKeyId", pending.getAppKeyId(),
                    "appSecret", cryptoService.decrypt(pending.getSecretPending())));
        } catch (Exception e) {
            log.warn("decrypt pending secret failed: userId={}", userId);
            return;
        }
        // 完整值仅此一次展示：读后即清（§10.3 第 7 条）
        // updateById 默认忽略 null 字段，须显式置空
        userAppKeyMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserAppKey>()
                .eq(UserAppKey::getId, pending.getId())
                .set(UserAppKey::getSecretPending, null));
    }

    private Map<String, Object> toCreateView(RechargeOrder order) {
        return Map.of(
                "orderNo", order.getOrderNo(),
                "status", order.getStatus(),
                "channel", order.getChannel(),
                "points", order.getSkuPoints(),
                "amountFen", order.getAmountFen(),
                "codeUrl", order.getCodeUrl() == null ? "" : order.getCodeUrl(),
                "expiresAt", order.getExpireAt().toString());
    }
}
