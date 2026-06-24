package com.foxmimi.experiment.day05;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 第 05 天分析报告生成器。
 * <p>
 * 将分析结果渲染为 Markdown 格式，包含：
 * <ol>
 *   <li>整体汇总 —— 总调用数、成功率、总 Token</li>
 *   <li>按任务类型分组表格 —— 每类的平均 Token、延迟和成功率</li>
 *   <li>按输入长度排序表格 —— 展示长度对 Token 和延迟的影响</li>
 *   <li>成本估算 —— 基于定价配置计算</li>
 *   <li>解释边界 —— 提醒读者正确理解数据含义</li>
 * </ol>
 */
public final class Day05AnalysisReportWriter {

    /**
     * 将分析结果写入 Markdown 报告文件。
     *
     * @param reportPath    输出路径
     * @param taskTypeSummaries 按类型汇总
     * @param sortedByLength    按输入长度排序的结果
     * @param costResult        成本统计
     */
    public void write(
            Path reportPath,
            List<Day05Analyzer.TaskTypeSummary> taskTypeSummaries,
            List<Day05TaskResult> sortedByLength,
            Day05Analyzer.CostResult costResult
    ) throws IOException {
        if (reportPath == null) {
            throw new IllegalArgumentException("报告路径不能为空");
        }

        Path parent = reportPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(
                reportPath,
                render(taskTypeSummaries, sortedByLength, costResult),
                StandardCharsets.UTF_8
        );
    }

    /** 将全部分析数据渲染为完整的 Markdown 字符串。 */
    private String render(
            List<Day05Analyzer.TaskTypeSummary> summaries,
            List<Day05TaskResult> sortedByLength,
            Day05Analyzer.CostResult cost
    ) {
        StringBuilder report = new StringBuilder();

        // 标题和元信息
        report.append("# Day05 任务覆盖与成本分析报告\n\n")
                .append("- 生成时间：").append(Instant.now()).append('\n')
                .append("- 任务总数：").append(
                        summaries.stream().mapToInt(Day05Analyzer.TaskTypeSummary::totalTasks).sum()
                ).append('\n')
                .append("- API 成功数：").append(
                        summaries.stream().mapToInt(Day05Analyzer.TaskTypeSummary::apiSuccessfulTasks).sum()
                ).append('\n')
                .append("- 覆盖任务类型：").append(summaries.size()).append(" 种\n\n");

        // 按任务类型分组表格
        appendTaskTypeTable(report, summaries);

        // 按输入长度排序表格
        appendLengthAnalysisTable(report, sortedByLength);

        // 成本估算
        appendCostSection(report, cost);

        // 解释边界
        appendDisclaimer(report);

        return report.toString();
    }

    /** 追加按任务类型分组的汇总表格。 */
    private static void appendTaskTypeTable(
            StringBuilder report,
            List<Day05Analyzer.TaskTypeSummary> summaries
    ) {
        report.append("## 按任务类型汇总\n\n")
                .append("| 任务类型 | 任务数 | API成功 | 任务成功率 | "
                        + "平均输入字符 | 平均输入Token | 平均输出Token | 平均延迟(ms) |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");

        for (Day05Analyzer.TaskTypeSummary s : summaries) {
            report.append('|').append(s.taskType())
                    .append('|').append(s.totalTasks())
                    .append('|').append(s.apiSuccessfulTasks())
                    .append('|').append(formatRate(s.taskSuccessRate()))
                    .append('|').append(formatNumber(s.avgInputCharLength()))
                    .append('|').append(formatNumber(s.avgPromptTokens()))
                    .append('|').append(formatNumber(s.avgCompletionTokens()))
                    .append('|').append(formatNumber(s.avgLatencyMillis()))
                    .append("|\n");
        }
        report.append('\n');
    }

