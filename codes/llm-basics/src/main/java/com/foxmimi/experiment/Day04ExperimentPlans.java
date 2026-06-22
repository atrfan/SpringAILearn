package com.foxmimi.experiment;

import java.util.List;

/**
 * 第 04 天实验计划工厂。
 * <p>
 * 为三种任务（分类、开放生成、幻觉探测）各生成低温和高温两组
 * {@link ExperimentPlan}，用于对比 Temperature 参数的影响。
 */
public final class Day04ExperimentPlans {

    /** 每组实验计划的重复执行次数。 */
    private static final int REPETITIONS = 5;

    /** 工具类，私有构造器防止实例化。 */
    private Day04ExperimentPlans() {}

    /**
     * 生成全部 6 组实验计划。
     *
     * @param model          模型名称
     * @param maxOutputTokens 最大输出 token 数
     * @return 实验计划列表
     */
    public static List<ExperimentPlan> create(String model, int maxOutputTokens) {
        return List.of(
                new ExperimentPlan(
                        "A-classification-t0",
                        Day04ExperimentCatalog.classificationCase(),
                        model, 0.0, maxOutputTokens, REPETITIONS),
                new ExperimentPlan(
                        "B-classification-t07",
                        Day04ExperimentCatalog.classificationCase(),
                        model, 0.7, maxOutputTokens, REPETITIONS),
                new ExperimentPlan(
                        "C-open-generation-t0",
                        Day04ExperimentCatalog.openGenerationCase(),
                        model, 0.0, maxOutputTokens, REPETITIONS),
                new ExperimentPlan(
                        "D-open-generation-t1",
                        Day04ExperimentCatalog.openGenerationCase(),
                        model, 1.0, maxOutputTokens, REPETITIONS),
                new ExperimentPlan(
                        "E-hallucination-t0",
                        Day04ExperimentCatalog.hallucinationProbeCase(),
                        model, 0.0, maxOutputTokens, REPETITIONS),
                new ExperimentPlan(
                        "F-hallucination-t07",
                        Day04ExperimentCatalog.hallucinationProbeCase(),
                        model, 0.7, maxOutputTokens, REPETITIONS)
        );
    }
}
