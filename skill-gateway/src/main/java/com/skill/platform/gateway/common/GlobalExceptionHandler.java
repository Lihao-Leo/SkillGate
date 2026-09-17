package com.skill.platform.gateway.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理：BizException → HTTP 状态 + 业务码 + data；上传超限 → 40005。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBiz(BizException e) {
        log.warn("biz error: code={}, message={}", e.errorCode().code(), e.getMessage());
        return ResponseEntity.status(e.errorCode().httpStatus())
                .body(ApiResponse.of(e.errorCode(), e.getMessage(), e.data()));
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

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(ErrorCode.MATERIAL_TOO_LARGE.httpStatus())
                .body(ApiResponse.of(ErrorCode.MATERIAL_TOO_LARGE, "上传文件超过大小限制", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("internal error", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.of(ErrorCode.INTERNAL_ERROR, "内部错误", null));
    }
}
