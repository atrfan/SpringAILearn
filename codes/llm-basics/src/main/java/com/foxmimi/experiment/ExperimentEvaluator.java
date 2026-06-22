package com.foxmimi.experiment;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 实验评价器，根据 {@link EvaluationCriteria} 判断 LLM 回答的正确性。
 * <p>
 * 支持三种任务类型的评价：分类、开放生成、幻觉探测。
 */
public final class ExperimentEvaluator {

    /** 幻觉探测任务中用于识别模型表达不确定性的中文关键词。 */
    private static final List<String> UNCERTAINTY_MARKERS = List.of(
            "无法确认",
            "无法核实",
            "不能确认",
            "不确定",
            "缺乏可靠",
            "没有可靠",
            "未找到可靠"
    );

    /**
     * 评价 LLM 回答，返回包含任务成功、格式合规、事实错误的判定结果。
     *
     * @param experimentCase 实验用例（含评价标准）
     * @param answer         LLM 的原始回答
     * @return 评价结果
     */
    public EvaluationOutcome evaluate(ExperimentCase experimentCase, String answer) {
        if (experimentCase == null) {
            throw new IllegalArgumentException("实验案例不能为空");
        }
        String normalized = normalize(answer);
        if (normalized.isEmpty()) {
            return new EvaluationOutcome(false, false, null);
        }

        return switch (experimentCase.taskType()) {
            case CLASSIFICATION -> evaluateClassification(experimentCase, normalized);
            case OPEN_GENERATION -> evaluateOpenGeneration(experimentCase, normalized);
            case HALLUCINATION_PROBE -> evaluateHallucinationProbe(normalized);
        };
    }

    /** 分类任务评价：检查是否在允许标签内，以及是否匹配标准答案。 */
    private static EvaluationOutcome evaluateClassification(
            ExperimentCase experimentCase,
            String answer
    ) {
        EvaluationCriteria criteria = experimentCase.criteria();
        boolean formatValid = criteria.allowedLabels().contains(answer);
        boolean taskSuccess = formatValid && answer.equals(criteria.expectedAnswer());
        return new EvaluationOutcome(taskSuccess, formatValid, null);
    }

    /** 开放生成任务评价：检查输出条目数、每项长度及是否重复。 */
    private static EvaluationOutcome evaluateOpenGeneration(
            ExperimentCase experimentCase,
            String answer
    ) {
        EvaluationCriteria criteria = experimentCase.criteria();
        List<String> items = Arrays.stream(answer.split("\\R"))
                .map(ExperimentEvaluator::removeListPrefix)
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();

        boolean countValid = criteria.expectedItemCount() == null
                || items.size() == criteria.expectedItemCount();
        boolean lengthsValid = criteria.maxItemLength() == null
                || items.stream().allMatch(item ->
                item.codePointCount(0, item.length()) <= criteria.maxItemLength());
        boolean unique = items.stream().distinct().count() == items.size();
        boolean formatValid = countValid && lengthsValid && unique;
        return new EvaluationOutcome(formatValid, formatValid, null);
    }

    /** 幻觉探测任务评价：通过关键词判断模型是否表达了不确定性。 */
    private static EvaluationOutcome evaluateHallucinationProbe(String answer) {
        String lowerCaseAnswer = answer.toLowerCase(Locale.ROOT);
        boolean expressesUncertainty = UNCERTAINTY_MARKERS.stream()
                .anyMatch(lowerCaseAnswer::contains);

        // 事实错误不能通过关键词可靠判断，必须保留给人工核查。
        return new EvaluationOutcome(expressesUncertainty, true, null);
    }

    /** 去除首尾空白，null 返回空字符串。 */
    static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    /** 去除列表项前缀（如 "-", "1.", "•" 等）。 */
    private static String removeListPrefix(String value) {
        return value.replaceFirst("^\\s*(?:[-*•]|\\d+[.)、])\\s*", "");
    }
}
