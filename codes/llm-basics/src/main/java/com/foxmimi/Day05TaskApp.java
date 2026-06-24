package com.foxmimi;

import com.foxmimi.client.DeepSeekClient;
import com.foxmimi.experiment.ExperimentCase;
import com.foxmimi.experiment.day05.Day05Analyzer;
import com.foxmimi.experiment.day05.Day05AnalysisReportWriter;
import com.foxmimi.experiment.day05.Day05CostConfig;
import com.foxmimi.experiment.day05.Day05TaskResult;
import com.foxmimi.experiment.day05.Day05TaskRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 第 05 天任务执行入口。
 * <p>
 * 目标：对 21 条覆盖 7 类任务的用例各执行 1 次 API 调用，
 * 收集 Token、延迟、成功率和成本数据，生成 CSV 和分析报告。
 * <p>
 * 环境变量：
 * <ul>
 *   <li>{@code DEEPSEEK_KEY}（必需）：API 密钥</li>
 *   <li>{@code EXPERIMENT_MAX_OUTPUT_TOKENS}（可选，默认 1024）：最大输出 Token</li>
 *   <li>{@code EXPERIMENT_DELAY_MILLIS}（可选，默认 1000）：调用间隔毫秒</li>
 * </ul>
 * <p>
 * 命令行参数：
 * <ul>
 *   <li>第一个参数（可选）：输出目录路径，默认 {@code experiments}</li>
 * </ul>
 */
public final class Day05TaskApp {

    /** 默认最大输出 Token 数。Day 05 涵盖摘要、生成等较长输出任务，因此设为 1024。 */
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1024;

    /** 默认调用间隔（毫秒），避免触发 API 限流。 */
    private static final long DEFAULT_DELAY_MILLIS = 1_000;

    /** Day 05 固定使用 temperature = 0，控制变量。 */
    private static final double TEMPERATURE = 0.0;

    /** 使用的模型名称。 */
    private static final String MODEL = "deepseek-v4-pro";

    private Day05TaskApp() {}

    public static void main(String[] args) throws Exception {
        // 1. 读取环境变量和配置
        String apiKey = requiredEnvironmentVariable("DEEPSEEK_KEY");
        int maxOutputTokens = positiveIntegerEnvironmentVariable(
                "EXPERIMENT_MAX_OUTPUT_TOKENS",
                DEFAULT_MAX_OUTPUT_TOKENS
        );
        long delayMillis = nonNegativeLongEnvironmentVariable(
                "EXPERIMENT_DELAY_MILLIS",
                DEFAULT_DELAY_MILLIS
        );

        // 2. 确定输出路径，拒绝覆盖已有结果
        Path outputDirectory = args.length == 0
                ? Path.of("experiments")
                : Path.of(args[0]);
        Path csvPath = outputDirectory.resolve("task-results.csv");
        Path reportPath = outputDirectory.resolve("day05-analysis.md");
        refuseToMixWithExistingResults(csvPath);

        System.out.println("=== Day 05 任务覆盖实验 ===");
        System.out.println("模型：" + MODEL);
        System.out.println("Temperature：" + TEMPERATURE);
        System.out.println("最大输出 Token：" + maxOutputTokens);
        System.out.println();

        // 3. 加载 21 条任务用例
        List<ExperimentCase> cases = com.foxmimi.experiment.day05.Day05TaskCatalog.cases();
        System.out.println("任务总数：" + cases.size());
        System.out.println();

        // 4. 执行所有任务
        Day05TaskRunner runner = new Day05TaskRunner(
                new DeepSeekClient(apiKey),
                Duration.ofMillis(delayMillis)
        );
        List<Day05TaskResult> results = runner.run(
                cases, MODEL, TEMPERATURE, maxOutputTokens, csvPath
        );

        // 5. 分析结果
        Day05Analyzer analyzer = new Day05Analyzer();
        List<Day05Analyzer.TaskTypeSummary> summaries = analyzer.analyzeByTaskType(results);
        List<Day05TaskResult> sortedByLength = analyzer.sortByInputLength(results);

        // 6. 计算成本
        //    价格需要实验前从供应商官方页面查询并填入。
        //    以下价格仅为占位值，运行前请替换为实际价格。
        Day05CostConfig costConfig = createCostConfig();
        Day05Analyzer.CostResult costResult = analyzer.calculateCost(results, costConfig);

        // 7. 生成分析报告
        new Day05AnalysisReportWriter().write(
                reportPath, summaries, sortedByLength, costResult
        );

        // 8. 输出汇总
        printSummary(results, costResult);
        System.out.println();
        System.out.println("CSV：" + csvPath.toAbsolutePath());
        System.out.println("报告：" + reportPath.toAbsolutePath());
    }

    /**
     * 创建定价配置。
     * <p>
     * <strong>运行前请从供应商官方定价页面获取最新价格并替换以下占位值。</strong>
     * DeepSeek 定价页面：<a href="https://api-docs.deepseek.com/zh-cn/quick_start/pricing">
     * https://api-docs.deepseek.com/zh-cn/quick_start/pricing</a>
     */
    private static Day05CostConfig createCostConfig() {
        // TODO: 运行前请替换为当天从官方页面获取的实际价格
        return new Day05CostConfig(
                MODEL,
                "2026-06-24",
                "https://api-docs.deepseek.com/zh-cn/quick_start/pricing",
                1,
                2
        );
    }

    /** 打印简要汇总到控制台。 */
    private static void printSummary(
            List<Day05TaskResult> results,
            Day05Analyzer.CostResult cost
    ) {
        long apiSuccess = results.stream().filter(Day05TaskResult::apiSuccess).count();
        long taskSuccess = results.stream()
                .filter(r -> Boolean.TRUE.equals(r.taskSuccess()))
                .count();
        int totalPrompt = results.stream()
                .filter(Day05TaskResult::apiSuccess)
                .mapToInt(r -> r.promptTokens() != null ? r.promptTokens() : 0)
                .sum();
        int totalCompletion = results.stream()
                .filter(Day05TaskResult::apiSuccess)
                .mapToInt(r -> r.completionTokens() != null ? r.completionTokens() : 0)
                .sum();

        System.out.println("=== 汇总 ===");
        System.out.println("总调用数：" + results.size());
        System.out.println("API 成功：" + apiSuccess + "/" + results.size());
        System.out.println("任务成功：" + taskSuccess + "/" + apiSuccess);
        System.out.println("总输入 Token：" + totalPrompt);
        System.out.println("总输出 Token：" + totalCompletion);
        System.out.println("总 Token：" + (totalPrompt + totalCompletion));
        System.out.printf(Locale.ROOT, "估算成本：$%.6f%n", cost.totalCost());
    }

    // ==================== 环境变量工具方法 ====================

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value;
    }

    private static int positiveIntegerEnvironmentVariable(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return parsed;
    }

    private static long nonNegativeLongEnvironmentVariable(String name, long defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(name + " 不能为负数");
        }
        return parsed;
    }

    /** 如果 CSV 文件已存在且非空，拒绝混入另一批数据。 */
    private static void refuseToMixWithExistingResults(Path csvPath) throws Exception {
        if (Files.exists(csvPath) && Files.size(csvPath) > 0) {
            throw new IllegalStateException(
                    "结果文件已存在，拒绝混入另一批实验数据：" + csvPath.toAbsolutePath()
            );
        }
    }
}
