package com.foxmimi.springaichat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * OpenAI 聊天集成测试
 * <p>
 * 使用 {@link SpringBootTest} 启动完整的 Spring 容器，
 * 验证通过 {@link com.foxmimi.springaichat.config.OpenAIConfig} 配置的 ChatClient
 * 能否正常调用 AI 模型并获取响应。
 * </p>
 * <p>注意：此测试需要配置有效的 API Key 和网络连接，属于真实调用测试。</p>
 */
@Tag("integration")
@SpringBootTest
public class OpenAIChatTest {

    private final ChatClient chatClient;

    /**
     * 通过构造器注入 Spring 容器中配置好的 ChatClient Bean
     */
    @Autowired
    public OpenAIChatTest(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 测试基本的聊天对话功能
     * <p>
     * 采用阻塞式（同步）调用方式，等待模型生成完整回复后一次性返回。
     * 如果 OpenAIConfig 中配置了系统提示词，模型的回复应体现该角色设定。
     * </p>
     */
    @Test
    public void chatTest(){
        // 发送用户消息 "who are you"，同步等待模型回复
        String whoAreYou = chatClient.prompt().user("who are you").call().content();
        // 打印模型的完整回复内容
        System.out.println(whoAreYou);
    }

}
