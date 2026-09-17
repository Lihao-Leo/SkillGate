package com.skill.platform.billing.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：BizException → 对应 HTTP 状态 + 业务码；参数校验失败 → 40001；未知异常 → 50001。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        log.warn("biz error: code={}, message={}", e.errorCode().code(), e.getMessage());
        return ResponseEntity.status(e.errorCode().httpStatus())
                .body(ApiResponse.of(e.errorCode(), e.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.httpStatus())
                .body(ApiResponse.of(ErrorCode.PARAM_INVALID, detail, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("internal error", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.of(ErrorCode.INTERNAL_ERROR, "内部错误", null));
    }
}
