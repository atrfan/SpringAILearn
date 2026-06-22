package com.foxmimi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        String id,
        String model,
        List<Choice> choices,
        Usage usage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            // 响应消息可能包含 reasoning_content，不能复用请求 DTO ChatMessage。
            AssistantMessage message,
            @JsonProperty("finish_reason") String finishReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(        // 该请求的用量信息
                                @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {}

    public String answer() {
        if (choices == null || choices.isEmpty()) {
            return "";
        }

        AssistantMessage message = choices.getFirst().message();
        return message == null || message.content() == null
                ? ""
                : message.content();
    }

    /**
     * 返回思考模式产生的推理内容；未启用思考模式或服务端未返回时为空字符串。
     */
    public String reasoningContent() {
        if (choices == null || choices.isEmpty()) {
            return "";
        }

        AssistantMessage message = choices.getFirst().message();
        return message == null || message.reasoningContent() == null
                ? ""
                : message.reasoningContent();
    }
}
