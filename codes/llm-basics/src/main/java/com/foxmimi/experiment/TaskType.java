package com.foxmimi.experiment;

/**
 * LLM 任务的类型枚举，用于区分不同的评测场景。
 */
public enum TaskType {
    /** 分类任务：模型需从给定标签集合中选出一个正确标签。 */
    CLASSIFICATION,
    /** 开放生成任务：模型需按约束条件自由生成文本（如命名、摘要）。 */
    OPEN_GENERATION,
    /** 幻觉探测任务：模型面对未知/虚构信息时能否承认不确定，而非编造内容。 */
    HALLUCINATION_PROBE
}
