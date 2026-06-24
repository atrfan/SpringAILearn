package com.foxmimi.client;

import com.foxmimi.exception.LlmErrorType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetryDecision} 默认决策表的一致性测试。
 *
 * <p>验证决策表与 {@link LlmErrorType} 枚举保持同步：
 * 条目数量、可重试属性必须与枚举定义一致。</p>
 */
class RetryDecisionTest {

    /** 决策表必须覆盖所有 LlmErrorType，且可重试属性与枚举定义一致。 */
    @Test
    void shouldKeepDecisionTableConsistentWithErrorMetadata() {
        Map<LlmErrorType, RetryDecision> decisions = RetryDecision.defaults().stream()
                .collect(Collectors.toMap(RetryDecision::errorType, decision -> decision));

        assertEquals(LlmErrorType.values().length, decisions.size());
        assertFalse(decisions.get(LlmErrorType.AUTHENTICATION).retryable());
        assertFalse(decisions.get(LlmErrorType.RESPONSE_FORMAT).retryable());
        assertTrue(decisions.get(LlmErrorType.RATE_LIMIT).retryable());
        assertTrue(decisions.get(LlmErrorType.SERVER_ERROR).retryable());

        decisions.forEach((type, decision) ->
                assertEquals(type.retryable(), decision.retryable()));
    }
}
