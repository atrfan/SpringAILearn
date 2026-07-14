package com.foxmimi.springaichat.model.request;

/**
 * 信息抽取请求体
 * <p>
 * 与 {@link SummarizeRequest} 同构：record 隐式为 final 无法通过继承复用，
 * 因此独立声明与 {@code /api/extract} 一致的请求结构。
 * </p>
 *
 * @param message 待抽取信息的文本内容
 */
public record ExtractRequest(String message) {
}
