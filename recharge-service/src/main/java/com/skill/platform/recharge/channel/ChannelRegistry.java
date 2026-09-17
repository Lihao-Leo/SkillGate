package com.skill.platform.recharge.channel;

import com.skill.platform.recharge.common.BizException;
import com.skill.platform.recharge.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 渠道注册表：按订单/请求的 channel 标识取适配器（新渠道加 Bean 即生效）。
 */
@Component
public class ChannelRegistry {

    private final Map<String, PaymentChannel> channels;

    public ChannelRegistry(List<PaymentChannel> channelBeans) {
        this.channels = channelBeans.stream()
                .collect(Collectors.toMap(PaymentChannel::channel, Function.identity()));
    }

    public PaymentChannel channel(String name) {
        PaymentChannel channel = name == null ? null : channels.get(name.toUpperCase());
        if (channel == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "支付渠道不支持: " + name
                    + "（可用: " + channels.keySet() + "）");
        }
        return channel;
    }

    public Map<String, PaymentChannel> all() {
        return channels;
    }
}
