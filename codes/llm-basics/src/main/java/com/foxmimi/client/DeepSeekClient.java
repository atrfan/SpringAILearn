package com.foxmimi.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.foxmimi.exception.LlmClientException;
import com.foxmimi.exception.LlmErrorType;
import com.foxmimi.model.ChatRequest;
import com.foxmimi.model.ChatResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * 使用 Java 原生 {@link HttpClient} 调用 DeepSeek Chat Completions API。
 *
 * <p>客户端支持注入 API 地址、HTTP 客户端、JSON 映射器和请求超时时间，
 * 因而既能使用默认生产配置，也能在测试中连接 WireMock 等本地服务。</p>
 */
public final class DeepSeekClient {

    /** DeepSeek Chat Completions API 的默认生产地址。 */
    private static final URI DEFAULT_API_URI =
            URI.create("https://api.deepseek.com/chat/completions");

    /** 单次请求的默认最长等待时间。 */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final URI apiUri;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    /**
     * 使用默认生产配置创建客户端。
     *
     * <p>该构造器适合应用正常运行；测试代码应使用完整构造器注入本地地址和依赖。</p>
     *
     * @param apiKey DeepSeek API Key，不能为空
     */
    public DeepSeekClient(String apiKey) {
        this(
                DEFAULT_API_URI,
                apiKey,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                new ObjectMapper(),
                DEFAULT_REQUEST_TIMEOUT
        );
    }

    /**
     * 使用显式依赖创建客户端，便于测试或替换基础设施配置。
     *
     * @param apiUri        完整的 Chat Completions API 地址
     * @param apiKey        API Key，不能为空
     * @param httpClient    用于发送请求的 HTTP 客户端
     * @param objectMapper  用于 JSON 序列化和反序列化的映射器
     * @param requestTimeout 单次请求超时时间，必须大于零
     */
    public DeepSeekClient(
            URI apiUri,
            String apiKey,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Duration requestTimeout
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        if (requestTimeout == null
                || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("请求超时时间必须大于零");
        }

        this.apiUri = Objects.requireNonNull(apiUri, "API 地址不能为空");
        this.apiKey = apiKey;
        this.httpClient = Objects.requireNonNull(httpClient, "HttpClient 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空");
        this.requestTimeout = requestTimeout;
    }

    /**
     * 发送一次非流式聊天请求并解析响应。
     *
     * @param chatRequest 聊天请求对象
     * @return HTTP 状态、调用耗时和模型响应
     * @throws LlmClientException 请求、网络或响应解析失败时抛出
     */
    public CallResult chat(ChatRequest chatRequest) {
        Objects.requireNonNull(chatRequest, "聊天请求不能为空");

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(chatRequest);
        } catch (JsonProcessingException exception) {
            throw new LlmClientException(
                    LlmErrorType.RESPONSE_FORMAT,
                    "请求对象无法序列化为 JSON",
                    null,
                    null,
                    exception
            );
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(apiUri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody,
                        StandardCharsets.UTF_8
                ))
                .build();

        long start = System.nanoTime();

        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        } catch (HttpTimeoutException exception) {
            throw new LlmClientException(
                    LlmErrorType.REQUEST_TIMEOUT,
                    "LLM API 请求超时",
                    null,
                    null,
                    exception
            );
        } catch (IOException exception) {
            throw new LlmClientException(
                    LlmErrorType.NETWORK_ERROR,
                    "LLM API 网络请求失败",
                    null,
                    null,
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmClientException(
                    LlmErrorType.NETWORK_ERROR,
                    "LLM API 请求被中断",
                    null,
                    null,
                    exception
            );
        }

        long elapsedMillis =
                (System.nanoTime() - start) / 1_000_000;

        if (httpResponse.statusCode() < 200
                || httpResponse.statusCode() >= 300) {
            throw createHttpException(httpResponse);
        }

        ChatResponse response;
        try {
            response = objectMapper.readValue(
                    httpResponse.body(),
                    ChatResponse.class
            );
        } catch (JsonProcessingException exception) {
            throw new LlmClientException(
                    LlmErrorType.RESPONSE_FORMAT,
                    "LLM API 响应不是有效的预期 JSON",
                    httpResponse.statusCode(),
                    httpResponse.body(),
                    exception
            );
        }

        return new CallResult(
                response.model(),
                httpResponse.statusCode(),
                elapsedMillis,
                response.usage().promptTokens(),
                response.usage().completionTokens(),
                response.usage().totalTokens(),
                true,
                response
        );
    }

    /**
     * 根据服务端已经返回的 HTTP 响应创建统一客户端异常。
     *
     * <p>与网络异常不同，HTTP 错误已经取得服务端响应，因此异常中保留
     * 状态码和原始响应体。响应体可能包含敏感信息，调用方不得直接写入日志。</p>
     *
     * @param response LLM 服务端返回的非成功 HTTP 响应
     * @return 包含错误类别、状态码和响应体的统一异常
     */
    private static LlmClientException createHttpException(
            HttpResponse<String> response
    ) {
        int statusCode = response.statusCode();

        return new LlmClientException(
                classify(statusCode),
                "LLM API 调用失败，HTTP 状态码：" + statusCode,
                statusCode,
                response.body(),
                null
        );
    }

    /**
     * 将 HTTP 状态码转换为稳定的客户端错误类别，使上层不依赖具体状态码。
     * HTTP 408 表示服务端返回了请求超时响应，与客户端抛出的
     * {@link HttpTimeoutException} 来源不同，但二者采用相同错误类别。
     *
     * @param statusCode LLM 服务端返回的 HTTP 状态码
     * @return 对应的错误类别
     */
    static LlmErrorType classify(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> LlmErrorType.AUTHENTICATION;
            case 408 -> LlmErrorType.REQUEST_TIMEOUT;
            case 429 -> LlmErrorType.RATE_LIMIT;
            default -> statusCode >= 500 && statusCode <= 599
                    ? LlmErrorType.SERVER_ERROR
                    : LlmErrorType.CLIENT_ERROR;
        };
    }

    public record CallResult(
            String model,
            int statusCode,
            long elapsedMillis,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            boolean success,
            ChatResponse response
    ) {}
}
