package com.foxmimi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DeepSeek Chat Completions API 的响应 DTO。
 *
 * <p>包含模型标识、回答选项列表和 Token 用量信息。
 * 使用 {@link com.fasterxml.jackson.annotation.JsonIgnoreProperties} 忽略未知字段，
 * 以兼容 API 可能新增的非关键响应属性。</p>
 *
 * @param id      服务端生成的响应 ID
 * @param model   实际使用的模型名称
 * @param choices 回答选项列表，通常只包含一个元素
 * @param usage   Token 用量统计
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        String id,
        String model,
        List<Choice> choices,
        Usage usage
) {
    /**
     * 单个回答选项，包含序号、助手消息和结束原因。
     *
     * @param index      选项序号，通常为 0
     * @param message    助手消息；响应可能包含 reasoning_content，因此不复用 ChatMessage
     * @param finishReason 回答结束原因，如 "stop"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            AssistantMessage message,
            @JsonProperty("finish_reason") String finishReason
    ) {}

    /**
     * Token 用量统计，包含输入、输出和总计 Token 数。
     *
     * @param promptTokens     输入部分消耗的 Token 数
     * @param completionTokens 输出部分消耗的 Token 数
     * @param totalTokens      总消耗 Token 数，应等于输入与输出之和
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens
    ) {}

    /**
     * 提取首个回答选项的文本内容；无回答时返回空字符串。
     */
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
