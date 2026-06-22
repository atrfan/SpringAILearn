package com.foxmimi.experiment;

/**
 * 一个实验用例（测试样本），定义了对 LLM 的一次调用配置及其评价方式。
 * <p>
 * 包含系统提示词、用户提示词，以及对应的评价标准 {@link EvaluationCriteria}。
 */
public record ExperimentCase(
        /** 用例唯一标识，如 "classification-001"。 */
        String id,
        /** 任务类型，决定评测维度和评价策略。 */
        TaskType taskType,
        /** 系统级提示词（System Prompt），设定模型角色与行为约束。 */
        String systemPrompt,
        /** 用户级提示词（User Prompt），即具体的任务指令与输入。 */
        String userPrompt,
        /** 评价标准，定义如何判断模型输出的正确性与质量。 */
        EvaluationCriteria criteria
) {
    /** 紧凑构造器：校验所有必填字段不可为 null 或空白。 */
    public ExperimentCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("实验案例 ID 不能为空");
        }
        if (taskType == null) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("System Prompt 不能为空");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("User Prompt 不能为空");
        }
        if (criteria == null) {
            throw new IllegalArgumentException("评价标准不能为空");
        }
    }
}
