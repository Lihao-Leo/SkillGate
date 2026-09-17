package com.skill.platform.gateway;

import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import com.skill.platform.gateway.service.CallbackUrlValidator;
import com.skill.platform.gateway.service.DnsResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * callbackUrl 校验测试：白名单、保留域名、保留地址段（SSRF 防护）。
 * DNS 经伪造解析器注入，无网络依赖。
 */
class CallbackUrlValidatorTest {

    private static InetAddress addr(String host) throws UnknownHostException {
        return InetAddress.getByName(host);
    }

    private static CallbackUrlValidator validator(DnsResolver resolver) {
        return new CallbackUrlValidator(resolver, false);
    }

    @Test
    void public_host_without_whitelist_passes() throws Exception {
        CallbackUrlValidator validator = validator(host -> List.of(addr("93.184.216.34")));
        assertThatCode(() -> validator.validate("https://callback.example.com/api/cb", List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void whitelist_must_match() throws Exception {
        CallbackUrlValidator validator = validator(host -> List.of(addr("93.184.216.34")));
        assertThatCode(() -> validator.validate("https://allowed.example.com/cb",
                List.of("allowed.example.com"))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("https://sub.allowed.example.com/cb",
                List.of("*.allowed.example.com"))).doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate("https://evil.com/cb", List.of("allowed.example.com")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void reserved_domain_rejected() {
        CallbackUrlValidator validator = validator(host -> {
            throw new UnknownHostException(host);
        });
        for (String url : new String[]{
                "http://localhost/cb", "https://svc.internal/cb", "https://printer.local/cb"}) {
            assertThatThrownBy(() -> validator.validate(url, List.of()))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }

    @Test
    void reserved_address_rejected_even_with_whitelist() throws Exception {
        // 白名单命中但解析到内网地址 → 仍拒绝（SSRF）
        CallbackUrlValidator validator = validator(host -> List.of(addr("10.1.2.3")));
        assertThatThrownBy(() -> validator.validate("https://allowed.example.com/cb",
                List.of("allowed.example.com")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        CallbackUrlValidator loopbackResolver = validator(host -> List.of(addr("127.0.0.1")));
        assertThatThrownBy(() -> loopbackResolver.validate("https://allowed.example.com/cb", List.of()))
                .isInstanceOf(BizException.class);

        CallbackUrlValidator linkLocal = validator(host -> List.of(addr("169.254.169.254")));
        assertThatThrownBy(() -> linkLocal.validate("https://allowed.example.com/cb", List.of()))
                .isInstanceOf(BizException.class);
    }

    @Test
    void unresolvable_host_rejected_as_param_error() {
        CallbackUrlValidator validator = validator(host -> {
            throw new UnknownHostException(host);
        });
        assertThatThrownBy(() -> validator.validate("https://nonexistent.invalid/cb", List.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    void non_http_scheme_rejected() {
        CallbackUrlValidator validator = validator(host -> List.of());
        assertThatThrownBy(() -> validator.validate("ftp://example.com/cb", List.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }
}
