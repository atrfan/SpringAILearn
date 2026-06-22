package com.foxmimi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模型返回的 assistant 消息。
 *
 * <p>该类型与请求使用的 {@link ChatMessage} 分离，因为响应可能包含
 * reasoning_content 等仅由服务端产生的字段。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistantMessage(
        String role,
        String content,
        @JsonProperty("reasoning_content") String reasoningContent
) {}
