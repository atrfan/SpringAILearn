package com.foxmimi.client;

import com.foxmimi.exception.LlmClientException;
import com.foxmimi.exception.LlmErrorType;
import com.foxmimi.model.ChatRequest;

import java.time.Duration;

/**
 * 带有限重试的聊天客户端包装器。
 *
 * <p>在 {@link DeepSeekClient} 之上增加重试逻辑，只对
 * {@link com.foxmimi.exception.LlmErrorType#retryable()} 为 {@code true}
 * 的错误执行有限重试。认证失败（401）、格式错误等确定性错误不会重试。</p>
 *
 * <p>重试策略由 {@link RetryPolicy} 控制，包含指数退避、随机抖动和
 * Retry-After 支持。如果服务端通过 429 响应的 Retry-After 头指定了等待时间，
 * 则优先使用该值而非退避计算。</p>
 */
public final class RetryingChatClient implements LlmChatClient {

    /** 被包装的底层 API 客户端。 */
    private final LlmChatClient client;

    /** 重试策略配置。 */
    private final RetryPolicy retryPolicy;
    private final RetrySleeper sleeper;

    /**
     * 使用默认重试策略创建包装器。
     *
     * @param client 底层 API 客户端
     */
    public RetryingChatClient(LlmChatClient client) {
        this(client, RetryPolicy.DEFAULT);
    }

    /**
     * 使用自定义重试策略创建包装器。
     *
     * @param client      底层 API 客户端
     * @param retryPolicy 重试策略
     */
    public RetryingChatClient(LlmChatClient client, RetryPolicy retryPolicy) {
        this(client, retryPolicy, RetrySleeper.THREAD_SLEEPER);
    }

    public RetryingChatClient(
            LlmChatClient client,
            RetryPolicy retryPolicy,
            RetrySleeper sleeper
    ) {
        if (client == null) {
            throw new IllegalArgumentException("客户端不能为空");
        }
        if (retryPolicy == null) {
            throw new IllegalArgumentException("重试策略不能为空");
        }
        if (sleeper == null) {
            throw new IllegalArgumentException("等待器不能为空");
        }
        this.client = client;
        this.retryPolicy = retryPolicy;
        this.sleeper = sleeper;
    }

    /**
     * 发送聊天请求，失败时按策略有限重试。
     *
     * <p>重试条件：异常可重试 {@code &&} 未超过最大尝试次数。
     * 不可重试的错误（如认证失败、JSON 格式错误）会立即抛出，不会浪费时间和成本。</p>
     *
     * @param chatRequest 聊天请求
     * @return 调用结果
     * @throws LlmClientException 所有尝试均失败，或遇到不可重试的错误
     */
    @Override
    public DeepSeekClient.CallResult chat(ChatRequest chatRequest) {
        LlmClientException lastException = null;
        long start = System.nanoTime();

        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                DeepSeekClient.CallResult result = client.chat(chatRequest);
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                return result.withRetryMetadata(attempt + 1, elapsedMillis);
            } catch (LlmClientException exception) {
                // 不可重试的错误直接抛出：认证失败、格式错误、普通客户端错误
                if (!exception.retryable()) {
                    throw exception.withAttempts(attempt + 1);
                }

                lastException = exception;

                // 已达最大尝试次数，不再等待和重试
                if (attempt >= retryPolicy.maxAttempts() - 1) {
                    break;
                }

                // 计算等待时间：优先使用 Retry-After，否则使用指数退避
                sleepBeforeRetry(attempt, exception);
            }
        }

        if (lastException == null) {
            throw new IllegalStateException("重试策略未执行任何调用");
        }
        throw lastException.withAttempts(retryPolicy.maxAttempts());
    }

    /**
     * 在重试前等待指定时间。
     *
     * <p>如果异常携带 Retry-After 信息（如 HTTP 429 响应头），
     * 则使用该值；否则按指数退避策略计算。
     * 等待期间如果线程被中断，恢复中断标记并抛出异常。</p>
     */
    private void sleepBeforeRetry(int attempt, LlmClientException exception) {
        Duration wait = exception.retryAfter() == null
                ? retryPolicy.calculateDelay(attempt)
                : min(exception.retryAfter(), retryPolicy.maxDelay());

        try {
            sleeper.sleep(wait);
        } catch (InterruptedException interruptedException) {
            // 恢复中断标记，使上层能检测到中断
            Thread.currentThread().interrupt();
            throw new LlmClientException(
                    LlmErrorType.NETWORK_ERROR,
                    "重试等待被中断",
                    null,
                    null,
                    null,
                    attempt + 1,
                    interruptedException
            );
        }
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }
}
