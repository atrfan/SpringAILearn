package com.foxmimi.springaichat.exception;

/**
 * 上游服务响应异常
 * <p>
 * 当 AI 模型服务返回 null 或异常响应时抛出此异常，
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为 HTTP 502 错误响应。
 * </p>
 */
public class UpstreamResponseException extends RuntimeException {

    public UpstreamResponseException(String message) {
        super(message);
    }
}
