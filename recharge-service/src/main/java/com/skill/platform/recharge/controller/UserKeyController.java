package com.skill.platform.recharge.controller;

import com.skill.platform.recharge.common.ApiResponse;
import com.skill.platform.recharge.security.JwtAuthFilter;
import com.skill.platform.recharge.security.UserContext;
import com.skill.platform.recharge.service.UserKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户 APIKey 管理（§10.2）：脱敏列表 / 新增（一次性展示）/ 重置轮换 / 禁用。
 */
@RestController
@RequestMapping("/api/user/keys")
@RequiredArgsConstructor
public class UserKeyController {

    private final UserKeyService userKeyService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestAttribute(JwtAuthFilter.ATTR_USER) UserContext user) {
        return ApiResponse.ok(userKeyService.list(user));
    }

    /** 新增 key：完整值仅此一次返回 */
    @PostMapping
    public ApiResponse<Map<String, String>> issue(
            @RequestAttribute(JwtAuthFilter.ATTR_USER) UserContext user) {
        return ApiResponse.ok(userKeyService.issue(user));
    }

    /** AppSecret 重置=轮换：旧值立即失效，新值仅此一次返回 */
    @PostMapping("/{appKeyId}/reset-secret")
    public ApiResponse<Map<String, String>> resetSecret(
            @RequestAttribute(JwtAuthFilter.ATTR_USER) UserContext user,
            @PathVariable String appKeyId) {
        return ApiResponse.ok(userKeyService.resetSecret(user, appKeyId));
    }

    @PostMapping("/{appKeyId}/disable")
    public ApiResponse<Map<String, Object>> disable(
            @RequestAttribute(JwtAuthFilter.ATTR_USER) UserContext user,
            @PathVariable String appKeyId) {
        return ApiResponse.ok(userKeyService.disable(user, appKeyId));
    }
}
