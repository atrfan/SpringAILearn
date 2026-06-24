package com.foxmimi.springaichat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI 聊天应用启动类
 * <p>
 * 基于 Spring Boot + Spring AI 构建的 AI 聊天服务，
 * 通过 OpenAI 兼容接口对接大语言模型（如 DeepSeek）。
 * </p>
 */
@SpringBootApplication
public class SpringAiChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiChatApplication.class, args);
    }

}
