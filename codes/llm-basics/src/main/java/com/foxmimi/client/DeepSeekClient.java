package com.foxmimi.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxmimi.model.ChatRequest;
import com.foxmimi.model.ChatResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class DeepSeekClient {

    private static final URI API_URI =
            URI.create("https://api.deepseek.com/chat/completions");

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }

        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public CallResult chat(ChatRequest chatRequest)
            throws IOException, InterruptedException {

        String requestBody =
                objectMapper.writeValueAsString(chatRequest);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(API_URI)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody,
                        StandardCharsets.UTF_8
                ))
                .build();

        long start = System.nanoTime();

        HttpResponse<String> httpResponse = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        long elapsedMillis =
                (System.nanoTime() - start) / 1_000_000;

        if (httpResponse.statusCode() < 200
                || httpResponse.statusCode() >= 300) {
            throw new IllegalStateException(
                    "API 调用失败，HTTP 状态码："
                            + httpResponse.statusCode()
                            + "，响应："
                            + httpResponse.body()
            );
        }

        ChatResponse response = objectMapper.readValue(
                httpResponse.body(),
                ChatResponse.class
        );

        return new CallResult(
                httpResponse.statusCode(),
                elapsedMillis,
                response
        );
    }

    public record CallResult(
            int statusCode,
            long elapsedMillis,
            ChatResponse response
    ) {}
}