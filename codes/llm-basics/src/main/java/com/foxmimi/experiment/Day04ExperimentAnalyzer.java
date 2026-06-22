package com.foxmimi.experiment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实验结果分析器，将原始 {@link ExperimentResult} 列表按实验组聚合为 {@link ExperimentSummary}。
 * <p>
 * 计算各维度的成功率、平均值、中位数和一致率。
 */
public final class Day04ExperimentAnalyzer {

    /**
     * 分析实验结果，按 experimentId 分组汇总。
     *
     * @param results 原始实验结果列表
     * @return 按实验组聚合的汇总列表
     */
    public List<ExperimentSummary> analyze(List<ExperimentResult> results) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("实验结果不能为空");
        }

        Map<String, List<ExperimentResult>> groups = results.stream()
                .collect(Collectors.groupingBy(
                        ExperimentResult::experimentId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return groups.values().stream()
                .map(this::summarize)
                .toList();
    }

    /** 将同一实验组的所有结果聚合为一个 {@link ExperimentSummary}。 */
    private ExperimentSummary summarize(List<ExperimentResult> group) {
        ExperimentResult first = group.getFirst();
        List<ExperimentResult> successful = group.stream()
                .filter(ExperimentResult::apiSuccess)
                .toList();
        List<String> answers = successful.stream()
                .map(ExperimentResult::answer)
                .map(ExperimentEvaluator::normalize)
                .filter(answer -> !answer.isEmpty())
                .toList();

        int evaluatedCalls = count(group, result -> result.taskSuccess() != null);
        int formatEvaluatedCalls = count(group, result -> result.formatValid() != null);
        int factEvaluatedCalls = count(group, result -> result.factError() != null);

        return new ExperimentSummary(
                first.experimentId(),
                first.taskType(),
                first.temperature(),
                group.size(),
                successful.size(),
                evaluatedCalls,
                count(group, result -> Boolean.TRUE.equals(result.taskSuccess())),
                formatEvaluatedCalls,
                count(group, result -> Boolean.TRUE.equals(result.formatValid())),
                factEvaluatedCalls,
                count(group, result -> Boolean.TRUE.equals(result.factError())),
                average(successful, ExperimentResult::latencyMillis),
                median(successful.stream().map(ExperimentResult::latencyMillis).toList()),
                average(successful, ExperimentResult::promptTokens),
                average(successful, ExperimentResult::completionTokens),
                average(successful, ExperimentResult::totalTokens),
                (int) answers.stream().distinct().count(),
                consistencyRate(answers)
        );
    }

    /** 统计满足条件的记录数。 */
    private static int count(
            List<ExperimentResult> results,
            java.util.function.Predicate<ExperimentResult> predicate
    ) {
        return (int) results.stream().filter(predicate).count();
    }

    /** 计算数值型字段的平均值，忽略 null；全部为 null 时返回 null。 */
    private static <N extends Number> Double average(
            List<ExperimentResult> results,
            Function<ExperimentResult, N> extractor
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

    /** 计算中位数，忽略 null；列表为空时返回 null。 */
    private static Long median(List<Long> values) {
        List<Long> sorted = values.stream()
                .filter(value -> value != null)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (sorted.isEmpty()) {
            return null;
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2.0);
    }

    /** 计算一致率 = 众数出现次数 / 总回答数；无回答时返回 null。 */
    private static Double consistencyRate(List<String> answers) {
        if (answers.isEmpty()) {
            return null;
        }
        Map<String, Long> frequencies = answers.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        long modeCount = frequencies.values().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0);
        return (double) modeCount / answers.size();
    }
}
