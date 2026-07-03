package com.foxmimi.springaichat.model;

/**
 * 统一错误响应体
 * <p>
 * 由 {@link com.foxmimi.springaichat.exception.GlobalExceptionHandler} 在捕获异常时构建，
 * 向客户端返回结构化的错误信息。
 * </p>
 *
 * @param code      业务错误码（如 INVALID_REQUEST、UPSTREAM_TIMEOUT）
 * @param message   用户可读的错误描述
 * @param timestamp 错误发生的时间戳（Unix 毫秒）
 */
public record ErrorResponse(
        String code,
        String message,
        long timestamp
) {
}
