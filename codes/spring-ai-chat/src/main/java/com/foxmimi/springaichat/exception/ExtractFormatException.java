package com.foxmimi.springaichat.exception;

import com.foxmimi.springaichat.handler.GlobalExceptionHandler;

/**
 * 抽取结果格式异常
 * <p>
 * 当 AI 模型服务返回的字符串不能被解析为正确的json 时，会抛出此异常
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为 HTTP 502 错误响应。
 * </p>
 */
public class ExtractFormatException extends RuntimeException {

    private final String rawContent;

    public ExtractFormatException(String message, String rawContent) {
        super(message);
        this.rawContent = rawContent;
    }

    public String getRawContent() {
        return rawContent;
    }
}
