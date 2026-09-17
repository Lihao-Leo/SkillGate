package com.skill.platform.recharge;

import com.skill.platform.recharge.dal.entity.UserAccount;
import com.skill.platform.recharge.dal.entity.VerificationCode;
import com.skill.platform.recharge.dal.mapper.UserAccountMapper;
import com.skill.platform.recharge.dal.mapper.VerificationCodeMapper;
import com.skill.platform.recharge.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户认证测试：验证码发送限流、登录换 JWT、会话过滤器。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class UserAuthTest {

    private static final Random RANDOM = new Random();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private VerificationCodeMapper codeMapper;
    @Autowired
    private UserAccountMapper userMapper;

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String phone() {
        return "139" + String.format("%08d", RANDOM.nextInt(100_000_000));
    }

    @Test
    void send_code_echoes_in_dev_and_rate_limits() throws Exception {
        String phone = phone();
        mockMvc.perform(post("/api/user/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + phone + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.devEchoCode").exists());

        // 60s 内第二条 → 42901
        mockMvc.perform(post("/api/user/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + phone + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));

        // 非法 identifier → 40001
        mockMvc.perform(post("/api/user/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"not-a-contact\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void login_with_correct_code_returns_jwt_and_protects_user_api() throws Exception {
        String email = "user-" + uniq() + "@example.com";
        MvcResult send = mockMvc.perform(post("/api/user/auth/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String code = objectMapper.readTree(send.getResponse().getContentAsString())
                .path("data").path("devEchoCode").asText();

        // 错误验证码 → 40101（不消耗）
        mockMvc.perform(post("/api/user/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult login = mockMvc.perform(post("/api/user/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").exists())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("data").path("token").asText();

        // 验证码一次性：同码再登 → 40101
        mockMvc.perform(post("/api/user/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());

        // 受保护端点：无 token 40101，有 token 200
        mockMvc.perform(get("/api/user/keys"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
        mockMvc.perform(get("/api/user/keys").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        // 伪造 token → 40101
        mockMvc.perform(get("/api/user/keys").header("Authorization", "Bearer fake.token.sig"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jwt_util_sign_and_verify_roundtrip() {
        String secret = "jwt-test-secret";
        String token = JwtUtil.issue(secret, 42L,
                java.time.Instant.now().getEpochSecond() + 60);
        assertThat(JwtUtil.verify(secret, token)).isEqualTo(42L);
        assertThat(JwtUtil.verify("other-secret", token)).isNull();
        assertThat(JwtUtil.verify(secret, token + "x")).isNull();
        // 过期
        String expired = JwtUtil.issue(secret, 42L,
                java.time.Instant.now().getEpochSecond() - 1);
        assertThat(JwtUtil.verify(secret, expired)).isNull();
    }

    @Test
    void disabled_user_cannot_login() throws Exception {
        String email = "banned-" + uniq() + "@example.com";
        UserAccount user = new UserAccount();
        user.setIdentifier(email);
        user.setIdentifierType("EMAIL");
        user.setStatus(0);
        userMapper.insert(user);

        VerificationCode vc = new VerificationCode();
        vc.setIdentifier(email);
        vc.setScene("LOGIN");
        vc.setCode("123456");
        vc.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        codeMapper.insert(vc);

        mockMvc.perform(post("/api/user/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"" + email + "\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }
}
