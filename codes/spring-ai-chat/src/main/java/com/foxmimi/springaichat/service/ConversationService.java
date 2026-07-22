package com.foxmimi.springaichat.service;

import com.foxmimi.springaichat.exception.UpstreamResponseException;
import com.foxmimi.springaichat.model.request.ConversationRequest;
import com.foxmimi.springaichat.model.response.ConversationResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Optional;


@Service
public class ConversationService {

    /**
     * 当模型元数据中未提供模型名称时使用的默认值
     */
    private static final String UNKNOWN_MODEL = "unknown";


    private final ChatClient chatClient;

    private final ChatMemory chatMemory;

    public ConversationService(@Qualifier("conversationChatClient") ChatClient chatClient, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
    }


    public ConversationResponse chat(ConversationRequest request) {
        // 记录请求开始时间（纳秒级精度）
        long start = System.nanoTime();

        // 构建提示词并同步调用 AI 模型
        var springAiResponse = chatClient.prompt()
                .user(request.message())
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, request.conversationId());
                }).system("你是一个有帮助的 AI 助手，专注于提供准确和有用的信息。请根据用户的输入提供清晰、简洁的回答。")
                .call()
                .chatResponse();

        // 计算请求耗时（转换为毫秒）
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 模型未返回响应时抛出异常
        if (springAiResponse == null) {
            throw new UpstreamResponseException("模型服务未返回响应");
        }

        // 将 Spring AI 的响应对象转换为自定义的统一响应格式
        return new ConversationResponse(
                request.conversationId(),
                modelOf(springAiResponse.getMetadata()),
                // 安全提取回复文本，任一环节为 null 则返回空字符串
                Optional.ofNullable(springAiResponse.getResult())
                        .map(result -> result.getOutput())
                        .map(output -> output.getText())
                        .orElse(""),
                promptTokensOf(springAiResponse.getMetadata()),
                completionTokensOf(springAiResponse.getMetadata()),
                totalTokensOf(springAiResponse.getMetadata()),
                elapsedMillis
        );
    }

    /**
     * 清除指定会话的历史记忆，用于结束或重置一条会话。
     * <p>
     * 直接委托 {@link ChatMemory#clear(String)}。注入的 {@link ChatMemory} 与
     * {@code conversationChatClient} 上 advisor 读写的是同一个 bean（全局仅此一个），
     * 因此清除的正是 {@code /api/conversation} 端点使用的那份记忆。
     * 操作幂等：清除一个从未出现过的 conversationId 也不会报错。
     * </p>
     */
    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
    }

    /**
     * 从响应元数据中提取模型名称，若不可用则返回默认值 "unknown"
     */
    private String modelOf(ChatResponseMetadata metadata) {
        if (metadata == null || !org.springframework.util.StringUtils.hasText(metadata.getModel())) {
            return UNKNOWN_MODEL;
        }
        return metadata.getModel();
    }

    /**
     * 提取提示词（输入）消耗的 Token 数量，不可用时返回 0
     */
    private int promptTokensOf(ChatResponseMetadata metadata) {
        Usage usage = usageOf(metadata);
        return usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
    }

    /**
     * 提取回复（输出）消耗的 Token 数量，不可用时返回 0
     */
    private int completionTokensOf(ChatResponseMetadata metadata) {
        Usage usage = usageOf(metadata);
        return usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    }

    /**
     * 提取总 Token 消耗数量（输入 + 输出），不可用时返回 0
     */
    private int totalTokensOf(ChatResponseMetadata metadata) {
        Usage usage = usageOf(metadata);
        return usage == null || usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
    }

    /**
     * 从响应元数据中安全获取 Usage 对象
     */
    private Usage usageOf(ChatResponseMetadata metadata) {
        return metadata == null ? null : metadata.getUsage();
    }
}
