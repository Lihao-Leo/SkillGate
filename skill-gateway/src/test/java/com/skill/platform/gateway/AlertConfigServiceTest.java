package com.skill.platform.gateway;

import com.skill.platform.gateway.service.AlertConfigService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警配置统一维护测试：sys_config 读写往返、发送测试消息（stub 飞书端点，
 * code=0 成功 / code!=0 拒绝 / 未配置 webhook 快速失败）。
 */
@SpringBootTest
@ActiveProfiles("local")
class AlertConfigServiceTest {

    @Autowired
    private AlertConfigService alertConfigService;

    private HttpServer stub;
    private int stubPort;
    private volatile int stubCode = 200;
    private volatile String stubBody = "{\"code\":0,\"msg\":\"success\"}";

    @BeforeEach
    void startStub() throws Exception {
        stubCode = 200;
        stubBody = "{\"code\":0,\"msg\":\"success\"}";
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stubPort = stub.getAddress().getPort();
        stub.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] reply = stubBody.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(stubCode, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        stub.start();
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    @Test
    void save_then_get_roundtrip() {
        AlertConfigService.AlertConfig config = new AlertConfigService.AlertConfig(
                "https://open.feishu.cn/hook/demo", "sign-secret", 120, 5000);

        alertConfigService.save(config);
        AlertConfigService.AlertConfig loaded = alertConfigService.get();

        assertThat(loaded.webhookUrl()).isEqualTo("https://open.feishu.cn/hook/demo");
        assertThat(loaded.secret()).isEqualTo("sign-secret");
        assertThat(loaded.runningSeconds()).isEqualTo(120);
        assertThat(loaded.tokensThreshold()).isEqualTo(5000);
    }

    @Test
    void test_send_success_when_feishu_accepts() {
        alertConfigService.save(new AlertConfigService.AlertConfig(
                "http://127.0.0.1:" + stubPort + "/hook", "", 600, 0));

        var result = alertConfigService.sendTest();

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("message")).isEqualTo("测试消息已送达飞书群");
    }

    @Test
    void test_send_reports_feishu_rejection() {
        stubCode = 200;
        stubBody = "{\"code\":19021,\"msg\":\"sign match fail\"}";
        alertConfigService.save(new AlertConfigService.AlertConfig(
                "http://127.0.0.1:" + stubPort + "/hook", "", 600, 0));

        var result = alertConfigService.sendTest();

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(result.get("message"))).contains("sign match fail");
    }

    @Test
    void test_send_without_webhook_fails_fast() {
        alertConfigService.save(new AlertConfigService.AlertConfig("", "", 600, 0));

        var result = alertConfigService.sendTest();

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(result.get("message"))).contains("未配置 webhook");
    }

    @Test
    void test_send_unreachable_webhook_reports_error() {
        alertConfigService.save(new AlertConfigService.AlertConfig(
                "http://127.0.0.1:9/hook", "", 600, 0));

        var result = alertConfigService.sendTest();

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(result.get("message"))).contains("发送失败");
    }
}
