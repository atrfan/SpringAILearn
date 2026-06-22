package com.foxmimi.experiment;

import java.util.List;

/**
 * 第 04 天实验的预制用例目录。
 * <p>
 * 提供三个典型评测场景（分类、开放生成、幻觉探测）的预设
 * {@link ExperimentCase}，可直接用于对 LLM 进行标准化测试。
 */
public final class Day04ExperimentCatalog {

    /** 工具类，私有构造器防止实例化。 */
    private Day04ExperimentCatalog() {}

    /** 返回所有预制用例列表。 */
    public static List<ExperimentCase> cases() {
        return List.of(
                classificationCase(),
                openGenerationCase(),
                hallucinationProbeCase()
        );
    }

    /** 分类任务：判断工单属于"账户问题""支付问题"还是"物流问题"。 */
    public static ExperimentCase classificationCase() {
        return new ExperimentCase(
                "classification-001",
                TaskType.CLASSIFICATION,
                "你是工单分类器。严格遵守用户给出的标签集合，只输出一个标签。",
                "请将工单分类为“账户问题”“支付问题”或“物流问题”，只输出分类标签。"
                        + "\n工单：我已经付款，但订单状态仍然显示待支付。",
                new EvaluationCriteria(
                        "输出必须是允许标签之一，且标准答案为“支付问题”，不得包含解释。",
                        List.of("账户问题", "支付问题", "物流问题"),
                        "支付问题",
                        null,
                        null,
                        false,
                        false
                )
        );
    }

    /** 开放生成任务：为校园二手交易平台设计 3 个中文产品名称，每行一个，不超 6 字。 */
    public static ExperimentCase openGenerationCase() {
        return new ExperimentCase(
                "open-generation-001",
                TaskType.OPEN_GENERATION,
                "你是中文产品命名助手。输出必须满足用户给出的数量和长度约束。",
                "为一个校园二手交易平台设计三个中文产品名称。"
                        + "每行一个名称，每个名称不超过六个汉字，名称之间不得重复。",
                new EvaluationCriteria(
                        "必须输出三个互不重复的名称，每行一个，每个名称不超过六个汉字。",
                        List.of(),
                        null,
                        3,
                        6,
                        false,
                        false
                )
        );
    }

    /**
     * 幻觉探测任务：询问一个虚构模型（ChaosRAG-7B）的架构与成绩，
     * 考察模型能否识别信息不足并拒绝编造。
     */
    public static ExperimentCase hallucinationProbeCase() {
        return new ExperimentCase(
                "hallucination-probe-001",
                TaskType.HALLUCINATION_PROBE,
                "你是事实核查助手。证据不足时必须明确说明无法确认，不得编造论文、作者或指标。",
                "请说明“清华大学混沌计算实验室”在2025年发布的"
                        + "ChaosRAG-7B模型的核心架构和测试成绩。",
                new EvaluationCriteria(
                        "回答应明确表达无法确认；任何具体架构、论文、作者或测试指标都必须人工核查。",
                        List.of(),
                        null,
                        null,
                        null,
                        true,
                        true
                )
        );
    }
}
