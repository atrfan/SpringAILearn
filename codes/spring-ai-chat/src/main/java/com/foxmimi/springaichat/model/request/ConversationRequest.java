package com.foxmimi.springaichat.model.request;


/**
 * 聊天请求体
 *
 * @param message        用户发送的消息内容
 * @param conversationId 会话 ID，用于标识不同的聊天会话
 */
public record ConversationRequest(
         String message,
         String conversationId) {
}
