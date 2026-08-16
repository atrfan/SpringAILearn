package com.foxmimi.springaichat.tool.service;

import com.foxmimi.springaichat.exception.UpstreamResponseException;
import com.foxmimi.springaichat.tool.controller.ToolResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 工具调用服务
 * <p>
 * 用 {@code @Qualifier("toolChatClient")} 精确点名注入带工具的
 * {@link ChatClient}（week05 决定 3 经验：避免拼写错误导致装配静默失效，
 * 如把 {@code conversation} 拼成 {@code conservation}）。
 * </p>
 * <p>
 * 单轮工具问答：模型自主决策是否调用工具并填参，应用层不做关键词路由。
 * </p>
 */
@Service
public class ToolService {

    private static final String UNKNOWN_MODEL = "unknown";

    private final ChatClient chatClient;

    public ToolService(@Qualifier("toolChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 单轮工具问答
     *
     * @param message 用户消息
     * @return 含模型回复、token 用量、耗时的 {@link ToolResponse}
     * @throws UpstreamResponseException 模型未返回响应时
     */
    public ToolResponse chat(String message) {
        long start = System.nanoTime();

        var springAiResponse = chatClient.prompt()
                .user(message)
                .call()
                .chatResponse();

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        if (springAiResponse == null) {
            throw new UpstreamResponseException("模型服务未返回响应");
        }

        return new ToolResponse(
                modelOf(springAiResponse.getMetadata()),
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

    private String modelOf(ChatResponseMetadata metadata) {
        if (metadata == null || !org.springframework.util.StringUtils.hasText(metadata.getModel())) {
            return UNKNOWN_MODEL;
        }
        return metadata.getModel();
    }

    private int promptTokensOf(ChatResponseMetadata metadata) {
        Usage usage = usageOf(metadata);
        return usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
    }

    private int completionTokensOf(ChatResponseMetadata metadata) {
        Usage usage = usageOf(metadata);
        return usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
    }

    private int totalTokensOf(ChatResponseMetadata metadata) {
        Usage usage = usageOf(metadata);
        return usage == null || usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
    }

    private Usage usageOf(ChatResponseMetadata metadata) {
        return metadata == null ? null : metadata.getUsage();
    }
}
