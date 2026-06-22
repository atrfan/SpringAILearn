package com.foxmimi.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Markdown 格式实验报告生成器。
 * <p>
 * 将 {@link ExperimentSummary} 列表渲染为带表格和对比分析的可读报告。
 */
public final class MarkdownExperimentReportWriter {

    /**
     * 将实验汇总写入 Markdown 文件。
     *
     * @param reportPath 输出路径
     * @param summaries  实验汇总列表
     */
    public void write(
            Path reportPath,
            List<ExperimentSummary> summaries
    ) throws IOException {
        if (reportPath == null) {
            throw new IllegalArgumentException("报告路径不能为空");
        }
        if (summaries == null || summaries.isEmpty()) {
            throw new IllegalArgumentException("实验汇总不能为空");
        }

        Path parent = reportPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                reportPath,
                render(summaries),
                StandardCharsets.UTF_8
        );
    }

    /** 将汇总数据渲染为完整的 Markdown 字符串（含表格和自动分析）。 */
    String render(List<ExperimentSummary> summaries) {
        StringBuilder report = new StringBuilder();
        report.append("# Day04 参数实验报告\n\n")
                .append("- 生成时间：").append(Instant.now()).append("\n")
                .append("- 总调用数：")
                .append(summaries.stream().mapToInt(ExperimentSummary::totalCalls).sum())
                .append("\n\n")
                .append("## 汇总结果\n\n")
                .append("| 实验组 | 任务 | Temperature | API成功率 | 任务成功率 | 格式合规率 | "
                        + "事实错误率 | 平均延迟(ms) | P50延迟(ms) | 平均输入Token | 平均输出Token | "
                        + "不同回答数 | 一致率 |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");

        for (ExperimentSummary summary : summaries) {
            report.append('|').append(summary.groupId())
                    .append('|').append(summary.taskType())
                    .append('|').append(formatNumber(summary.temperature()))
                    .append('|').append(formatRate(summary.apiSuccessRate()))
                    .append('|').append(formatRate(summary.taskSuccessRate()))
                    .append('|').append(formatRate(summary.formatValidRate()))
                    .append('|').append(formatRate(summary.factErrorRate()))
                    .append('|').append(formatNumber(summary.averageLatencyMillis()))
                    .append('|').append(value(summary.medianLatencyMillis()))
                    .append('|').append(formatNumber(summary.averagePromptTokens()))
                    .append('|').append(formatNumber(summary.averageCompletionTokens()))
                    .append('|').append(summary.distinctAnswerCount())
                    .append('|').append(formatRate(summary.consistencyRate()))
                    .append("|\n");
        }

        report.append("\n## 自动分析\n\n");
        appendComparisons(report, summaries);
        report.append("\n## 解释边界\n\n")
                .append("- 一致率表示组内众数答案占比，不等于事实准确率。\n")
                .append("- 幻觉探测的任务成功率只表示回答是否明确表达不确定性。\n")
                .append("- 事实错误率不能由关键词可靠判断，必须人工核查原始回答后补录。\n")
                .append("- 当前结论只适用于本次模型、Prompt、参数和小样本，不应外推为普遍规律。\n");
        return report.toString();
    }

    /** 追加低温和高温组的自动对比分析段落。 */
    private static void appendComparisons(
            StringBuilder report,
            List<ExperimentSummary> summaries
    ) {
        Map<String, ExperimentSummary> byId = summaries.stream()
                .collect(Collectors.toMap(ExperimentSummary::groupId, Function.identity()));
        appendPair(report, byId, "A-classification-t0", "B-classification-t07",
                "分类任务");
        appendPair(report, byId, "C-open-generation-t0", "D-open-generation-t1",
                "开放生成任务");
        appendPair(report, byId, "E-hallucination-t0", "F-hallucination-t07",
                "幻觉探测任务");
    }

    /** 追加一组对比（低温 vs 高温）的文本行。 */
    private static void appendPair(
            StringBuilder report,
            Map<String, ExperimentSummary> summaries,
            String lowId,
            String highId,
            String label
    ) {
        ExperimentSummary low = summaries.get(lowId);
        ExperimentSummary high = summaries.get(highId);
        if (low == null || high == null) {
            return;
        }
        report.append("- ").append(label).append("：Temperature ")
                .append(formatNumber(low.temperature())).append(" 的一致率为 ")
                .append(formatRate(low.consistencyRate())).append("，Temperature ")
                .append(formatNumber(high.temperature())).append(" 的一致率为 ")
                .append(formatRate(high.consistencyRate())).append("；任务成功率分别为 ")
                .append(formatRate(low.taskSuccessRate())).append(" 和 ")
                .append(formatRate(high.taskSuccessRate())).append("。\n");
    }

    /** 将比率（0~1）格式化为百分比字符串，null 显示 "N/A"。 */
    private static String formatRate(Double value) {
        return value == null ? "N/A" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    /** 将数值格式化为两位小数，null 显示 "N/A"。 */
    private static String formatNumber(Number value) {
        return value == null ? "N/A" : String.format(Locale.ROOT, "%.2f", value.doubleValue());
    }

    /** 将任意值转为字符串，null 显示 "N/A"。 */
    private static String value(Object value) {
        return value == null ? "N/A" : value.toString();
    }
}
