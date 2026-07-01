package com.foxmimi.springaichat.service;

import com.foxmimi.springaichat.exception.UpstreamResponseException;
import com.foxmimi.springaichat.model.ChatResponse;
import com.foxmimi.springaichat.model.RenderedPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * 聊天服务
 * <p>
 * 封装与 AI 模型的交互逻辑，调用 Spring AI 的 {@link ChatClient} 发送用户消息，
 * 并将模型响应转换为统一的 {@link ChatResponse} 格式返回。
 * 同时记录请求耗时和 Token 使用量等元信息。
 * </p>
 */
@Service
public class SummarizeService {

    /** 当模型元数据中未提供模型名称时使用的默认值 */
    private static final String UNKNOWN_MODEL = "unknown";

    private final ChatClient chatClient;

    /**
     * 通过构造器注入 ChatClient
     *
     * @param chatClient 在 {@link com.foxmimi.springaichat.config.OpenAIConfig} 中配置的 ChatClient Bean
     */
    public SummarizeService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 发送用户消息并获取 AI 模型的响应
     * <p>
     * 采用阻塞式调用（同步），等待模型生成完整回复后一次性返回。
     * 同时统计调用耗时，并提取 Token 用量等元数据。
     * </p>
     *
     * @param prompt
     * @return 包含模型回复内容、Token 用量和耗时的统一响应对象
     * @throws UpstreamResponseException 当模型服务未返回任何响应时抛出
     */
    public ChatResponse chat(RenderedPrompt prompt) {
        // 记录请求开始时间（纳秒级精度）
        long start = System.nanoTime();

        // 构建提示词并同步调用 AI 模型
        var springAiResponse = chatClient.prompt()
                .user(prompt.user())
                .system(prompt.system())
                .call()
                .chatResponse();

        // 计算请求耗时（转换为毫秒）
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 模型未返回响应时抛出异常
        if (springAiResponse == null) {
            throw new UpstreamResponseException("模型服务未返回响应");
        }

        // 将 Spring AI 的响应对象转换为自定义的统一响应格式
        return new ChatResponse(
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
