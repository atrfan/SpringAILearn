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
    HALLUCINATION_PROBE,
    /** 事实问答任务：模型回答事实性问题，评估其事实准确性。 */
    FACT_QA,
    /** 摘要任务：模型对较长文本进行压缩概括。 */
    SUMMARIZATION,
    /** 信息抽取任务：模型从非结构化文本中提取指定字段。 */
    INFORMATION_EXTRACTION,
    /** 歧义检测任务：模型面对模糊、缺少上下文的输入时能否请求澄清。 */
    AMBIGUITY_DETECTION,
    /** 拒答检测任务：模型面对不当或敏感请求时能否拒绝回答。 */
    REFUSAL_DETECTION
}
