package com.skill.platform.gateway.common;

/**
 * 平台错误码（技术方案 v6.5 §5.6）。
 *
 * <p>注：40401（资源不存在）为 gateway 侧扩展码——文档轮询契约中「404 视为终态」的 HTTP 语义
 * 由本码承载（如 taskId 不存在 / 跨租户访问）。
 */
public enum ErrorCode {

    SUCCESS(0, 200, "成功"),

    PARAM_INVALID(40001, 400, "参数校验失败"),
    SKILL_NOT_FOUND(40002, 404, "Skill 不存在或版本不可用"),
    MATERIAL_TYPE_UNSUPPORTED(40004, 400, "素材类型不支持"),
    MATERIAL_TOO_LARGE(40005, 400, "素材大小超限"),
    COUNT_INVALID(40006, 400, "count 参数不合法（不支持数量控制 / 超上限未 override）"),

    UNAUTHORIZED(40101, 401, "鉴权失败（AppKey 无效 / 请求签名错误）"),

    INSUFFICIENT_BALANCE(40201, 402, "点数余额不足"),
    BILLING_VALIDATION_FAILED(40202, 402, "计费校验失败"),

    FORBIDDEN(40301, 403, "无权限（callbackUrl 不在白名单 / 素材跨租户或不可用）"),

    RESOURCE_NOT_FOUND(40401, 404, "资源不存在"),

    RATE_LIMITED(42901, 429, "限流 / 超配额"),

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
