package com.skill.platform.recharge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skill.platform.recharge.common.BizException;
import com.skill.platform.recharge.common.ErrorCode;
import com.skill.platform.recharge.common.Ids;
import com.skill.platform.recharge.dal.entity.UserAccount;
import com.skill.platform.recharge.dal.entity.VerificationCode;
import com.skill.platform.recharge.dal.mapper.UserAccountMapper;
import com.skill.platform.recharge.dal.mapper.VerificationCodeMapper;
import com.skill.platform.recharge.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * C 端用户认证（V1 最简：手机号 / 邮箱 + 验证码，JWT 会话）。
 * 发送限流：同 identifier 60s 一条、当日 5 条。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAuthService {

    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final int CODE_TTL_MINUTES = 10;
    private static final int SEND_INTERVAL_SECONDS = 60;
    private static final int DAILY_LIMIT = 5;

    private final VerificationCodeMapper codeMapper;
    private final UserAccountMapper userMapper;

    @Value("${recharge.jwt.secret}")
    private String jwtSecret;
    @Value("${recharge.jwt.ttl-days:7}")
    private int jwtTtlDays;
    @Value("${recharge.auth.dev-echo-code:false}")
    private boolean devEchoCode;

    public Map<String, Object> sendCode(String identifier) {
        String type = identifierType(identifier);
        LocalDateTime now = LocalDateTime.now();

        VerificationCode latest = codeMapper.selectOne(new LambdaQueryWrapper<VerificationCode>()
                .eq(VerificationCode::getIdentifier, identifier)
                .orderByDesc(VerificationCode::getId)
                .last("LIMIT 1"));
        if (latest != null && latest.getCreatedAt() != null
                && latest.getCreatedAt().isAfter(now.minusSeconds(SEND_INTERVAL_SECONDS))) {
            throw new BizException(ErrorCode.RATE_LIMITED, "验证码发送过频，请 1 分钟后再试");
        }
        Long todayCount = codeMapper.selectCount(new LambdaQueryWrapper<VerificationCode>()
                .eq(VerificationCode::getIdentifier, identifier)
                .ge(VerificationCode::getCreatedAt, LocalDate.now().atStartOfDay()));
        if (todayCount != null && todayCount >= DAILY_LIMIT) {
            throw new BizException(ErrorCode.RATE_LIMITED, "验证码发送次数已达当日上限");
        }

        String code = Ids.verificationCode();
        VerificationCode entity = new VerificationCode();
        entity.setIdentifier(identifier);
        entity.setScene("LOGIN");
        entity.setCode(code);
        entity.setExpiresAt(now.plusMinutes(CODE_TTL_MINUTES));
        codeMapper.insert(entity);
        // 生产接短信/邮件通道；此处仅记录（验证码明文只在 DB，绝不打日志）
        log.info("verification code sent: identifier={}(masked), type={}", mask(identifier), type);

        return devEchoCode
                ? Map.of("devEchoCode", code, "expiresInMinutes", CODE_TTL_MINUTES)
                : Map.of("expiresInMinutes", CODE_TTL_MINUTES);
    }

    public Map<String, Object> login(String identifier, String code) {
        identifierType(identifier);
        VerificationCode latest = codeMapper.selectOne(new LambdaQueryWrapper<VerificationCode>()
                .eq(VerificationCode::getIdentifier, identifier)
                .orderByDesc(VerificationCode::getId)
                .last("LIMIT 1"));
        if (latest == null || latest.getUsedAt() != null
                || latest.getExpiresAt().isBefore(LocalDateTime.now())
                || !latest.getCode().equals(code)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "验证码错误或已过期");
        }
        // 验证码一次性：使用即作废
        latest.setUsedAt(LocalDateTime.now());
        codeMapper.updateById(latest);

        UserAccount user = userMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getIdentifier, identifier).last("LIMIT 1"));
        if (user == null) {
            user = new UserAccount();
            user.setIdentifier(identifier);
            user.setIdentifierType(identifierType(identifier));
            user.setStatus(1);
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException e) {
                user = userMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getIdentifier, identifier).last("LIMIT 1"));
            }
        }
        if (user.getStatus() != 1) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账户已禁用");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        String token = JwtUtil.issue(jwtSecret, user.getId(),
                java.time.Instant.now().getEpochSecond() + jwtTtlDays * 24L * 3600);
        return Map.of("token", token, "userId", user.getId(), "identifier", mask(identifier));
    }

    static String identifierType(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "identifier 不能为空");
        }
        if (PHONE.matcher(identifier).matches()) {
            return "PHONE";
        }
        if (EMAIL.matcher(identifier).matches()) {
            return "EMAIL";
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "identifier 须为合法手机号或邮箱");
    }

    private static String mask(String identifier) {
        if (identifier == null || identifier.length() <= 4) {
            return "****";
        }
        int at = identifier.indexOf('@');
        return at > 1
                ? identifier.substring(0, 2) + "****" + identifier.substring(at)
                : identifier.substring(0, 3) + "****" + identifier.substring(identifier.length() - 2);
    }
}
