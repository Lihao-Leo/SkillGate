package com.skill.platform.gateway.service;

import com.skill.platform.gateway.common.BizException;
import com.skill.platform.gateway.common.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * callbackUrl 安全校验（§5.5）：域名白名单 + 保留地址段（SSRF 防护）。
 *
 * <p>白名单非空时 host 必须命中（支持 {@code *.example.com} 通配前缀）；
 * 无论是否配置白名单，host 解析结果命中保留地址（回环/内网/链路本地/组播/未指定）一律拒绝。
 */
@Component
public class CallbackUrlValidator {

    private final DnsResolver dnsResolver;
    private final boolean allowReserved;

    @org.springframework.beans.factory.annotation.Autowired
    public CallbackUrlValidator(
            ObjectProvider<DnsResolver> dnsResolver,
            @Value("${skill-platform.callback.allow-reserved:false}") boolean allowReserved) {
        this.dnsResolver = dnsResolver.getIfAvailable(() -> host -> List.of(InetAddress.getAllByName(host)));
        this.allowReserved = allowReserved;
    }

    /** 测试/独立使用：直接指定解析器 */
    public CallbackUrlValidator(DnsResolver dnsResolver, boolean allowReserved) {
        this.dnsResolver = dnsResolver;
        this.allowReserved = allowReserved;
    }

    /**
     * @param callbackDomains 白名单（空 = 仅保留地址段校验）
     */
    public void validate(String callbackUrl, List<String> callbackDomains) {
        URI uri;
        try {
            uri = URI.create(callbackUrl);
        } catch (Exception e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "callbackUrl 格式非法");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "callbackUrl 仅支持 http/https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "callbackUrl 缺少 host");
        }
        checkWhitelist(host, callbackDomains);
        checkReservedAddress(host);
    }

    private void checkWhitelist(String host, List<String> callbackDomains) {
        if (callbackDomains == null || callbackDomains.isEmpty()) {
            return;
        }
        boolean matched = callbackDomains.stream().anyMatch(domain ->
                domain.equalsIgnoreCase(host)
                        || (domain.startsWith("*.") && host.toLowerCase().endsWith(domain.substring(1))));
        if (!matched) {
            throw new BizException(ErrorCode.FORBIDDEN, "callbackUrl 域名不在白名单: " + host);
        }
    }

    private void checkReservedAddress(String host) {
        if (allowReserved) {
            return;
        }
        if ("localhost".equalsIgnoreCase(host) || host.endsWith(".localhost")
                || host.endsWith(".internal") || host.endsWith(".local")) {
            throw new BizException(ErrorCode.FORBIDDEN, "callbackUrl 指向保留域名: " + host);
        }
        List<InetAddress> addresses;
        try {
            addresses = dnsResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "callbackUrl 域名无法解析: " + host);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isMulticastAddress() || address.isAnyLocalAddress()) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "callbackUrl 指向保留地址段: " + host + " -> " + address.getHostAddress());
            }
        }
    }
}
