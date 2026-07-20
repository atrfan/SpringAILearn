package com.foxmimi.springaichat.config;

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
}
