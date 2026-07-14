package com.foxmimi.springaichat.exception;

import com.foxmimi.springaichat.handler.GlobalExceptionHandler;

/**
 * 抽取重试耗尽异常
 * <p>
 * 当格式错误或语义错误达到重试上限仍未通过解析与校验时抛出，
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为 HTTP 502 错误响应。
 * </p>
 */
public class ExtractRetryExhaustedException extends RuntimeException {

    private final String rawContent;

    public ExtractRetryExhaustedException(String message, String rawContent) {
        super(message);
        this.rawContent = rawContent;
    }

    public String getRawContent() {
        return rawContent;
    }
}
