package com.foxmimi.springaichat.model.domain;

import java.util.List;

/**
 * Prompt 模板定义
 * <p>
 * 与 {@code resources/prompts/} 下单个 YAML 模板文件一一对应，是从文件加载出来的只读数据载体。
 * 固定骨架（{@link #system}、{@link #user}）由开发者控制，{@link #variables} 列出运行时需要绑定的变量名；
 * 用户输入只会流入 {@link #user} 中的占位符（如 {@code {content}}），不会进入 {@link #system} 约束，
 * 以此落地"变量边界"，降低提示注入风险。
 * </p>
 *
 * @param id        模板唯一标识，同时作为渲染时的查找 key
 * @param version   模板版本号，便于回归对照
 * @param purpose   模板用途说明
 * @param model     适用/验证过的模型名称
 * @param variables 运行时需要绑定的变量名列表
 * @param system    System 约束（固定骨架）
 * @param user      User 模板正文（含占位符，交给 Spring AI PromptTemplate 渲染）
 */
public record PromptTemplateDefinition(
        String id,
        int version,
        String purpose,
        String model,
        List<String> variables,
        String system,
        String user
) {
}
