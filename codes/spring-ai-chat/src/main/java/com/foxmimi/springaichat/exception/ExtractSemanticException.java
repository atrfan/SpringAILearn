package com.foxmimi.springaichat.exception;

import com.foxmimi.springaichat.handler.GlobalExceptionHandler;

/**
 * 抽取结果语义异常
 * <p>
 * 当 {@code ExtractResult} 解析成功、但字段内容未通过 Bean Validation 校验
 * （如 amount 格式不符合"数字 + 单位"约定）时抛出，
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为 HTTP 502 错误响应。
 * </p>
 */
public class ExtractSemanticException extends RuntimeException {

    private final String rawContent;

    public ExtractSemanticException(String message, String rawContent) {
        super(message);
        this.rawContent = rawContent;
    }

    public String getRawContent() {
        return rawContent;
    }
}
