package com.foxmimi.springaichat.controller;

import com.foxmimi.springaichat.model.ChatResponse;
import com.foxmimi.springaichat.service.MyChatService;
import com.foxmimi.springaichat.service.UpstreamResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.SocketTimeoutException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ChatController} 单元测试
 * <p>
 * 使用 MockMvc 进行 Web 层独立测试，不启动完整的 Spring 容器。
 * 通过 Mockito 模拟 {@link MyChatService}，验证接口的请求校验、正常响应和异常处理逻辑。
 * </p>
 */
class ChatControllerTest {

    private MyChatService chatService;
    private MockMvc mockMvc;

    /**
     * 每个测试前初始化 MockMvc 环境
     * 独立装配 ChatController 和 GlobalExceptionHandler，无需 Spring 容器
     */
    @BeforeEach
    void setUp() {
        // 用 Mockito 的 mock() 创建一个 MyChatService 的假对象，不启动 Spring 容器、不连接真实 AI 服务。
        // 通过 when(...).thenReturn(...) 或 thenThrow(...) 精确控制它的行为，从而单独测试 Controller 层的逻辑（参数校验、异常映射等）。
        chatService = mock(MyChatService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chatService))
                // 注册全局异常处理器，确保异常能被正确捕获和转换
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 测试正常请求：验证返回的聊天响应包含正确的模型名称、内容和 Token 信息
     */
    @Test
    void returnsChatResponseForValidRequest() throws Exception {
        when(chatService.chat("who are you"))
                .thenReturn(new ChatResponse("deepseek-chat", "I am DeepSeek.", 3, 4, 7, 120L));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"who are you"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("deepseek-chat"))
                .andExpect(jsonPath("$.content").value("I am DeepSeek."))
                .andExpect(jsonPath("$.totalTokens").value(7));
    }

    /**
     * 测试空消息校验：message 仅包含空白字符时应返回 400 Bad Request
     */
    @Test
    void rejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("message 不能为空"));
    }

    /**
     * 测试非法 JSON：请求体为畸形 JSON 时应返回 400 Bad Request
     */
    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /**
     * 测试 AI 服务瞬时故障：当模型服务抛出 TransientAiException 时，
     * 应返回 503 Service Unavailable 并携带 UPSTREAM_UNAVAILABLE 错误码
     */
    @Test
    void mapsTransientAiFailureToServiceUnavailable() throws Exception {
        when(chatService.chat("hello")).thenThrow(new TransientAiException("rate limited"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"hello"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    /**
     * 测试 AI 服务非瞬时故障：当模型服务抛出 NonTransientAiException 时，
     * 应返回 502 Bad Gateway 并携带 UPSTREAM_ERROR 错误码
     */
    @Test
    void mapsNonTransientAiExceptionToBadGateway() throws Exception {
        when(chatService.chat("hello")).thenThrow(new NonTransientAiException("auth failed"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"hello"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"));
    }

    /**
     * 测试上游响应异常：当服务层抛出 UpstreamResponseException 时，
     * 应返回 502 Bad Gateway 并携带 UPSTREAM_ERROR 错误码
     */
    @Test
    void mapsUpstreamResponseExceptionToBadGateway() throws Exception {
        when(chatService.chat("hello")).thenThrow(new UpstreamResponseException("模型服务未返回响应"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"hello"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"));
    }

    /**
     * 测试超时场景：当 TransientAiException 的根因为 SocketTimeoutException 时，
     * 应返回 504 Gateway Timeout 并携带 UPSTREAM_TIMEOUT 错误码
     */
    @Test
    void mapsTimeoutToGatewayTimeout() throws Exception {
        when(chatService.chat("hello")).thenThrow(
                new TransientAiException("timeout", new SocketTimeoutException("Read timed out")));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"hello"}
                                """))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("UPSTREAM_TIMEOUT"));
    }

    /**
     * 测试未预期异常：当服务层抛出非已知类型的 RuntimeException 时，
     * 应返回 500 Internal Server Error 并携带 INTERNAL_ERROR 错误码
     */
    @Test
    void mapsUnexpectedExceptionToInternalServerError() throws Exception {
        when(chatService.chat("hello")).thenThrow(new RuntimeException("unexpected"));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"hello"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    /**
     * 测试 null 请求体：当请求体为 null 时应返回 400 Bad Request
     */
    @Test
    void rejectsNullRequestBody() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
