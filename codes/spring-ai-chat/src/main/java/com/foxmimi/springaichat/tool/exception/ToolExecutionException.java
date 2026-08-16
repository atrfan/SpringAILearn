package com.foxmimi.springaichat.tool.exception;

/**
 * 工具执行异常
 * <p>
 * 当下游工具调用（如 CMA 天气接口）失败时抛出，由
 * {@link com.foxmimi.springaichat.handler.GlobalExceptionHandler} 统一捕获并按
 * {@link FailureCause} 映射为对应的 HTTP 状态码与业务错误码。
 * </p>
 * <p>
 * 字段命名为 {@code failureCause} 而非 {@code cause}，避免与
 * {@link Throwable#getCause()} 混淆。
 * </p>
 */
public class ToolExecutionException extends RuntimeException {

    /**
     * 下游失败原因分类，对应 design.md §2.3 错误码映射表。
     */
    public enum FailureCause {
        TIMEOUT,
        BLOCKED,
        RATE_LIMITED,
        CLIENT_4XX,
        SERVER_5XX,
        NOT_JSON,
        BUSINESS_ERROR
    }

    private final FailureCause failureCause;

    public ToolExecutionException(FailureCause failureCause, String message) {
        super(message);
        this.failureCause = failureCause;
    }

    public ToolExecutionException(FailureCause failureCause, String message, Throwable cause) {
        super(message, cause);
        this.failureCause = failureCause;
    }

    public FailureCause getFailureCause() {
        return failureCause;
    }
}
