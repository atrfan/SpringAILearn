package com.foxmimi.springaichat.model;

/**
 * 聊天请求体
 *
 * @param message 用户发送的消息内容
 */
public record ChatRequest(String message) {
}
