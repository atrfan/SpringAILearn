package com.foxmimi.client;

import com.foxmimi.exception.LlmErrorType;

import java.util.List;

/**
 * 重试决策表条目，描述每种错误类别的默认处理建议。
 *
 * <p>该表将 {@link LlmErrorType} 映射到人类可读的故障描述和建议动作，
 * 同时标注该错误是否允许有限重试。可用于生成运维文档或辅助错误处理决策。</p>
 *
 * @param failure   故障场景的人类可读描述
 * @param errorType 对应的错误类别
 * @param retryable 该错误类别是否允许有限重试
 * @param action    建议的处理动作
 */
public record RetryDecision(
        String failure,
        LlmErrorType errorType,
        boolean retryable,
        String action
) {
    /**
     * 返回所有错误类别的默认重试决策表。
     *
     * <p>表中的每条记录与 {@link LlmErrorType} 的枚举值一一对应，
     * 确保决策表覆盖所有已定义的错误类别。</p>
     *
     * @return 默认重试决策表
     */
    public static List<RetryDecision> defaults() {
        return List.of(
                new RetryDecision(
                        "HTTP 400/404 等请求错误",
                        LlmErrorType.CLIENT_ERROR,
                        false,
                        "修正请求参数、URI 或模型名称"
                ),
                new RetryDecision(
                        "HTTP 401/403",
                        LlmErrorType.AUTHENTICATION,
                        false,
                        "修正 API Key 或权限配置"
                ),
                new RetryDecision(
                        "HTTP 408 或客户端请求超时",
                        LlmErrorType.REQUEST_TIMEOUT,
                        true,
                        "在总时间预算内有限重试"
                ),
                new RetryDecision(
                        "HTTP 429",
                        LlmErrorType.RATE_LIMIT,
                        true,
                        "优先遵守 Retry-After，再执行有限重试"
                ),
                new RetryDecision(
                        "HTTP 5xx",
                        LlmErrorType.SERVER_ERROR,
                        true,
                        "指数退避、抖动并限制最大尝试次数"
                ),
                new RetryDecision(
                        "临时网络异常",
                        LlmErrorType.NETWORK_ERROR,
                        true,
                        "确认请求幂等风险后有限重试"
                ),
                new RetryDecision(
                        "非法 JSON 或关键字段缺失",
                        LlmErrorType.RESPONSE_FORMAT,
                        false,
                        "保存脱敏样本并修正协议映射"
                )
        );
    }
}
