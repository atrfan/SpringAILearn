package com.foxmimi.springaichat.exception;

import com.foxmimi.springaichat.handler.GlobalExceptionHandler;

/**
 * 抽取结果业务规则异常
 * <p>
 * 当 {@code ExtractResult} 解析与字段校验均通过，但违反服务层自定义的业务约束
 * （如 name/date/amount 三字段全部为 null，视为本次抽取未产出有效信息）时抛出，
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为 HTTP 502 错误响应。
 * </p>
 */
public class ExtractBusinessException extends RuntimeException {

    private final String rawContent;

    public ExtractBusinessException(String message, String rawContent) {
        super(message);
        this.rawContent = rawContent;
    }

    public String getRawContent() {
        return rawContent;
    }
}
