package com.skill.platform.billing.common;

/**
 * 统一响应体：{@code {code, message, data}}。
 *
 * @param <T> data 类型
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), data);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> of(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(errorCode.code(), message, data);
    }
}
