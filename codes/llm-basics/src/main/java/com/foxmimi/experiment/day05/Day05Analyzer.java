package com.foxmimi.experiment.day05;

import com.foxmimi.experiment.TaskType;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 第 05 天任务结果分析器。
 * <p>
 * 提供两种分析视角：
 * <ol>
 *   <li>按任务类型汇总（{@link #analyzeByTaskType}）—— 每类任务的平均 Token、延迟、成功率</li>
 *   <li>按输入长度排序（{@link #sortByInputLength}）—— 分析输入长度对 Token 和延迟的影响</li>
 * </ol>
 * <p>
 * 结合 {@link Day05CostConfig} 可估算实验的 API 调用成本。
 */
public final class Day05Analyzer {

    /**
     * 按任务类型分组汇总。
     * <p>
     * 使用 {@link TaskType#ordinal()} 保持枚举声明顺序，
     * 使报告中的类型排列与代码定义一致。
     *
     * @param results 全部任务结果
     * @return 按类型排序的汇总列表
     */
    public List<TaskTypeSummary> analyzeByTaskType(List<Day05TaskResult> results) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("任务结果不能为空");
        }

        Map<TaskType, List<Day05TaskResult>> groups = results.stream()
                .collect(Collectors.groupingBy(Day05TaskResult::taskType));

        return groups.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getKey().ordinal()))
                .map(entry -> summarize(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 按输入字符数从短到长排序，用于分析长度对 Token 和延迟的影响。
     *
     * @param results 全部任务结果
     * @return 按输入长度升序排列的结果列表
     */
    public List<Day05TaskResult> sortByInputLength(List<Day05TaskResult> results) {
        return results.stream()
                .sorted(Comparator.comparingInt(Day05TaskResult::inputCharLength))
                .toList();
    }

    /**
     * 使用定价配置计算实验总成本。
     *
     * @param results 全部任务结果
     * @param cost    定价配置
     * @return 成本统计结果
     */
    public CostResult calculateCost(
            List<Day05TaskResult> results,
            Day05CostConfig cost
    ) {
        if (cost == null) {
            throw new IllegalArgumentException("定价配置不能为空");
        }

        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int successfulCalls = 0;

        for (Day05TaskResult result : results) {
            if (result.apiSuccess() && result.promptTokens() != null) {
                totalPromptTokens += result.promptTokens();
                totalCompletionTokens += result.completionTokens() != null
                        ? result.completionTokens() : 0;
                successfulCalls++;
            }
        }

        double totalCost = cost.calculateCost(totalPromptTokens, totalCompletionTokens);

        return new CostResult(
                cost.model(),
                cost.priceDate(),
                cost.priceSourceUrl(),
                cost.inputPricePerM(),
                cost.outputPricePerM(),
                successfulCalls,
                totalPromptTokens,
                totalCompletionTokens,
                totalPromptTokens + totalCompletionTokens,
                totalPromptTokens * cost.inputPricePerM() / 1_000_000.0,
                totalCompletionTokens * cost.outputPricePerM() / 1_000_000.0,
                totalCost
        );
    }

    /** 汇总单个任务类型的统计指标。 */
    private TaskTypeSummary summarize(TaskType taskType, List<Day05TaskResult> group) {
        List<Day05TaskResult> successful = group.stream()
                .filter(Day05TaskResult::apiSuccess)
                .toList();

        int evaluatedCount = (int) successful.stream()
                .filter(r -> r.taskSuccess() != null)
                .count();
        int taskSuccessCount = (int) successful.stream()
                .filter(r -> Boolean.TRUE.equals(r.taskSuccess()))
                .count();

        return new TaskTypeSummary(
                taskType,
                group.size(),
                successful.size(),
                evaluatedCount,
                taskSuccessCount,
                average(successful, Day05TaskResult::inputCharLength),
                average(successful, Day05TaskResult::promptTokens),
                average(successful, Day05TaskResult::completionTokens),
                average(successful, Day05TaskResult::totalTokens),
                average(successful, Day05TaskResult::latencyMillis)
        );
    }

    /** 计算数值型字段的平均值，全部为 null 时返回 null。 */
    private static <N extends Number> Double average(
            List<Day05TaskResult> results,
            java.util.function.Function<Day05TaskResult, N> extractor
    ) {
        return results.stream()
                .map(extractor)
                .filter(value -> value != null)
                .mapToDouble(Number::doubleValue)
                .average()
                .stream()
                .boxed()
                .findFirst()
                .orElse(null);
    }

    // ==================== 内部 record 定义 ====================

    /**
     * 单个任务类型的汇总统计。
     *
     * @param taskType               任务类型
     * @param totalTasks             该类型的总任务数
     * @param apiSuccessfulTasks     API 调用成功的任务数
     * @param evaluatedTasks         已执行评价的任务数
     * @param taskSuccessfulTasks    任务判定为成功的数量
     * @param avgInputCharLength     平均输入字符数
     * @param avgPromptTokens        平均输入 Token
     * @param avgCompletionTokens    平均输出 Token
     * @param avgTotalTokens         平均总 Token
     * @param avgLatencyMillis       平均延迟（毫秒）
     */
    public record TaskTypeSummary(
            TaskType taskType,
            int totalTasks,
            int apiSuccessfulTasks,
            int evaluatedTasks,
            int taskSuccessfulTasks,
            Double avgInputCharLength,
            Double avgPromptTokens,
            Double avgCompletionTokens,
            Double avgTotalTokens,
            Double avgLatencyMillis
    ) {
        /** 任务成功率；无可评价调用时返回 null。 */
        public Double taskSuccessRate() {
            return evaluatedTasks == 0 ? null : (double) taskSuccessfulTasks / evaluatedTasks;
        }
    }

    /**
     * 成本统计结果。
     *
     * @param model               模型名称
     * @param priceDate           价格快照日期
     * @param priceSourceUrl      价格来源 URL
     * @param inputPricePerM      输入 Token 每百万美元价格
     * @param outputPricePerM     输出 Token 每百万美元价格
     * @param successfulCalls     成功调用次数
     * @param totalPromptTokens   总输入 Token
     * @param totalCompletionTokens 总输出 Token
     * @param totalTokens         总 Token
     * @param inputCost           输入成本（美元）
     * @param outputCost          输出成本（美元）
     * @param totalCost           总成本（美元）
     */
    public record CostResult(
            String model,
            String priceDate,
            String priceSourceUrl,
            double inputPricePerM,
            double outputPricePerM,
            int successfulCalls,
            int totalPromptTokens,
            int totalCompletionTokens,
            int totalTokens,
            double inputCost,
            double outputCost,
            double totalCost
    ) {}
}
