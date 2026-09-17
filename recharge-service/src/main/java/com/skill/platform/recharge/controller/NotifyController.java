package com.skill.platform.recharge.controller;

import com.skill.platform.recharge.channel.PaymentChannel;
import com.skill.platform.recharge.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.TreeMap;

/**
 * 支付渠道异步回调（公网暴露面，仅此端点）：按渠道协议应答（微信 v3 JSON / 支付宝 plain text）。
 */
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotifyController {

    private final NotifyService notifyService;
    private final com.skill.platform.recharge.channel.ChannelRegistry channelRegistry;

    @PostMapping("/{channel}/pay")
    public ResponseEntity<String> notify(@PathVariable String channel,
                                         @RequestBody String rawBody,
                                         @RequestHeader Map<String, String> headers) {
        // Tomcat 将 header 名统一小写、MockMvc 保留原大小写：边界处归一化为大小写不敏感
        Map<String, String> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        normalized.putAll(headers);
        NotifyService.NotifyOutcome outcome = notifyService.handle(channel, rawBody, normalized);
        PaymentChannel paymentChannel = channelRegistry.channel(channel);
        String body = paymentChannel.ackResponse(outcome.success(), outcome.message());
        boolean json = body.trim().startsWith("{");
        return ResponseEntity.status(outcome.success() ? 200 : 500)
                .contentType(json ? MediaType.APPLICATION_JSON : MediaType.TEXT_PLAIN)
                .body(body);
    }
}
