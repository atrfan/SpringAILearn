package com.foxmimi.springaichat.service;

import com.foxmimi.springaichat.exception.UpstreamResponseException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MyChatService} 单元测试
 * <p>
 * 使用 Mockito 模拟 ChatClient，验证服务层对模型响应的解析和转换逻辑是否正确，
 * 包括正常响应映射和元数据缺失时的安全默认值处理。
 * </p>
 */
class MyChatServiceTest {

    // 使用深桩（RETURNS_DEEP_STUBS）模拟 ChatClient，支持链式调用如 chatClient.prompt().user().call()
    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final MyChatService chatService = new MyChatService(chatClient);

    /**
     * 测试正常场景：验证模型名称、回复内容、Token 用量和耗时均被正确映射
     */
    @Test
    void mapsModelResponseAndUsage() {
        // 构造包含完整元数据的模拟响应
        ChatResponse springAiResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("answer"))),
                ChatResponseMetadata.builder()
                        .model("deepseek-chat")
                        .usage(new DefaultUsage(3, 4, 7))
                        .build()
        );
        when(chatClient.prompt().user("question").call().chatResponse()).thenReturn(springAiResponse);

        var response = chatService.chat("question");

        // 验证模型名称
        assertThat(response.model()).isEqualTo("deepseek-chat");
        // 验证回复内容
        assertThat(response.content()).isEqualTo("answer");
        // 验证 Token 用量：输入 3 + 输出 4 = 总计 7
        assertThat(response.promptTokens()).isEqualTo(3);
        assertThat(response.completionTokens()).isEqualTo(4);
        assertThat(response.totalTokens()).isEqualTo(7);
        // 验证耗时为非负数
        assertThat(response.elapsedMillis()).isNotNegative();
    }

    /**
     * 测试边界场景：当元数据和生成结果为空时，验证服务返回安全的默认值
     */
    @Test
    void usesSafeDefaultsWhenMetadataAndGenerationAreMissing() {
        // 构造一个空的模拟响应（无 Generation、无 Metadata）
        ChatResponse springAiResponse = new ChatResponse(List.of());
        when(chatClient.prompt().user("question").call().chatResponse()).thenReturn(springAiResponse);

        var response = chatService.chat("question");

        // 模型名称默认为 "unknown"
        assertThat(response.model()).isEqualTo("unknown");
        // 回复内容为空字符串
        assertThat(response.content()).isEmpty();
        // 所有 Token 计数默认为 0
        assertThat(response.promptTokens()).isZero();
        assertThat(response.completionTokens()).isZero();
        assertThat(response.totalTokens()).isZero();
    }

    /**
     * 测试空响应场景：当模型服务返回 null 时，应抛出 UpstreamResponseException
     */
    @Test
    void throwsUpstreamResponseExceptionWhenResponseIsNull() {
        when(chatClient.prompt().user("question").call().chatResponse()).thenReturn(null);

        assertThatThrownBy(() -> chatService.chat("question"))
                .isInstanceOf(UpstreamResponseException.class)
                .hasMessage("模型服务未返回响应");
    }
}
