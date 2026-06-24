package com.foxmimi.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxmimi.exception.LlmClientException;
import com.foxmimi.exception.LlmErrorType;
import com.foxmimi.model.ChatMessage;
import com.foxmimi.model.ChatRequest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 WireMock 模拟 6+ 类故障场景，验证 DeepSeekClient 的错误分类
 * 和 RetryingChatClient 的有限重试行为。
 *
 * <p>所有测试完全在本地运行，不调用真实付费 API，不产生 Token 费用。</p>
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>不可重试错误（401、非法 JSON）—— 只调用 1 次</li>
 *   <li>可重试错误（429、500、503、超时）—— 调用次数 = maxAttempts</li>
 *   <li>重试后成功 —— 瞬态故障恢复</li>
 *   <li>Retry-After 头 —— 遵守服务端建议的等待时间</li>
 * </ul>
 */
class DeepSeekClientWireMockTest {

    private static final String TEST_API_KEY = "test-api-key";
    private static final String CHAT_PATH = "/chat/completions";

    /** 成功响应的 JSON 模板，所有成功场景复用。 */
    private static final String SUCCESS_JSON = """
            {
              "id": "test-001",
              "model": "deepseek-v4-pro",
              "choices": [{"index": 0, "message": {"role": "assistant", "content": "OK"}, "finish_reason": "stop"}],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }""";

    private WireMockServer wmServer;
    private URI apiUri;

    @BeforeEach
    void startWireMock() {
        wmServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wmServer.start();
        apiUri = URI.create("http://localhost:" + wmServer.port() + CHAT_PATH);
    }

    @AfterEach
    void stopWireMock() {
        if (wmServer != null) {
            wmServer.stop();
        }
    }

    // ==================== 不可重试错误 ====================

