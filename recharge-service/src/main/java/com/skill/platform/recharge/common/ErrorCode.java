package com.skill.platform.recharge.common;

/**
 * 错误码（recharge-service 内部口径，对齐执行平台 §5.6 风格）。
 */
public enum ErrorCode {

    SUCCESS(0, 200, "成功"),

    PARAM_INVALID(40001, 400, "参数校验失败"),
    UNAUTHORIZED(40101, 401, "鉴权失败（未登录 / 回调验签失败 / 验证码错误）"),

    RESOURCE_NOT_FOUND(40401, 404, "资源不存在（订单 / key）"),

    RATE_LIMITED(42901, 429, "限流（验证码发送过频）"),

    INTERNAL_ERROR(50001, 500, "内部错误");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
