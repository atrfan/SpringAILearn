package com.foxmimi.springaichat.model;

/**
 * 摘要请求体
 * <p>
 * record 隐式为 final，无法通过继承 {@link ChatRequest} 复用，
 * 因此这里独立声明同名字段，保持与 {@code /api/summarize} 的请求结构一致。
 * </p>
 *
 * @param message 待摘要的文本内容
 */
public record SummarizeRequest(String message) {
}
