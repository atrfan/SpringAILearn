package com.foxmimi.experiment;

/**
 * 实验计划，定义一次实验的运行参数。
 * <p>
 * 包含实验用例、模型、温度、最大 Token 数及重复次数等配置。
 */
public record ExperimentPlan(
        /** 实验组编号，用于分组对比（如 "A-classification-t0"）。 */
        String groupId,
        /** 要执行的实验用例。 */
        ExperimentCase experimentCase,
        /** 使用的模型名称（如 "deepseek-chat"）。 */
        String model,
        /** 温度参数，取值范围 [0, 2]。 */
        double temperature,
        /** 最大输出 token 数。 */
        int maxOutputTokens,
        /** 重复执行次数，用于统计稳定性。 */
        int repetitions
) {
    /** 紧凑构造器：校验所有参数合法性。 */
    public ExperimentPlan {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("实验组编号不能为空");
        }
        if (experimentCase == null) {
            throw new IllegalArgumentException("实验案例不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature 必须是 0 到 2 之间的有限数");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens 必须大于 0");
        }
        if (repetitions <= 0) {
            throw new IllegalArgumentException("repetitions 必须大于 0");
        }
    }
}
