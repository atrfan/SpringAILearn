package com.foxmimi.experiment;

/**
 * 评价结果记录，由 {@link ExperimentEvaluator} 产出。
 * <p>
 * {@code null} 值表示该维度不适用或无法评判（如 API 调用失败）。
 */
public record EvaluationOutcome(
        /** 任务是否完成（语义层面，如分类是否命中标准答案）。 */
        Boolean taskSuccess,
        /** 输出格式是否满足约束（如数量、长度、唯一性）。 */
        Boolean formatValid,
        /** 是否存在事实错误（当前始终为 null，需人工填充）。 */
        Boolean factError
) {}
