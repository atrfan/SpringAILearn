package com.foxmimi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)      // 该注解来自 Jackson 库，作用是在序列化 Java 对象为 JSON 时，忽略值为 null 的字段，不将其包含在输出的 JSON 字符串中。
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        boolean stream,

        @JsonProperty("max_tokens")
        Integer maxTokens,

        @JsonProperty("reasoning_effort")
        String reasoningEffort,

        Thinking thinking
) {
    public ChatRequest {
        if (temperature != null
                && (!Double.isFinite(temperature)
                || temperature < 0
                || temperature > 2)) {
            throw new IllegalArgumentException("temperature 必须是 0 到 2 之间的有限数");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须大于 0");
        }
    }

    public record Thinking(String type) {}
}
