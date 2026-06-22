package com.foxmimi.experiment;

import com.foxmimi.model.ChatMessage;
import com.foxmimi.model.ChatRequest;

import java.util.List;

/**
 * 实验请求工厂，将 {@link ExperimentPlan} 转换为 API 所需的 {@link ChatRequest}。
 */
public final class ExperimentRequestFactory {

    /**
     * 根据实验计划构建聊天请求。
     * <p>
     * 将 {@link ExperimentCase} 中的 systemPrompt 和 userPrompt 组装为消息列表。
     */
    public ChatRequest create(ExperimentPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("实验计划不能为空");
        }
        ExperimentCase experimentCase = plan.experimentCase();
        return new ChatRequest(
                plan.model(),
                List.of(
                        new ChatMessage("system", experimentCase.systemPrompt()),
                        new ChatMessage("user", experimentCase.userPrompt())
                ),
                plan.temperature(),
                false,
                plan.maxOutputTokens(),
                null,
                null
        );
    }
}
