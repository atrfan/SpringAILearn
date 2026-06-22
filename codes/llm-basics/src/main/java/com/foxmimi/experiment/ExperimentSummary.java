package com.foxmimi.experiment;

/**
 * 一组实验结果（相同实验组）的汇总统计。
 * <p>
 * 由 {@link Day04ExperimentAnalyzer} 聚合生成，包含各维度的成功/失败计数与比率。
 */
public record ExperimentSummary(
        /** 实验组编号，同组共享（如 "A-classification-t0"）。 */
        String groupId,
        /** 任务类型。 */
        TaskType taskType,
        /** 温度参数。 */
        double temperature,
        /** 总调用次数。 */
        int totalCalls,
        /** API 调用成功的次数。 */
        int apiSuccessfulCalls,
        /** 成功执行评价的调用次数（即有 taskSuccess 判定）。 */
        int evaluatedCalls,
        /** 任务判定为成功的次数。 */
        int taskSuccessfulCalls,
        /** 执行格式评价的调用次数。 */
        int formatEvaluatedCalls,
        /** 格式判定为合规的次数。 */
        int formatValidCalls,
        /** 执行事实核查的调用次数。 */
        int factEvaluatedCalls,
        /** 发现事实错误的次数。 */
        int factErrorCalls,
        /** 平均延迟（毫秒）。 */
        Double averageLatencyMillis,
        /** 延迟中位数（毫秒）。 */
        Long medianLatencyMillis,
        /** 平均输入 token 数。 */
        Double averagePromptTokens,
        /** 平均输出 token 数。 */
        Double averageCompletionTokens,
        /** 平均总 token 数。 */
        Double averageTotalTokens,
        /** 去重后的不同回答数。 */
        int distinctAnswerCount,
        /** 一致率 = 众数回答出现次数 / 总回答数。 */
        Double consistencyRate
) {
    /** API 层面成功率。 */
    public double apiSuccessRate() {
        return rate(apiSuccessfulCalls, totalCalls);
    }

    /** 任务层面成功率；无可评价调用时返回 null。 */
    public Double taskSuccessRate() {
        return evaluatedCalls == 0 ? null : rate(taskSuccessfulCalls, evaluatedCalls);
    }

    /** 格式合规率；未执行格式评价时返回 null。 */
    public Double formatValidRate() {
        return formatEvaluatedCalls == 0 ? null : rate(formatValidCalls, formatEvaluatedCalls);
    }

    /** 事实错误率；未执行事实核查时返回 null。 */
    public Double factErrorRate() {
        return factEvaluatedCalls == 0 ? null : rate(factErrorCalls, factEvaluatedCalls);
    }

    /** 计算比率，分母为 0 时返回 0。 */
    private static double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
