package com.foxmimi.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RetryPolicy} 的单元测试，覆盖退避计算、边界值和非法参数校验。
 *
 * <p>通过注入确定性随机源（{@link java.util.function.DoubleSupplier}）消除
 * 随机性，使退避时间的断言完全可预测。</p>
 */
class RetryPolicyTest {

    /** 使用固定随机值验证指数退避 + 抖动的计算结果。 */
    @Test
    void shouldCalculateDeterministicExponentialBackoffWithJitter() {
        RetryPolicy policy = new RetryPolicy(
                3,
                Duration.ofMillis(100),
                Duration.ofMillis(500)
        );

        assertAll(
                () -> assertEquals(
                        Duration.ofMillis(50),
                        policy.calculateDelay(0, () -> 0.0)
                ),
                () -> assertEquals(
                        Duration.ofMillis(150),
                        policy.calculateDelay(1, () -> 0.5)
                ),
                () -> assertEquals(
                        Duration.ofMillis(399),
                        policy.calculateDelay(2, () -> 0.999)
                ),
                () -> assertEquals(
                        Duration.ofMillis(499),
                        policy.calculateDelay(100, () -> 0.999)
                )
        );
    }

    /** maxAttempts = 1 表示只尝试一次、不重试，策略仍然合法。 */
    @Test
    void shouldAllowOneAttemptWhenRetryIsDisabled() {
        RetryPolicy policy = new RetryPolicy(
                1,
                Duration.ofMillis(1),
                Duration.ofMillis(1)
        );

        assertEquals(1, policy.maxAttempts());
    }

    /** 非法配置（maxAttempts=0、负数 attempt、越界随机值）必须被拒绝。 */
    @Test
    void shouldRejectInvalidConfigurationAndRandomValues() {
        RetryPolicy policy = new RetryPolicy(
                2,
                Duration.ofMillis(10),
                Duration.ofMillis(20)
        );

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RetryPolicy(0, Duration.ofMillis(1), Duration.ofMillis(1))
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> policy.calculateDelay(-1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> policy.calculateDelay(0, () -> 1.0)
                )
        );
    }
}
