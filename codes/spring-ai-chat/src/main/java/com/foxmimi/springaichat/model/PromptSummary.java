package com.foxmimi.springaichat.model;

import java.util.List;

/**
 * Prompt 模板的只读元数据视图
 * <p>
 * 供 {@code GET /api/prompts} 列表端点使用，只暴露模板的治理元数据与变量契约，
 * 刻意不包含 {@code system}/{@code user} 正文——那是内部固定骨架，且可能含少样本敏感样本，
 * 不应通过公开端点泄露。
 * </p>
 *
 * @param id        模板唯一标识
 * @param version   模板版本号，便于回归对照
 * @param purpose   模板用途说明
 * @param model     适用/验证过的模型名称
 * @param variables 运行时需要绑定的变量名列表（模板对调用方的公开契约）
 */
public record PromptSummary(
        String id,
        int version,
        String purpose,
        String model,
        List<String> variables
) {
}
