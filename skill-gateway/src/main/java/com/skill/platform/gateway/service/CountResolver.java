package com.skill.platform.gateway.service;

import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;

/**
 * 产出数量控制规则（技术方案 v6.5 §4.4）：
 * 数量语义归 Skill 声明（output_config），平台只做边界校验与透传。
 */
public final class CountResolver {

    public record Resolution(int count, boolean overridden) {
    }

    private CountResolver() {
    }

    /**
     * 规则表：
     * <ul>
     *   <li>不传 count → defaultCount（null 按 1）</li>
     *   <li>countable=false 却传了 count → 40006（防静默忽略）</li>
     *   <li>count ≤ 0 → 40006</li>
     *   <li>count &gt; maxCount：未 override → 40006；override=true → 放行并标记（调用方自担成本/时长）</li>
     * </ul>
     *
     * @param maxCount 上限（null 视为不设上限）
     */
    public static Resolution resolve(Integer requested, boolean countable, Integer defaultCount,
                                     Integer maxCount, boolean override) {
        if (requested == null) {
            return new Resolution(defaultCount == null ? 1 : defaultCount, false);
        }
        if (requested <= 0) {
            throw new BizException(ErrorCode.COUNT_INVALID, "count 必须为正整数");
        }
        if (!countable) {
            throw new BizException(ErrorCode.COUNT_INVALID, "该 Skill 不支持数量参数");
        }
        if (maxCount != null && requested > maxCount) {
            if (!override) {
                throw new BizException(ErrorCode.COUNT_INVALID,
                        "count 超出上限 " + maxCount + "，如需放行请显式传 override=true");
            }
            return new Resolution(requested, true);
        }
        return new Resolution(requested, false);
    }
}
