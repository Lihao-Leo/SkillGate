package com.skill.platform.recharge.common;

/**
 * 业务异常：错误码 + 可展示消息 + 可选数据载荷。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object data;

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object data() {
        return data;
    }
}
