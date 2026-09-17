package com.skill.platform.gateway.service;

import com.skill.platform.gateway.billing.BillingClient;
import com.skill.platform.gateway.billing.BillingViews.BalanceView;
import com.skill.platform.gateway.billing.BillingViews.TransactionPage;
import com.skill.platform.gateway.security.CallerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 账户查询（§5.7）：AppKey 鉴权后代理 billing-service（余额 / 计费流水）。
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final BillingClient billingClient;

    public BalanceView balance(CallerContext caller) {
        return billingClient.balance(caller.appKeyId());
    }

    public TransactionPage transactions(CallerContext caller, String taskId, int pageNo, int pageSize) {
        return billingClient.transactions(caller.appKeyId(), taskId,
                Math.max(pageNo, 1), Math.min(Math.max(pageSize, 1), 100));
    }
}
