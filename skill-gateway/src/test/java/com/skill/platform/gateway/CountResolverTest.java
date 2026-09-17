package com.skill.platform.gateway;

import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.service.CountResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 产出数量控制规则测试（§4.4 / TC-EXE-004、TC-EXE-005）。
 */
class CountResolverTest {

    @Test
    void absent_count_uses_default() {
        assertThat(CountResolver.resolve(null, true, 3, 20, false))
                .isEqualTo(new CountResolver.Resolution(3, false));
        assertThat(CountResolver.resolve(null, false, null, null, false))
                .isEqualTo(new CountResolver.Resolution(1, false));
    }

    @Test
    void count_within_max_passes() {
        assertThat(CountResolver.resolve(10, true, 1, 20, false))
                .isEqualTo(new CountResolver.Resolution(10, false));
    }

    @Test
    void count_over_max_without_override_rejected() {
        assertThatThrownBy(() -> CountResolver.resolve(21, true, 1, 20, false))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.COUNT_INVALID));
    }

    @Test
    void count_over_max_with_override_marked() {
        assertThat(CountResolver.resolve(30, true, 1, 20, true))
                .isEqualTo(new CountResolver.Resolution(30, true));
    }

    @Test
    void non_countable_skill_rejects_count() {
        assertThatThrownBy(() -> CountResolver.resolve(2, false, 1, 20, false))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.COUNT_INVALID);
                    assertThat(e.getMessage()).contains("不支持数量参数");
                });
    }

    @Test
    void non_positive_count_rejected() {
        assertThatThrownBy(() -> CountResolver.resolve(0, true, 1, 20, false))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> CountResolver.resolve(-5, true, 1, 20, false))
                .isInstanceOf(BizException.class);
    }

    @Test
    void no_max_count_declared_accepts_any() {
        assertThat(CountResolver.resolve(999, true, 1, null, false))
                .isEqualTo(new CountResolver.Resolution(999, false));
    }
}
