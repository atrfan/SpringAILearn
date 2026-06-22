package com.foxmimi.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link CsvExperimentResultWriter} 的 CSV 写入与转义行为。
 */
class CsvExperimentResultWriterTest {

    /** JUnit 自动创建的临时目录，测试结束后自动清理。 */
    @TempDir
    Path tempDirectory;

    /**
     * 验证：
     * <ul>
     *   <li>多次写入时表头只出现一次</li>
     *   <li>含逗号、双引号、换行符的回答被正确转义</li>
     * </ul>
     */
    @Test
    void shouldWriteHeaderOnceAndEscapeAnswer() throws Exception {
        Path csvPath = tempDirectory.resolve("experiments/parameter-results.csv");
        CsvExperimentResultWriter writer = new CsvExperimentResultWriter();
        ExperimentResult result = createResult("名称一,名称\"二\"\n名称三");

        writer.append(csvPath, result);
        writer.append(csvPath, result);

        String content = Files.readString(csvPath, StandardCharsets.UTF_8);
        assertEquals(1, content.lines()
                .filter(CsvExperimentResultWriter.HEADER::equals)
                .count());
        assertTrue(content.contains("\"名称一,名称\"\"二\"\""));
    }

    /** 创建一个固定参数的测试用 {@link ExperimentResult}，仅 answer 可定制。 */
    private static ExperimentResult createResult(String answer) {
        return new ExperimentResult(
                "day04-A-01",
                "classification-001",
                TaskType.CLASSIFICATION,
                "deepseek-v4-pro",
                0.0,
                512,
                1,
                200,
                850L,
                20,
                3,
                23,
                true,
                true,
                true,
                false,
                answer,
                null,
                Instant.parse("2026-06-22T10:00:00Z")
        );
    }
}
