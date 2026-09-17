package com.skill.platform.recharge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.recharge.common.BizException;
import com.skill.platform.recharge.common.ErrorCode;
import com.skill.platform.recharge.dal.entity.Sku;
import com.skill.platform.recharge.dal.mapper.SkuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 套餐：阶梯赠送 SKU + 自定义金额（1 分 = 1 点，无赠送）。
 */
@Service
@RequiredArgsConstructor
public class SkuService {

    private final SkuMapper skuMapper;

    @Value("${recharge.custom-amount.min-fen:100}")
    private long customMinFen;
    @Value("${recharge.custom-amount.max-fen:1000000}")
    private long customMaxFen;

    public List<Sku> listActive() {
        return skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getActive, 1)
                .orderByAsc(Sku::getSort));
    }

    /** 下单规格：套餐优先；自定义金额按 1 分 = 1 点折算（无赠送） */
    public OrderSpec resolve(String skuId, Long customAmountFen) {
        if (skuId != null && !skuId.isBlank()) {
            Sku sku = skuMapper.selectOne(new LambdaQueryWrapper<Sku>()
                    .eq(Sku::getSkuId, skuId).eq(Sku::getActive, 1).last("LIMIT 1"));
            if (sku == null) {
                throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "套餐不存在或已下架: " + skuId);
            }
            return new OrderSpec(sku.getPoints(), sku.getPriceFen(), sku.getName());
        }
        if (customAmountFen == null || customAmountFen < customMinFen || customAmountFen > customMaxFen) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "自定义金额须在 " + (customMinFen / 100) + "~" + (customMaxFen / 100) + " 元之间");
        }
        return new OrderSpec(customAmountFen.intValue(), customAmountFen, "自定义充值");
    }

    public record OrderSpec(int points, long amountFen, String name) {
    }
}
