package com.skill.platform.billing.common;

/**
 * 业务异常：携带错误码与可展示消息；全局处理器按 {@link ErrorCode#httpStatus()} 映射 HTTP 状态。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
