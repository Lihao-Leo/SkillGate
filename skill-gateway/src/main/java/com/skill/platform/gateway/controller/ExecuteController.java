package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.common.ApiResponse;
import com.skill.platform.gateway.security.AppKeyAuthInterceptor;
import com.skill.platform.gateway.security.CallerContext;
import com.skill.platform.gateway.service.ExecuteDtos.ExecuteRequest;
import com.skill.platform.gateway.service.ExecuteDtos.ExecuteResult;
import com.skill.platform.gateway.service.ExecuteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestAttribute;

/**
 * 执行接口（§5.3）：统一异步，受理五关在拦截器 + ExecuteService；
 * 结果获取双通道（回调 / 轮询）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ExecuteController {

    private final ExecuteService executeService;

    @PostMapping("/execute")
    public ApiResponse<ExecuteResult> execute(
            @RequestAttribute(AppKeyAuthInterceptor.ATTR_CALLER) CallerContext caller,
            @Valid @RequestBody ExecuteRequest request) {
        return ApiResponse.ok(executeService.submit(caller, request));
    }
}
