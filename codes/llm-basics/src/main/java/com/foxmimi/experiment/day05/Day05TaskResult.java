package com.foxmimi.experiment.day05;

import com.foxmimi.experiment.TaskType;

import java.time.Instant;

/**
 * 第 05 天单次任务调用的完整结果记录。
 * <p>
 * 与 Day 04 的 {@link com.foxmimi.experiment.ExperimentResult} 相比，
 * 增加了输入字符数和输入 Token 长度比两个字段，用于分析输入长度对 Token 和延迟的影响。
 * <p>
 * 每条任务只执行 1 次（Day 05 关注广度覆盖，不关注统计显著性），
 * 因此没有 repeatIndex 字段。
 */
public record Day05TaskResult(
        /** 任务唯一标识，如 "fact-qa-001"，关联 {@link com.foxmimi.experiment.ExperimentCase#id()}。 */
        String taskId,
        /** 任务类型，用于分组汇总和对比分析。 */
        TaskType taskType,
        /** 使用的模型名称，如 "deepseek-v4-pro"。 */
        String model,
        /** 温度参数，Day 05 固定为 0.0。 */
        Double temperature,
        /** 最大输出 Token 数。 */
        Integer maxOutputTokens,
        /** 用户提示词的字符数（Java char 数量，非 code point），用于分析长度影响。 */
        int inputCharLength,
        /** 输入消息消耗的 Token 数。 */
        Integer promptTokens,
        /** 输出消息消耗的 Token 数。 */
        Integer completionTokens,
        /** 总 Token 数（输入 + 输出）。 */
        Integer totalTokens,
        /** API 调用耗时（毫秒），包含网络、排队和生成时间。 */
        Long latencyMillis,
        /** API 调用是否成功（HTTP 层面）。 */
        boolean apiSuccess,
        /** 任务是否成功（语义层面）；API 失败时为 null。 */
        Boolean taskSuccess,
        /** 输出格式是否满足约束；API 失败时为 null。 */
        Boolean formatValid,
        /** 是否发现事实错误；当前始终为 null，需人工填充。 */
        Boolean factError,
        /** 模型返回的原始文本回答；API 失败时为 null。 */
        String answer,
        /** 错误类型（如 "AUTHENTICATION"、"RATE_LIMIT"）；API 成功时为 null。 */
        String errorType,
        /** 结果记录的时间戳。 */
        Instant timestamp
) {
    /** 紧凑构造器：校验必填字段不可为 null 或非法值。 */
    public Day05TaskResult {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务 ID 不能为空");
        }
        if (taskType == null) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (inputCharLength < 0) {
            throw new IllegalArgumentException("输入字符数不能为负数");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("时间戳不能为空");
        }
    }

    /**
     * 计算输入 Token 与输入字符数的比值。
     * <p>
     * 该比值反映 tokenizer 对当前文本的编码效率：
     * 中文文本通常 1-2 个字符对应 1 Token，英文约 4 个字符对应 1 Token。
     * 如果 promptTokens 为 null（API 失败），返回 null。
     */
    public Double tokenPerChar() {
        if (promptTokens == null || inputCharLength == 0) {
            return null;
        }
        return (double) promptTokens / inputCharLength;
    }
}
