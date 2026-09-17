package com.skill.platform.gateway.controller;

import com.skill.platform.gateway.service.ModelGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 模型网关（OpenAI 兼容 /v1/chat/completions）：沙箱内技能经此按 alias 调用模型，
 * 路由配置实时读 model_provider 表（后管即生效）。
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ModelGatewayController {

    private final ModelGatewayService modelGatewayService;

    @PostMapping("/chat/completions")
    public void chatCompletions(HttpServletRequest request, HttpServletResponse response)
            throws IOException, InterruptedException {
        String auth = request.getHeader("Authorization");
        if (!modelGatewayService.authorize(auth)) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"message\":\"invalid api key（应为 "
                    + "worker 注入的 internal-token-task-*）\",\"type\":\"invalid_request_error\"}}");
            return;
        }
        String raw = new String(request.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        ModelGatewayService.GatewayResult result = modelGatewayService.chat(raw);
        response.setStatus(result.status());
        response.setContentType(result.contentType());
        response.getOutputStream().write(result.body());
        response.getOutputStream().flush();
    }
}
