package com.skill.platform.recharge.controller;

import com.skill.platform.recharge.common.ApiResponse;
import com.skill.platform.recharge.service.SkuService;
import com.skill.platform.recharge.service.UserAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户认证与套餐（公开端点，验证码发送有限流）。
 */
@RestController
@RequestMapping("/api/user/auth")
@RequiredArgsConstructor
public class UserAuthController {

    private final UserAuthService userAuthService;
    private final SkuService skuService;

    public record SendCodeRequest(@NotBlank String identifier) {
    }

    public record LoginRequest(@NotBlank String identifier, @NotBlank String code) {
    }

    @PostMapping("/send-code")
    public ApiResponse<Map<String, Object>> sendCode(@Valid @RequestBody SendCodeRequest request) {
        return ApiResponse.ok(userAuthService.sendCode(request.identifier()));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(userAuthService.login(request.identifier(), request.code()));
    }

    /** 套餐列表（登录前可浏览） */
    @GetMapping("/skus")
    public ApiResponse<List<Map<String, Object>>> skus() {
        return ApiResponse.ok(skuService.listActive().stream()
                .map(s -> Map.<String, Object>of(
                        "skuId", s.getSkuId(),
                        "name", s.getName(),
                        "points", s.getPoints(),
                        "priceFen", s.getPriceFen()))
                .toList());
    }
}
