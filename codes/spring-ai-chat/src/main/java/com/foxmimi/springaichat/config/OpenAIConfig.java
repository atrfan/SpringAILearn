package com.foxmimi.springaichat.config;

import com.foxmimi.springaichat.tool.tool.WeatherService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * OpenAI 聊天客户端配置类
 * <p>
 * 负责创建并配置 Spring AI 的 {@link ChatClient} Bean，
 * 可在此设置系统提示词（System Prompt）、Advisor（顾问/拦截器）等。
 * </p>
 */
@Configuration
public class OpenAIConfig {

    /**
     * 创建 ChatClient Bean
     * <p>
     * ChatClient 是 Spring AI 提供的核心聊天客户端，
     * 支持设置系统提示词、挂载 Advisor 拦截器链等功能。
     * </p>
     *
     * @param openAiChatModel Spring AI 自动配置的 OpenAI 聊天模型实例
     * @return 配置完成的 ChatClient 实例
     */
    @Primary
    @Bean
    public ChatClient chatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                // 如需设置系统提示词，可使用 .defaultSystem("...") 方法
                // 如需启用聊天记忆，可使用 .defaultAdvisors(...) 添加 MessageChatMemoryAdvisor
                .build();
    }

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(12)
                .build();
    }

    @Bean
    public ChatClient conversationChatClient(OpenAiChatModel openAiChatModel,ChatMemory chatMemory) {
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 工具调用 ChatClient（本周新增）
     * <p>
     * 单轮工具问答（无状态），装配 {@link WeatherService} 工具供模型自主决策调用。
     * 不带 advisor（记忆 + 工具融合走 {@code conversationChatClient}，Day40 接入），
     * 不动 {@code chatClient} / {@code chatMemory} / {@code conversationChatClient}。
     * </p>
     *
     * @param openAiChatModel Spring AI 自动配置的 OpenAI 聊天模型实例（与其它 bean 共享）
     * @param weatherService  天气工具服务（{@link Tool @Tool} 标注方法由 Spring AI 扫描为工具 Schema）
     * @return 带工具的 ChatClient 实例
     */
    @Bean
    public ChatClient toolChatClient(OpenAiChatModel openAiChatModel, WeatherService weatherService) {
        return ChatClient.builder(openAiChatModel)
                .defaultTools(weatherService)
                .build();
    }
}
