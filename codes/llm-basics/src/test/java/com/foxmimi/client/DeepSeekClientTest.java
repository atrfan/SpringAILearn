package com.foxmimi.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.foxmimi.exception.LlmClientException;
import com.foxmimi.exception.LlmErrorType;
import com.foxmimi.model.ChatMessage;
import com.foxmimi.model.ChatRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeekClient 的本地 HTTP 测试。
 *
 * <p>测试使用 JDK 自带的本地 HTTP Server，不会访问真实 DeepSeek API，
 * 因此不会消耗 Token，也不需要真实 API Key。</p>
 */
class DeepSeekClientTest {

    private static final String TEST_API_KEY = "test-api-key";

    private HttpServer server;
    private URI apiUri;
    private String capturedRequestBody;

    /** 每个测试启动独立的本地服务，避免测试之间共享响应状态。 */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        capturedRequestBody = null;
        apiUri = URI.create(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/chat/completions"
        );
    }

    /** 测试完成后关闭本地服务并释放端口。 */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldPopulateObservableFieldsFromSuccessfulResponse() {
        registerResponse(200, """
                {
                  "id": "chat-test-001",
                  "model": "deepseek-v4-pro",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Hello"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 8,
                    "total_tokens": 20
                  }
                }
                """);

        DeepSeekClient.CallResult result = createClient().chat(createRequest());
        JsonNode requestJson = parseCapturedRequest();

        assertAll(
                () -> assertEquals("deepseek-v4-pro", result.model()),
                () -> assertEquals(200, result.statusCode()),
                () -> assertTrue(result.elapsedMillis() >= 0),
                () -> assertEquals(12, result.promptTokens()),
                () -> assertEquals(8, result.completionTokens()),
                () -> assertEquals(20, result.totalTokens()),
                () -> assertTrue(result.success()),
                () -> assertNotNull(result.response()),
                () -> assertEquals("Hello", result.response().answer()),
                () -> assertTrue(requestJson.get("temperature").isNumber()),
                () -> assertEquals(0.7, requestJson.get("temperature").asDouble()),
                () -> assertTrue(requestJson.has("max_tokens")),
                () -> assertFalse(requestJson.has("maxTokens")),
                () -> assertTrue(requestJson.get("max_tokens").isIntegralNumber()),
                () -> assertEquals(1024, requestJson.get("max_tokens").asInt())
        );
    }

    @Test
    void shouldRejectInvalidGenerationParameters() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createRequest(-0.1, 1024)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createRequest(2.1, 1024)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createRequest(Double.NaN, 1024)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createRequest(0.7, 0)
                )
        );
    }

    @Test
    void shouldMapAuthenticationFailure() {
        registerResponse(401, "{\"error\":{\"message\":\"invalid key\"}}");

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> createClient().chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.AUTHENTICATION, exception.type()),
                () -> assertEquals(Integer.valueOf(401), exception.statusCode()),
                () -> assertFalse(exception.retryable()),
                () -> assertNotNull(exception.responseBody())
        );
    }

    @Test
    void shouldMapRateLimitFailure() {
        registerResponse(429, "{\"error\":{\"message\":\"rate limited\"}}");

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> createClient().chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.RATE_LIMIT, exception.type()),
                () -> assertEquals(Integer.valueOf(429), exception.statusCode()),
                () -> assertTrue(exception.retryable())
        );
    }

    @Test
    void shouldMapServerFailure() {
        registerResponse(503, "{\"error\":{\"message\":\"service unavailable\"}}");

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> createClient().chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.SERVER_ERROR, exception.type()),
                () -> assertEquals(Integer.valueOf(503), exception.statusCode()),
                () -> assertTrue(exception.retryable())
        );
    }

    @Test
    void shouldRejectInvalidJsonResponse() {
        registerResponse(200, "not-json");

        LlmClientException exception = assertThrows(
                LlmClientException.class,
                () -> createClient().chat(createRequest())
        );

        assertAll(
                () -> assertEquals(LlmErrorType.RESPONSE_FORMAT, exception.type()),
                () -> assertEquals(Integer.valueOf(200), exception.statusCode()),
                () -> assertFalse(exception.retryable())
        );
    }

    /** 创建指向本地测试服务的客户端，避免访问真实 API。 */
    private DeepSeekClient createClient() {
        return new DeepSeekClient(
                apiUri,
                TEST_API_KEY,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Duration.ofSeconds(2)
        );
    }

    /** 创建所有测试共用的最小合法聊天请求。 */
    private ChatRequest createRequest() {
        return createRequest(0.7, 1024);
    }

    private ChatRequest createRequest(Double temperature, Integer maxTokens) {
        return new ChatRequest(
                "deepseek-v4-pro",
                List.of(new ChatMessage("user", "Hello")),
                temperature,
                false,
                maxTokens,
                "high",
                new ChatRequest.Thinking("enabled")
        );
    }

    private JsonNode parseCapturedRequest() {
        assertNotNull(capturedRequestBody, "本地服务器没有收到请求体");
        try {
            return new ObjectMapper().readTree(capturedRequestBody);
        } catch (IOException exception) {
            throw new AssertionError("请求体不是有效 JSON", exception);
        }
    }

    /**
     * 注册测试响应。每个测试只注册一次，因为每个测试都会创建新的服务实例。
     */
    private void registerResponse(int statusCode, String responseBody) {
        server.createContext(
                "/chat/completions",
                exchange -> writeResponse(exchange, statusCode, responseBody)
        );
    }

    /** 将指定状态码和 UTF-8 JSON 内容写入本地 HTTP 响应。 */
    private void writeResponse(
            HttpExchange exchange,
            int statusCode,
            String responseBody
    ) throws IOException {
        capturedRequestBody = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );

        byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=UTF-8"
        );
        exchange.sendResponseHeaders(statusCode, bodyBytes.length);
        exchange.getResponseBody().write(bodyBytes);
        exchange.close();
    }
}
