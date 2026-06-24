package com.foxmimi.experiment.day05;

import com.foxmimi.client.DeepSeekClient;
import com.foxmimi.client.LlmChatClient;
import com.foxmimi.exception.LlmClientException;
import com.foxmimi.experiment.EvaluationOutcome;
import com.foxmimi.experiment.ExperimentCase;
import com.foxmimi.experiment.ExperimentEvaluator;
import com.foxmimi.experiment.ExperimentRequestFactory;
import com.foxmimi.experiment.ExperimentPlan;
import com.foxmimi.model.ChatMessage;
import com.foxmimi.model.ChatRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 第 05 天任务执行器，对每个任务用例执行一次 API 调用并收集结果。
 * <p>
 * 与 Day 04 的 {@link com.foxmimi.experiment.Day04ExperimentRunner} 不同，
 * Day 05 不重复执行，每条任务只调用 1 次，目标是广度覆盖。
 * <p>
 * 执行流程：
 * <pre>
 *   ExperimentCase → ChatRequest → API 调用 → 评价回答 → Day05TaskResult → CSV
 * </pre>
 */
public final class Day05TaskRunner {

    /** DeepSeek API 客户端。 */
    private final LlmChatClient client;
    /** 评价器，用于判断回答正确性。 */
    private final ExperimentEvaluator evaluator;
    /** CSV 写入器，用于持久化结果。 */
    private final Day05CsvWriter csvWriter;
    /** 两次调用之间的间隔，避免触发限流。 */
    private final Duration delayBetweenCalls;

    /**
     * 完整构造器。
     *
     * @param client            API 客户端
     * @param evaluator         评价器
     * @param csvWriter         CSV 写入器
     * @param delayBetweenCalls 调用间隔
     */
    public Day05TaskRunner(
            LlmChatClient client,
            ExperimentEvaluator evaluator,
            Day05CsvWriter csvWriter,
            Duration delayBetweenCalls
    ) {
        if (client == null || evaluator == null || csvWriter == null
                || delayBetweenCalls == null) {
            throw new IllegalArgumentException("执行器依赖不能为空");
        }
        if (delayBetweenCalls.isNegative()) {
            throw new IllegalArgumentException("调用间隔不能为负数");
        }
        this.client = client;
        this.evaluator = evaluator;
        this.csvWriter = csvWriter;
        this.delayBetweenCalls = delayBetweenCalls;
    }

    /**
     * 简化构造器，使用默认评价器和 CSV 写入器。
     *
     * @param client            API 客户端
     * @param delayBetweenCalls 调用间隔
     */
    public Day05TaskRunner(LlmChatClient client, Duration delayBetweenCalls) {
        this(client, new ExperimentEvaluator(), new Day05CsvWriter(), delayBetweenCalls);
    }

    /**
     * 串行执行所有任务用例，将结果写入 CSV 并返回内存列表。
     * <p>
     * 每条任务只执行 1 次。API 调用失败时记录错误类型而非中断整个流程。
     *
     * @param cases   任务用例列表
     * @param model   模型名称
     * @param temperature 温度参数
     * @param maxOutputTokens 最大输出 Token 数
     * @param csvPath CSV 输出路径
     * @return 所有任务结果
     */
    public List<Day05TaskResult> run(
            List<ExperimentCase> cases,
            String model,
            double temperature,
            int maxOutputTokens,
            Path csvPath
    ) throws IOException, InterruptedException {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("任务用例不能为空");
        }

        List<Day05TaskResult> results = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            ExperimentCase taskCase = cases.get(i);
            System.out.printf("[%d/%d] 执行任务：%s (%s)%n",
                    i + 1, cases.size(), taskCase.id(), taskCase.taskType());

            Day05TaskResult result = executeOnce(
                    taskCase, model, temperature, maxOutputTokens
            );
            csvWriter.append(csvPath, result);
            results.add(result);

            // 非最后一条时休眠，避免触发 API 限流
            if (i < cases.size() - 1) {
                pauseBetweenCalls();
            }
        }
        return List.copyOf(results);
    }

    /**
     * 执行一次 API 调用，包含评价和异常处理。
     * <p>
     * 构建临时 ExperimentPlan 以复用现有的 ExperimentRequestFactory，
     * 但只执行 1 次（repetitions = 1）。
     */
    private Day05TaskResult executeOnce(
            ExperimentCase taskCase,
            String model,
            double temperature,
            int maxOutputTokens
    ) {
        int inputCharLength = taskCase.userPrompt().length();

        try {
            // 直接构建 ChatRequest，避免依赖 ExperimentPlan 的 repetitions 语义
            ChatRequest request = new ChatRequest(
                    model,
                    List.of(
                            new ChatMessage("system", taskCase.systemPrompt()),
                            new ChatMessage("user", taskCase.userPrompt())
                    ),
                    temperature,
                    false,
                    maxOutputTokens,
                    null,
                    null
            );

            DeepSeekClient.CallResult call = client.chat(request);
            String answer = call.response().answer();

            // 使用现有评价器评估回答
            EvaluationOutcome evaluation = evaluator.evaluate(taskCase, answer);

            return new Day05TaskResult(
                    taskCase.id(),
                    taskCase.taskType(),
                    call.model(),
                    temperature,
                    maxOutputTokens,
                    inputCharLength,
                    call.promptTokens(),
                    call.completionTokens(),
                    call.totalTokens(),
                    call.elapsedMillis(),
                    true,
                    evaluation.taskSuccess(),
                    evaluation.formatValid(),
                    evaluation.factError(),
                    answer,
                    null,
                    Instant.now()
            );
        } catch (LlmClientException exception) {
            // API 调用失败：记录错误类型，业务字段全部置 null
            return failedResult(
                    taskCase.id(), taskCase.taskType(), model,
                    temperature, maxOutputTokens, inputCharLength,
                    exception.statusCode(), exception.type().name()
            );
        } catch (RuntimeException exception) {
            // 意外异常：记录为 UNEXPECTED_ERROR
            return failedResult(
                    taskCase.id(), taskCase.taskType(), model,
                    temperature, maxOutputTokens, inputCharLength,
                    null, "UNEXPECTED_ERROR"
            );
        }
    }

    /** 构建 API 调用失败的结果记录，仅填充标识和错误信息。 */
    private static Day05TaskResult failedResult(
            String taskId,
            com.foxmimi.experiment.TaskType taskType,
            String model,
            double temperature,
            int maxOutputTokens,
            int inputCharLength,
            Integer httpStatus,
            String errorType
    ) {
        return new Day05TaskResult(
                taskId, taskType, model, temperature, maxOutputTokens,
                inputCharLength, null, null, null, null,
                false, null, null, null, null, errorType,
                Instant.now()
        );
    }

    /** 按配置间隔休眠，避免触发 API 限流。 */
    private void pauseBetweenCalls() throws InterruptedException {
        if (!delayBetweenCalls.isZero()) {
            Thread.sleep(delayBetweenCalls);
        }
    }
}
