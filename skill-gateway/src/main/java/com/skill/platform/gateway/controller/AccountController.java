package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.billing.BillingViews.BalanceView;
import com.skill.platform.gateway.billing.BillingViews.TransactionPage;
import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.security.AppKeyAuthInterceptor;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费与账户接口（§5.7 调用方侧）。
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping("/balance")
    public ApiResponse<BalanceView> balance(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller) {
        return ApiResponse.ok(accountService.balance(caller));
    }

    @GetMapping("/transactions")
    public ApiResponse<TransactionPage> transactions(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @RequestParam(required = false) String taskId,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(accountService.transactions(caller, taskId, pageNo, pageSize));
    }
}
