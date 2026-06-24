package com.foxmimi.springaichat.model;

/**
 * 聊天响应体
 *
 * @param model            使用的 AI 模型名称（如 deepseek-chat）
 * @param content          模型生成的回复内容
 * @param promptTokens     提示词（输入）消耗的 Token 数
 * @param completionTokens 回复（输出）消耗的 Token 数
 * @param totalTokens      总共消耗的 Token 数
 * @param elapsedMillis    本次请求的耗时（毫秒）
 */
public record ChatResponse(
        String model,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long elapsedMillis
) {
}
