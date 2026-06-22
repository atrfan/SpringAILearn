package com.foxmimi.exception;

/**
 * LLM 客户端可能产生的错误类别。
 *
 * <p>每个类别携带默认的可重试属性，供上层制定重试策略。该属性只表示
 * 一般情况下是否值得重试，不代表客户端会自动执行重试。</p>
 */
public enum LlmErrorType {
    /** API Key 无效、缺失权限或认证失败，例如 HTTP 401、403。 */
    AUTHENTICATION(false),

    /** 请求受到服务端限流，例如 HTTP 429。 */
    RATE_LIMIT(true),

    /** LLM 服务端发生异常，例如 HTTP 5xx。 */
    SERVER_ERROR(true),

    /** 请求未在规定时间内完成。 */
    REQUEST_TIMEOUT(true),

    /** 连接建立、数据传输等网络过程失败。 */
    NETWORK_ERROR(true),

    /** 请求无法序列化，或响应 JSON 无法按预期结构解析。 */
    RESPONSE_FORMAT(false),

    /** 未单独分类的客户端请求错误，例如其他 HTTP 4xx。 */
    CLIENT_ERROR(false);

    /** 该错误类别在一般情况下是否允许重试。 */
    private final boolean retryable;

    LlmErrorType(boolean retryable) {
        this.retryable = retryable;
    }

    /**
     * 返回该类错误默认是否可重试。
     *
     * @return {@code true} 表示上层可以考虑有限重试
     */
    public boolean retryable() {
        return retryable;
    }
}
