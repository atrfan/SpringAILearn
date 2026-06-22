package com.foxmimi.model;

/**
 * 发送给模型的消息，只描述请求需要的角色和文本内容。
 */
public record ChatMessage(
        String role,
        String content
) {}
