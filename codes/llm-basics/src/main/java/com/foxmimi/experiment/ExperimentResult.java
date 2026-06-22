package com.foxmimi.experiment;

import java.time.Instant;

/**
 * 一次 LLM 调用的完整实验结果记录。
 * <p>
 * 包含实验标识、模型参数、调用性能指标、业务成功判定及原始回答。
 * 使用 {@code record} 确保不可变性，紧凑构造器提供参数校验。
 */
public record ExperimentResult(
        /** 实验编号，用于分组关联同一次实验的多次调用。 */
        String experimentId,
        /** 任务/用例编号，关联 {@link ExperimentCase#id()}。 */
        String taskId,
        /** 任务类型（分类/开放生成/幻觉探测）。 */
        TaskType taskType,
        /** 使用的模型名称（如 "gpt-4", "deepseek-chat"）。 */
        String model,
        /** 温度参数，控制生成随机性；{@code null} 表示使用 API 默认值。 */
        Double temperature,
        /** 最大输出 token 数；{@code null} 表示使用 API 默认值。 */
        Integer maxOutputTokens,
        /** 重复实验的轮次编号，从 1 开始，用于统计稳定性。 */
        int repeatIndex,
        /** HTTP 响应状态码；{@code null} 表示网络/超时等异常。 */
        Integer httpStatus,
        /** API 调用延迟（毫秒）。 */
        Long latencyMillis,
        /** 提示（输入）token 数。 */
        Integer promptTokens,
        /** 补全（输出）token 数。 */
        Integer completionTokens,
        /** 总 token 数。 */
        Integer totalTokens,
        /** API 调用是否成功（HTTP 层面）。 */
        boolean apiSuccess,
        /** 任务是否完成（语义层面，如分类是否为允许标签）；{@code null} 表示 API 失败无法判断。 */
        Boolean taskSuccess,
        /** 输出格式是否满足约束（如数量/长度合规）。 */
        Boolean formatValid,
        /** 是否发现事实错误，仅对需人工核查的用例有意义。 */
        Boolean factError,
        /** 模型返回的原始文本回答。 */
        String answer,
        /** 错误类型（如 "timeout", "rate_limit", "invalid_response"）。 */
        String errorType,
        /** 实验结果记录的时间戳。 */
        Instant timestamp
) {
    /** 紧凑构造器：校验必填实验标识、任务类型、重复轮次及时间戳。 */
    public ExperimentResult {
        if (experimentId == null || experimentId.isBlank()) {
            throw new IllegalArgumentException("实验编号不能为空");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("任务编号不能为空");
        }
        if (taskType == null) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        if (repeatIndex <= 0) {
            throw new IllegalArgumentException("repeatIndex 必须大于 0");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp 不能为空");
        }
    }
}
