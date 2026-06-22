package com.foxmimi;

import com.foxmimi.client.DeepSeekClient;
import com.foxmimi.experiment.Day04ExperimentAnalyzer;
import com.foxmimi.experiment.Day04ExperimentPlans;
import com.foxmimi.experiment.Day04ExperimentRunner;
import com.foxmimi.experiment.ExperimentPlan;
import com.foxmimi.experiment.ExperimentResult;
import com.foxmimi.experiment.ExperimentSummary;
import com.foxmimi.experiment.MarkdownExperimentReportWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class Day04ExperimentApp {

    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 256;
    private static final long DEFAULT_DELAY_MILLIS = 1_000;

    private Day04ExperimentApp() {}

    public static void main(String[] args) throws Exception {
        String apiKey = requiredEnvironmentVariable("DEEPSEEK_KEY");
        String model = "deepseek-v4-pro";
        int maxOutputTokens = positiveIntegerEnvironmentVariable(
                "EXPERIMENT_MAX_OUTPUT_TOKENS",
                DEFAULT_MAX_OUTPUT_TOKENS
        );
        long delayMillis = nonNegativeLongEnvironmentVariable(
                "EXPERIMENT_DELAY_MILLIS",
                DEFAULT_DELAY_MILLIS
        );

        Path outputDirectory = args.length == 0
                ? Path.of("experiments")
                : Path.of(args[0]);
        Path csvPath = outputDirectory.resolve("parameter-results.csv");
        Path reportPath = outputDirectory.resolve("day04-analysis.md");
        refuseToMixWithExistingResults(csvPath);

        List<ExperimentPlan> plans = Day04ExperimentPlans.create(
                model,
                maxOutputTokens
        );
        Day04ExperimentRunner runner = new Day04ExperimentRunner(
                new DeepSeekClient(apiKey),
                Duration.ofMillis(delayMillis)
        );
        List<ExperimentResult> results = runner.run(plans, csvPath);
        List<ExperimentSummary> summaries = new Day04ExperimentAnalyzer()
                .analyze(results);
        new MarkdownExperimentReportWriter().write(reportPath, summaries);

        System.out.println("实验调用数：" + results.size());
        System.out.println("CSV：" + csvPath.toAbsolutePath());
        System.out.println("报告：" + reportPath.toAbsolutePath());
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value;
    }

    private static int positiveIntegerEnvironmentVariable(
            String name,
            int defaultValue
    ) {
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

    private static long nonNegativeLongEnvironmentVariable(
            String name,
            long defaultValue
    ) {
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

    private static void refuseToMixWithExistingResults(Path csvPath) throws Exception {
        if (Files.exists(csvPath) && Files.size(csvPath) > 0) {
            throw new IllegalStateException(
                    "结果文件已存在，拒绝混入另一批实验数据：" + csvPath.toAbsolutePath()
            );
        }
    }
}
