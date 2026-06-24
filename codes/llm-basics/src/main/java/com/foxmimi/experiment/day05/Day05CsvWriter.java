package com.foxmimi.experiment.day05;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 第 05 天任务结果的 CSV 写入器。
 * <p>
 * 与 Day 04 的 CSV 相比，增加了 {@code input_char_length} 字段，
 * 用于分析输入长度对 Token 消耗和延迟的影响。
 * <p>
 * 输出文件：{@code experiments/task-results.csv}
 */
public final class Day05CsvWriter {

    /**
     * CSV 表头，与 {@link Day05TaskResult} 各组件一一对应。
     * 新增 {@code input_char_length} 字段位于 prompt_tokens 之前。
     */
    public static final String HEADER = String.join(",",
            "task_id",
            "task_type",
            "model",
            "temperature",
            "max_output_tokens",
            "input_char_length",
            "prompt_tokens",
            "completion_tokens",
            "total_tokens",
            "latency_ms",
            "api_success",
            "task_success",
            "format_valid",
            "fact_error",
            "answer",
            "error_type",
            "timestamp"
    );

    /**
     * 将一条任务结果追加写入 CSV 文件。
     * <p>
     * 如果文件不存在或为空，自动写入表头行。
     *
     * @param csvPath CSV 输出路径
     * @param result  待写入的任务结果
     */
    public void append(Path csvPath, Day05TaskResult result) throws IOException {
        if (csvPath == null) {
            throw new IllegalArgumentException("CSV 路径不能为空");
        }
        if (result == null) {
            throw new IllegalArgumentException("任务结果不能为空");
        }

        Path parent = csvPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        boolean needsHeader = Files.notExists(csvPath) || Files.size(csvPath) == 0;
        StringBuilder content = new StringBuilder();
        if (needsHeader) {
            content.append(HEADER).append(System.lineSeparator());
        }
        content.append(toCsvRow(result)).append(System.lineSeparator());

        Files.writeString(
                csvPath,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    /**
     * 将 {@link Day05TaskResult} 转换为 CSV 行字符串。
     * <p>
     * 按照 HEADER 定义的列顺序提取各字段，null 值转为空字符串，
     * 包含逗号、引号或换行符的字段用双引号包裹并转义。
     */
    String toCsvRow(Day05TaskResult result) {
        return Arrays.asList(
                        result.taskId(),
                        value(result.taskType()),
                        result.model(),
                        value(result.temperature()),
                        value(result.maxOutputTokens()),
                        value(result.inputCharLength()),
                        value(result.promptTokens()),
                        value(result.completionTokens()),
                        value(result.totalTokens()),
                        value(result.latencyMillis()),
                        value(result.apiSuccess()),
                        value(result.taskSuccess()),
                        value(result.formatValid()),
                        value(result.factError()),
                        result.answer(),
                        result.errorType(),
                        value(result.timestamp())
                ).stream()
                .map(Day05CsvWriter::escape)
                .collect(Collectors.joining(","));
    }

    /** 将任意值转为字符串，null 返回空字符串。 */
    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * CSV 字段转义：
     * <ul>
     *   <li>包含逗号、引号或换行符时，整个字段用双引号包裹</li>
     *   <li>字段内的双引号替换为两个双引号（" → ""）</li>
     * </ul>
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean requiresQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!requiresQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
