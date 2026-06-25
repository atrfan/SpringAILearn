package com.foxmimi.springaichat;

import com.foxmimi.springaichat.model.ChatRequest;
import com.foxmimi.springaichat.model.ChatResponse;
import com.foxmimi.springaichat.model.ErrorResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * 同步端点集成测试
 * <p>
 * 启动完整 Spring 容器，通过 RestTemplate 调用真实 /api/chat 端点。
 * 需要有效的 DEEPSEEK_KEY 环境变量和网络连接。
 * </p>
 * <p>运行方式：mvn test -Dgroups=integration -Dspring.ai.openai.api-key=sk-xxx</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatIntegrationTest {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 测试正常聊天请求：验证端到端响应包含有效的模型名称、内容和 Token 信息
     */
    @Test
    void syncEndpointReturnsValidChatResponse() {
        ChatRequest request = new ChatRequest("hello");
        String url = "http://localhost:" + port + "/api/chat";

        ResponseEntity<ChatResponse> response = restTemplate.postForEntity(
                url, request, ChatResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().model()).isNotBlank();
        assertThat(response.getBody().content()).isNotBlank();
        assertThat(response.getBody().totalTokens()).isGreaterThan(0);
        assertThat(response.getBody().elapsedMillis()).isNotNegative();
    }

    /**
     * 测试空消息校验：集成环境下验证 Controller 层的参数校验逻辑
     */
    @Test
    void syncEndpointRejectsBlankMessage() {
        ChatRequest request = new ChatRequest("   ");
        String url = "http://localhost:" + port + "/api/chat";

        assertThatThrownBy(() ->
                restTemplate.postForEntity(url, request, ErrorResponse.class))
                .isInstanceOfSatisfying(
                        HttpClientErrorException.BadRequest.class,
                        exception -> {
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(HttpStatus.BAD_REQUEST);
                            assertThat(exception.getResponseBodyAsString())
                                    .contains("INVALID_REQUEST");
                        });
    }
}
