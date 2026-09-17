package com.skill.platform.billing.common;

/**
 * 错误码（技术方案 v6.5 §5.6 子集 + billing 内部语义）。
 *
 * <p>HTTP 状态与业务码同时返回：HTTP 表达传输层语义，body.code 表达业务语义。
 */
public enum ErrorCode {

    SUCCESS(0, 200, "成功"),

    PARAM_INVALID(40001, 400, "参数校验失败"),

    UNAUTHORIZED(40101, 401, "鉴权失败"),

    INSUFFICIENT_BALANCE(40201, 402, "点数余额不足"),
    BILLING_VALIDATION_FAILED(40202, 402, "计费校验失败"),

    RESOURCE_NOT_FOUND(40401, 404, "资源不存在"),

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
