package com.foxmimi.client;

import com.foxmimi.model.ChatRequest;

/**
 * LLM 聊天客户端的统一抽象。
 *
 * <p>所有实现（{@link DeepSeekClient}、{@link RetryingChatClient} 等）
 * 都通过该接口提供一致的调用入口，方便上层解耦具体实现。</p>
 */
public interface LlmChatClient {

    /**
     * 发送一次聊天请求并返回调用结果。
     *
     * @param chatRequest 聊天请求对象
     * @return 包含状态码、用量信息和模型响应的调用结果
     * @throws com.foxmimi.exception.LlmClientException 请求失败时抛出
     */
    DeepSeekClient.CallResult chat(ChatRequest chatRequest);
}
