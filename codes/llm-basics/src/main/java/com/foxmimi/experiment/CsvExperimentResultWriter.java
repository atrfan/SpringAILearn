package com.foxmimi.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 实验结果 CSV 写入器。
 * <p>
 * 将 {@link ExperimentResult} 追加写入 CSV 文件。若文件不存在或为空，
 * 自动写入表头行。负责字段转义（逗号、引号、换行符）与 null 值处理。
 */
public final class CsvExperimentResultWriter {

    /** CSV 表头，与 {@link ExperimentResult} 各组件一一对应。 */
    public static final String HEADER = String.join(",",
            "experiment_id",
            "task_id",
            "task_type",
            "model",
            "temperature",
            "max_output_tokens",
            "repeat_index",
            "http_status",
            "latency_ms",
            "prompt_tokens",
            "completion_tokens",
            "total_tokens",
            "api_success",
            "task_success",
            "format_valid",
            "fact_error",
            "answer",
            "error_type",
            "timestamp"
    );

    /**
     * 将一条实验结果追加写入 CSV 文件。
     * <p>
     * 如果文件不存在则自动创建，如果文件为空则先写入表头。
     *
     * @param csvPath CSV 输出路径
     * @param result  待写入的实验结果
     */
    public void append(Path csvPath, ExperimentResult result) throws IOException {
        if (csvPath == null) {
            throw new IllegalArgumentException("CSV 路径不能为空");
        }
        if (result == null) {
            throw new IllegalArgumentException("实验结果不能为空");
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
     * 将 {@link ExperimentResult} 转换为 CSV 行字符串。
     * <p>
     * 按照 HEADER 定义的列顺序提取各字段，null 值转为空字符串，
     * 最终对所有字段执行 CSV 转义后以逗号连接。
     */
    String toCsvRow(ExperimentResult result) {
        return Arrays.asList(
                        result.experimentId(),
                        result.taskId(),
                        value(result.taskType()),
                        result.model(),
                        value(result.temperature()),
                        value(result.maxOutputTokens()),
                        value(result.repeatIndex()),
                        value(result.httpStatus()),
                        value(result.latencyMillis()),
                        value(result.promptTokens()),
                        value(result.completionTokens()),
                        value(result.totalTokens()),
                        value(result.apiSuccess()),
                        value(result.taskSuccess()),
                        value(result.formatValid()),
                        value(result.factError()),
                        result.answer(),
                        result.errorType(),
                        value(result.timestamp())
                ).stream()
                .map(CsvExperimentResultWriter::escape)
                .collect(Collectors.joining(","));
    }

    /** 将任意值转为字符串，null 返回空字符串。 */
    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 对 CSV 字段值进行转义：
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
