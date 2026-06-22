package com.foxmimi.exception;

/**
 * LLM 客户端的统一运行时异常。
 *
 * <p>上层只需捕获该异常，再通过 {@link #type()} 判断具体故障类别。
 * HTTP 状态码和响应体只在服务端已返回响应时存在，因此允许为 {@code null}。</p>
 */
public final class LlmClientException extends RuntimeException {

    /** 经过归类的错误类型。 */
    private final LlmErrorType type;

    /** HTTP 状态码；网络失败、超时或序列化失败时可能为 {@code null}。 */
    private final Integer statusCode;

    /**
     * 服务端原始响应体；服务端未返回响应时可能为 {@code null}。
     * 该字段可能包含敏感信息，不应未经脱敏直接写入日志。
     */
    private final String responseBody;

    /**
     * 创建统一客户端异常。
     *
     * @param type         错误类别
     * @param message      面向调用方的错误说明，不应包含 API Key
     * @param statusCode   HTTP 状态码，不存在时传入 {@code null}
     * @param responseBody 服务端原始响应体，不存在时传入 {@code null}
     * @param cause        原始异常，不存在时传入 {@code null}
     */
    public LlmClientException(
            LlmErrorType type,
            String message,
            Integer statusCode,
            String responseBody,
            Throwable cause
    ) {
        super(message, cause);
        this.type = type;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /** @return 错误类别 */
    public LlmErrorType type() {
        return type;
    }

    /** @return HTTP 状态码；请求未获得 HTTP 响应时返回 {@code null} */
    public Integer statusCode() {
        return statusCode;
    }

    /**
     * @return 服务端原始响应体；服务端未返回响应时返回 {@code null}
     */
    public String responseBody() {
        return responseBody;
    }

    /**
     * 返回当前错误类别默认是否允许重试。
     *
     * @return {@code true} 表示上层可以考虑有限重试
     */
    public boolean retryable() {
        return type.retryable();
    }
}