    /** 401 认证失败：不可重试，只调用 1 次。 */
    @Test
    void shouldNotRetryOnAuthenticationError() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(RetryPolicy.DEFAULT).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.AUTHENTICATION, exception.type()),
                () -> assertEquals(401, exception.statusCode()),
                () -> assertFalse(exception.retryable()),
                () -> assertEquals(1, exception.attempts()),
                () -> wmServer.verify(1, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** 400 请求错误：修正请求前重试没有意义，只调用 1 次。 */
    @Test
    void shouldNotRetryOnClientError() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad request\"}")));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(fastPolicy()).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.CLIENT_ERROR, exception.type()),
                () -> assertEquals(400, exception.statusCode()),
                () -> assertFalse(exception.retryable()),
                () -> wmServer.verify(1, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** HTTP 200 + 非法 JSON：不可重试，只调用 1 次。 */
    @Test
    void shouldNotRetryOnInvalidJsonResponse() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200).withBody("not-json")));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(RetryPolicy.DEFAULT).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.RESPONSE_FORMAT, exception.type()),
                () -> assertFalse(exception.retryable()),
                () -> wmServer.verify(1, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** HTTP 200 但缺少 usage：协议结构错误，不可重试。 */
    @Test
    void shouldRejectMissingUsageWithoutRetry() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {
                          "id": "test-001",
                          "model": "deepseek-v4-pro",
                          "choices": [
                            {
                              "index": 0,
                              "message": {"role": "assistant", "content": "OK"},
                              "finish_reason": "stop"
                            }
                          ]
                        }
                        """)));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(fastPolicy()).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.RESPONSE_FORMAT, exception.type()),
                () -> assertTrue(exception.getMessage().contains("usage")),
                () -> wmServer.verify(1, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** HTTP 200 但 choices 为空：不能伪装成正常空回答，也不可重试。 */
    @Test
    void shouldRejectEmptyChoicesWithoutRetry() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {
                          "id": "test-001",
                          "model": "deepseek-v4-pro",
                          "choices": [],
                          "usage": {
                            "prompt_tokens": 10,
                            "completion_tokens": 0,
                            "total_tokens": 10
                          }
                        }
                        """)));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(fastPolicy()).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.RESPONSE_FORMAT, exception.type()),
                () -> assertTrue(exception.getMessage().contains("choices")),
                () -> wmServer.verify(1, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    // ==================== 可重试错误（耗尽重试） ====================

    /** 429 限流持续发生：重试 1 次后耗尽（共 2 次调用）。 */
    @Test
    void shouldRetryOnRateLimitAndExhaust() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate limited\"}")));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(fastPolicy()).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.RATE_LIMIT, exception.type()),
                () -> assertEquals(429, exception.statusCode()),
                () -> assertTrue(exception.retryable()),
                () -> assertEquals(2, exception.attempts()),
                () -> wmServer.verify(2, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** 500 服务端错误持续发生：重试 1 次后耗尽（共 2 次调用）。 */
    @Test
    void shouldRetryOn500ServerErrorAndExhaust() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"internal\"}")));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(fastPolicy()).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.SERVER_ERROR, exception.type()),
                () -> assertEquals(500, exception.statusCode()),
                () -> wmServer.verify(2, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** 503 服务不可用持续发生：重试 1 次后耗尽（共 2 次调用）。 */
    @Test
    void shouldRetryOn503ServerErrorAndExhaust() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}")));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(fastPolicy()).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.SERVER_ERROR, exception.type()),
                () -> assertEquals(503, exception.statusCode()),
                () -> wmServer.verify(2, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** 请求超时：可重试，重试 1 次后耗尽（共 2 次调用）。 */
    @Test
    void shouldRetryOnRequestTimeoutAndExhaust() {
        // WireMock 延迟 3 秒，客户端超时 1 秒，必然触发 HttpTimeoutException
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(3000)));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(fastPolicy()).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.REQUEST_TIMEOUT, exception.type()),
                () -> assertTrue(exception.retryable()),
                () -> wmServer.verify(2, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** 服务端显式返回 HTTP 408：映射为请求超时并有限重试。 */
    @Test
    void shouldRetryOnHttp408AndExhaust() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(408).withBody("{\"error\":\"timeout\"}")));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(fastPolicy()).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.REQUEST_TIMEOUT, exception.type()),
                () -> assertEquals(408, exception.statusCode()),
                () -> wmServer.verify(2, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    // ==================== 重试后成功 ====================

    /** 503 → 200：第一次失败，第二次成功，总共 2 次调用。 */
    @Test
    void shouldRecoverAfterTransientServerError() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .inScenario("transient-error")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}"))
                .willSetStateTo("recovered"));
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .inScenario("transient-error")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody(SUCCESS_JSON)));

        DeepSeekClient.CallResult result = retryingClient(fastPolicy()).chat(createRequest());

        assertAll(
                () -> assertEquals(200, result.statusCode()),
                () -> assertTrue(result.success()),
                () -> assertEquals(2, result.attempts()),
                () -> assertEquals("OK", result.response().answer()),
                () -> wmServer.verify(2, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** 429 → 200：限流后重试成功，总共 2 次调用。 */
    @Test
    void shouldRecoverAfterTransientRateLimit() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .inScenario("transient-rate-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate limited\"}"))
                .willSetStateTo("recovered"));
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .inScenario("transient-rate-limit")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody(SUCCESS_JSON)));

        DeepSeekClient.CallResult result = retryingClient(fastPolicy()).chat(createRequest());

        assertAll(
                () -> assertEquals(200, result.statusCode()),
                () -> assertTrue(result.success()),
                () -> wmServer.verify(2, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    // ==================== Retry-After ====================

    /**
     * 429 + Retry-After: 1：客户端应遵守 Retry-After 头的等待时间。
     *
     * <p>测试注入不实际休眠的等待器，既验证等待值，也避免拖慢测试。</p>
     */
    @Test
    void shouldUseRetryAfterHeaderBeforeRetry() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .inScenario("retry-after")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "2")
                        .withBody("{\"error\":\"rate limited\"}"))
                .willSetStateTo("recovered"));
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .inScenario("retry-after")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody(SUCCESS_JSON)));

        List<Duration> recordedSleeps = new ArrayList<>();
        RetryingChatClient client = new RetryingChatClient(
                rawClient(),
                new RetryPolicy(2, Duration.ofMillis(10), Duration.ofSeconds(5)),
                recordedSleeps::add
        );

        DeepSeekClient.CallResult result = client.chat(createRequest());

        assertAll(
                () -> assertEquals(200, result.statusCode()),
                () -> assertEquals(List.of(Duration.ofSeconds(2)), recordedSleeps),
                () -> wmServer.verify(2, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** Retry-After 超过策略上限时必须被截断，不能无限阻塞调用线程。 */
    @Test
    void shouldCapRetryAfterAtPolicyMaximum() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .inScenario("capped-retry-after")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Retry-After", "60")
                        .withBody("{\"error\":\"unavailable\"}"))
                .willSetStateTo("recovered"));
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .inScenario("capped-retry-after")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody(SUCCESS_JSON)));

        List<Duration> recordedSleeps = new ArrayList<>();
        RetryingChatClient client = new RetryingChatClient(
                rawClient(),
                new RetryPolicy(2, Duration.ofMillis(10), Duration.ofMillis(500)),
                recordedSleeps::add
        );

        client.chat(createRequest());

        assertEquals(List.of(Duration.ofMillis(500)), recordedSleeps);
    }

    /** HTTP 日期形式的 Retry-After 应转换为相对等待时间。 */
    @Test
    void shouldParseHttpDateRetryAfter() {
        Instant now = Instant.parse("2026-06-24T10:00:00Z");
        String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                now.plusSeconds(3).atZone(ZoneOffset.UTC)
        );

        assertEquals(
                Duration.ofSeconds(3),
                DeepSeekClient.parseRetryAfter(header, now)
        );
    }

    /** usage 数值自相矛盾时应作为协议错误拒绝，不得进入正常业务。 */
    @Test
    void shouldRejectInconsistentUsageWithoutRetry() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {
                          "id": "test-001",
                          "model": "deepseek-v4-pro",
                          "choices": [
                            {
                              "index": 0,
                              "message": {"role": "assistant", "content": "OK"},
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 10,
                            "completion_tokens": 5,
                            "total_tokens": 99
                          }
                        }
                        """)));

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> retryingClient(fastPolicy()).chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.RESPONSE_FORMAT, exception.type()),
                () -> assertTrue(exception.getMessage().contains("total_tokens")),
                () -> assertEquals(1, exception.attempts()),
                () -> wmServer.verify(1, postRequestedFor(urlEqualTo(CHAT_PATH)))
        );
    }

    /** 重试等待被中断时恢复线程中断标记，并停止后续请求。 */
    @Test
    void shouldStopRetryingWhenBackoffIsInterrupted() {
        wmServer.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}")));
        RetryingChatClient client = new RetryingChatClient(
                rawClient(),
                fastPolicy(),
                duration -> {
                    throw new InterruptedException("test interruption");
                }
        );

        try {
            LlmClientException exception = assertThrows(
                    LlmClientException.class,
                    () -> client.chat(createRequest())
            );

            assertAll(
                    () -> assertEquals(LlmErrorType.NETWORK_ERROR, exception.type()),
                    () -> assertTrue(Thread.currentThread().isInterrupted()),
                    () -> wmServer.verify(1, postRequestedFor(urlEqualTo(CHAT_PATH)))
            );
        } finally {
            Thread.interrupted();
        }
    }

    // ==================== 工具方法 ====================

    /** 创建带重试的客户端（用于大多数测试）。 */
    private RetryingChatClient retryingClient(RetryPolicy policy) {
        return new RetryingChatClient(rawClient(), policy);
    }

    /** 创建不带重试的原始客户端（用于直接测试错误映射和 Retry-After 解析）。 */
    private DeepSeekClient rawClient() {
        return new DeepSeekClient(
                apiUri,
                TEST_API_KEY,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Duration.ofSeconds(1)
        );
    }

    /** 测试专用快速策略：2 次尝试、100ms 基础延迟，减少测试等待时间。 */
    private static RetryPolicy fastPolicy() {
        return new RetryPolicy(2, Duration.ofMillis(100), Duration.ofMillis(500));
    }

    /** 创建所有测试共用的最小合法聊天请求。 */
    private ChatRequest createRequest() {
        return new ChatRequest(
                "deepseek-v4-pro",
                List.of(new ChatMessage("user", "Hello")),
                0.0,
                false,
                1024,
                null,
                null
        );
    }
}
