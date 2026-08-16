package com.foxmimi.springaichat.tool.controller;

/**
 * 工具调用响应体
 * <p>
 * 复用 {@link com.foxmimi.springaichat.model.response.ConversationResponse}
 * 的 token 提取模式，但不带 {@code conversationId}（单轮工具端点无状态）。
 * </p>
 *
 * @param model            使用的 AI 模型名称
 * @param content          模型生成的回复内容
 * @param promptTokens     提示词（输入）消耗的 Token 数
 * @param completionTokens 回复（输出）消耗的 Token 数
 * @param totalTokens      总共消耗的 Token 数
 * @param elapsedMillis    本次请求的耗时（毫秒）
 */
public record ToolResponse(
        String model,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long elapsedMillis
) {
}
