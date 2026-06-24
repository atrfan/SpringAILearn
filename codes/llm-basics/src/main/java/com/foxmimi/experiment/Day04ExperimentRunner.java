package com.foxmimi.experiment;

import com.foxmimi.client.DeepSeekClient;
import com.foxmimi.client.LlmChatClient;
import com.foxmimi.exception.LlmClientException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 第 04 天实验执行器，编排多组实验计划的串行执行。
 * <p>
 * 负责：调用 API → 评价回答 → CSV 持久化，失败时记录错误类型而非中断。
 */
public final class Day04ExperimentRunner {

    /** DeepSeek API 客户端。 */
    private final LlmChatClient client;
    /** 请求工厂，用于构建 ChatRequest。 */
    private final ExperimentRequestFactory requestFactory;
    /** 评价器，用于判断回答正确性。 */
    private final ExperimentEvaluator evaluator;
    /** CSV 写入器，用于持久化实验结果。 */
    private final CsvExperimentResultWriter csvWriter;
    /** 两次 API 调用之间的间隔，用于避免限流。 */
    private final Duration delayBetweenCalls;

    /**
     * 简化构造器，调用间隔默认为 0。
     *
     * @param client DeepSeek API 客户端
     */
    public Day04ExperimentRunner(LlmChatClient client) {
        this(client, Duration.ZERO);
    }

    /**
     * 完整构造器，允许自定义调用间隔。
     *
     * @param client            DeepSeek API 客户端
     * @param delayBetweenCalls 调用间隔，避免触发限流
     */
    public Day04ExperimentRunner(
            LlmChatClient client,
            Duration delayBetweenCalls
    ) {
        this(
                client,
                new ExperimentRequestFactory(),
                new ExperimentEvaluator(),
                new CsvExperimentResultWriter(),
                delayBetweenCalls
        );
    }

    /** 包级私有构造器，支持依赖注入（便于测试）。 */
    Day04ExperimentRunner(
            LlmChatClient client,
            ExperimentRequestFactory requestFactory,
            ExperimentEvaluator evaluator,
            CsvExperimentResultWriter csvWriter,
            Duration delayBetweenCalls
    ) {
        if (client == null || requestFactory == null || evaluator == null
                || csvWriter == null || delayBetweenCalls == null) {
            throw new IllegalArgumentException("实验执行器依赖不能为空");
        }
        if (delayBetweenCalls.isNegative()) {
            throw new IllegalArgumentException("调用间隔不能为负数");
        }
        this.client = client;
        this.requestFactory = requestFactory;
        this.evaluator = evaluator;
        this.csvWriter = csvWriter;
        this.delayBetweenCalls = delayBetweenCalls;
    }

    /**
     * 串行执行多组实验计划。
     * <p>
     * 每组计划按指定重复次数运行，结果追加写入 CSV 并返回内存列表。
     *
     * @param plans  实验计划列表
     * @param csvPath CSV 输出路径
     * @return 所有实验结果
     */
    public List<ExperimentResult> run(
            List<ExperimentPlan> plans,
            Path csvPath
    ) throws IOException, InterruptedException {
        if (plans == null || plans.isEmpty()) {
            throw new IllegalArgumentException("实验计划不能为空");
        }

        List<ExperimentResult> results = new ArrayList<>();
        for (ExperimentPlan plan : plans) {
            for (int repeatIndex = 1; repeatIndex <= plan.repetitions(); repeatIndex++) {
                ExperimentResult result = executeOnce(plan, repeatIndex);
                csvWriter.append(csvPath, result);
                results.add(result);
                pauseBetweenCalls();
            }
        }
        return List.copyOf(results);
    }

    /** 按配置间隔休眠，避免触发 API 限流。 */
    private void pauseBetweenCalls() throws InterruptedException {
        if (!delayBetweenCalls.isZero()) {
            Thread.sleep(delayBetweenCalls);
        }
    }

    /** 执行一次 API 调用，包含评价和异常处理。 */
    private ExperimentResult executeOnce(ExperimentPlan plan, int repeatIndex) {
        try {
            DeepSeekClient.CallResult call = client.chat(requestFactory.create(plan));
            String answer = call.response().answer();
            EvaluationOutcome evaluation = evaluator.evaluate(plan.experimentCase(), answer);
            return successfulResult(plan, repeatIndex, call, answer, evaluation);
        } catch (LlmClientException exception) {
            return failedResult(
                    plan,
                    repeatIndex,
                    exception.statusCode(),
                    exception.type().name()
            );
        } catch (RuntimeException exception) {
            return failedResult(plan, repeatIndex, null, "UNEXPECTED_ERROR");
        }
    }

    /** 构建 API 调用成功的实验结果。 */
    private static ExperimentResult successfulResult(
            ExperimentPlan plan,
            int repeatIndex,
            DeepSeekClient.CallResult call,
            String answer,
            EvaluationOutcome evaluation
    ) {
        return new ExperimentResult(
                plan.groupId(),
                plan.experimentCase().id(),
                plan.experimentCase().taskType(),
                call.model(),
                plan.temperature(),
                plan.maxOutputTokens(),
                repeatIndex,
                call.statusCode(),
                call.elapsedMillis(),
                call.promptTokens(),
                call.completionTokens(),
                call.totalTokens(),
                true,
                evaluation.taskSuccess(),
                evaluation.formatValid(),
                evaluation.factError(),
                answer,
                null,
                Instant.now()
        );
    }

    /** 构建 API 调用失败（异常）的实验结果，仅填充实验标识和错误信息。 */
    private static ExperimentResult failedResult(
            ExperimentPlan plan,
            int repeatIndex,
            Integer httpStatus,
            String errorType
    ) {
        return new ExperimentResult(
                plan.groupId(),
                plan.experimentCase().id(),
                plan.experimentCase().taskType(),
                plan.model(),
                plan.temperature(),
                plan.maxOutputTokens(),
                repeatIndex,
                httpStatus,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                errorType,
                Instant.now()
        );
    }
}
