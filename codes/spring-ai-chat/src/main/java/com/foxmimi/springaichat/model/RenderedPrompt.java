package com.foxmimi.springaichat.model;

/**
 * 渲染后的 Prompt
 * <p>
 * {@link com.foxmimi.springaichat.service.PromptTemplateService#render} 的返回结果：
 * {@link #system} 直接来自模板骨架，{@link #user} 是占位符替换完成后的最终文本。
 * 二者可分别作为 System / User 消息交给模型。
 * </p>
 * <p>
 * 之所以返回纯字符串而非直接返回 Spring AI 的 Prompt 对象，是为了让渲染逻辑保持为纯函数，
 * 便于在不调用模型的情况下做单元测试（见第三周 Day20 计划）。
 * </p>
 *
 * @param templateId 使用的模板 id
 * @param version    使用的模板版本号（便于日志与回归对照）
 * @param model      模板声明的适用模型
 * @param system     System 约束文本
 * @param user       渲染后的 User 文本
 */
public record RenderedPrompt(
        String templateId,
        int version,
        String model,
        String system,
        String user
) {
}
