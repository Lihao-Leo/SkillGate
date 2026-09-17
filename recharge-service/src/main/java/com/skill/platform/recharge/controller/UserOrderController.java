package com.skill.platform.recharge.controller;

import com.skill.platform.recharge.common.ApiResponse;
import com.skill.platform.recharge.security.JwtAuthFilter;
import com.skill.platform.recharge.security.UserContext;
import com.skill.platform.recharge.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 充值下单与订单查询（用户会话）。
 */
@RestController
@RequestMapping("/api/user/orders")
@RequiredArgsConstructor
public class UserOrderController {

    private final OrderService orderService;

    public record CreateOrderRequest(@NotBlank String channel, String skuId, Long customAmountFen) {
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestAttribute(JwtAuthFilter.ATTR_USER) UserContext user,
            @Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(orderService.create(user, request.skuId(),
                request.customAmountFen(), request.channel()));
    }

    /** 轮询订单状态：CREDITED 时附带余额到账与一次性 key 展示 */
    @GetMapping("/{orderNo}")
    public ApiResponse<Map<String, Object>> view(
            @RequestAttribute(JwtAuthFilter.ATTR_USER) UserContext user,
            @PathVariable String orderNo) {
        return ApiResponse.ok(orderService.view(user, orderNo));
    }
}
