package com.skill.platform.billing.controller;

import com.skill.platform.billing.common.ApiResponse;
import com.skill.platform.billing.common.ErrorCode;
import com.skill.platform.billing.dto.AccountEnsureRequest;
import com.skill.platform.billing.dto.AdjustRequest;
import com.skill.platform.billing.dto.BalanceView;
import com.skill.platform.billing.dto.FreezeRequest;
import com.skill.platform.billing.dto.FreezeResponse;
import com.skill.platform.billing.dto.HoldView;
import com.skill.platform.billing.dto.PageView;
import com.skill.platform.billing.dto.RechargeRequest;
import com.skill.platform.billing.dto.RechargeResult;
import com.skill.platform.billing.dto.ReleaseRequest;
import com.skill.platform.billing.dto.ReleaseResponse;
import com.skill.platform.billing.dto.SettleRequest;
import com.skill.platform.billing.dto.SettleResponse;
import com.skill.platform.billing.dto.TransactionView;
import com.skill.platform.billing.service.BillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部 API（gateway / worker / 充值平台调用；X-Internal-Token 认证）。
 */
@RestController
@RequestMapping("/internal/billing")
@RequiredArgsConstructor
public class InternalBillingController {

    private final BillingService billingService;

    @PostMapping("/accounts")
    public ApiResponse<Void> ensureAccount(@Valid @RequestBody AccountEnsureRequest request) {
        billingService.ensureAccount(request.appKeyId(), request.tenantId());
        return ApiResponse.ok();
    }

    @PostMapping("/freeze")
    public ApiResponse<FreezeResponse> freeze(@Valid @RequestBody FreezeRequest request) {
        return ApiResponse.ok(billingService.freeze(request.taskId(), request.appKeyId(),
                request.amount(), request.remark()));
    }

    @PostMapping("/settle")
    public ApiResponse<SettleResponse> settle(@Valid @RequestBody SettleRequest request) {
        return ApiResponse.ok(billingService.settle(request.taskId(), request.actualPoints()));
    }

    @PostMapping("/release")
    public ApiResponse<ReleaseResponse> release(@Valid @RequestBody ReleaseRequest request) {
        if (!request.hasKey()) {
            return ApiResponse.of(ErrorCode.PARAM_INVALID, "holdId 与 taskId 至少传一个", null);
        }
        ReleaseResponse response = request.holdId() != null && !request.holdId().isBlank()
                ? billingService.release(request.holdId())
                : billingService.releaseByTask(request.taskId());
        return ApiResponse.ok(response);
    }

    @GetMapping("/balance")
    public ApiResponse<BalanceView> balance(@RequestParam String appKeyId) {
        return ApiResponse.ok(billingService.balance(appKeyId));
    }

    @GetMapping("/holds")
    public ApiResponse<HoldView> holdOf(@RequestParam String taskId) {
        return ApiResponse.ok(billingService.holdOf(taskId));
    }

    @GetMapping("/transactions")
    public ApiResponse<PageView<TransactionView>> transactions(
            @RequestParam String appKeyId,
            @RequestParam(required = false) String taskId,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(billingService.transactions(appKeyId, taskId, pageNo, pageSize));
    }

    @PostMapping("/recharge")
    public ApiResponse<RechargeResult> recharge(@Valid @RequestBody RechargeRequest request) {
        return ApiResponse.ok(billingService.recharge(request.appKeyId(), request.tenantId(),
                request.points(), request.orderNo()));
    }

    @PostMapping("/adjust")
    public ApiResponse<BalanceView> adjust(@Valid @RequestBody AdjustRequest request) {
        return ApiResponse.ok(billingService.adjust(request.appKeyId(), request.delta(), request.remark()));
    }
}