    /** 追加按输入长度排序的明细表格，用于分析长度影响。 */
    private static void appendLengthAnalysisTable(
            StringBuilder report,
            List<Day05TaskResult> sortedByLength
    ) {
        report.append("## 按输入长度排序（分析长度对 Token 和延迟的影响）\n\n")
                .append("| # | 任务ID | 任务类型 | 输入字符 | 输入Token | 输出Token | "
                        + "延迟(ms) | Token/字符 |\n")
                .append("|---:|---|---|---:|---:|---:|---:|---:|\n");

        for (int i = 0; i < sortedByLength.size(); i++) {
            Day05TaskResult r = sortedByLength.get(i);
            report.append('|').append(i + 1)
                    .append('|').append(r.taskId())
                    .append('|').append(r.taskType())
                    .append('|').append(r.inputCharLength())
                    .append('|').append(value(r.promptTokens()))
                    .append('|').append(value(r.completionTokens()))
                    .append('|').append(value(r.latencyMillis()))
                    .append('|').append(formatNumber(r.tokenPerChar()))
                    .append("|\n");
        }
        report.append('\n');
    }

    /** 追加成本估算段落。 */
    private static void appendCostSection(
            StringBuilder report,
            Day05Analyzer.CostResult cost
    ) {
        report.append("## 成本估算\n\n")
                .append("| 项目 | 值 |\n")
                .append("|---|---|\n")
                .append("| 模型 | ").append(cost.model()).append(" |\n")
                .append("| 价格日期 | ").append(cost.priceDate()).append(" |\n")
                .append("| 价格来源 | ").append(cost.priceSourceUrl()).append(" |\n")
                .append("| 输入单价（$/M tokens） | ")
                .append(formatCostPrice(cost.inputPricePerM())).append(" |\n")
                .append("| 输出单价（$/M tokens） | ")
                .append(formatCostPrice(cost.outputPricePerM())).append(" |\n")
                .append("| 成功调用数 | ").append(cost.successfulCalls()).append(" |\n")
                .append("| 总输入 Token | ").append(cost.totalPromptTokens()).append(" |\n")
                .append("| 总输出 Token | ").append(cost.totalCompletionTokens()).append(" |\n")
                .append("| 总 Token | ").append(cost.totalTokens()).append(" |\n")
                .append("| 输入成本 | $").append(formatCost(cost.inputCost())).append(" |\n")
                .append("| 输出成本 | $").append(formatCost(cost.outputCost())).append(" |\n")
                .append("| **总成本** | **$").append(formatCost(cost.totalCost()))
                .append("** |\n\n");
    }

    /** 追加解释边界，提醒读者正确理解数据含义。 */
    private static void appendDisclaimer(StringBuilder report) {
        report.append("## 解释边界\n\n")
                .append("- 每条任务只执行 1 次，结果不具备统计显著性，不能外推为稳定性能结论。\n")
                .append("- 任务成功率基于关键词或格式检查，不能替代人工语义评估。\n")
                .append("- 事实错误率需要人工核查原始回答后补录。\n")
                .append("- Token/字符比值受文本语言、tokenizer 和系统提示词共同影响。\n")
                .append("- 成本基于快照价格计算，实际账单可能与估算存在偏差。\n")
                .append("- 延迟受网络状况、服务端负载和模型推理时间共同影响，单次测量波动较大。\n");
    }

    // ==================== 格式化工具方法 ====================

    /** 将比率（0~1）格式化为百分比，null 显示 "N/A"。 */
    private static String formatRate(Double value) {
        return value == null ? "N/A" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    /** 将数值格式化为两位小数，null 显示 "N/A"。 */
    private static String formatNumber(Number value) {
        return value == null ? "N/A" : String.format(Locale.ROOT, "%.2f", value.doubleValue());
    }

    /** 将成本价格格式化为四位小数。 */
    private static String formatCostPrice(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** 将成本金额格式化为六位小数。 */
    private static String formatCost(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    /** 将任意值转为字符串，null 显示 "N/A"。 */
    private static String value(Object value) {
        return value == null ? "N/A" : value.toString();
    }
}
