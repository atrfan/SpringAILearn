package com.foxmimi.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 重试策略配置，定义有限重试的所有约束参数。
 *
 * <p>重试策略必须同时满足以下条件才能避免重试风暴：
 * <ul>
 *   <li>有限次数：不超过 {@code maxAttempts} 次总尝试</li>
 *   <li>指数退避：每次等待时间翻倍，上限为 {@code maxDelay}</li>
 *   <li>随机抖动：避免多个客户端同时重试造成惊群效应</li>
 *   <li>遵守 Retry-After：服务端明确指定等待时间时优先采用</li>
 *   <li>仅重试可重试的错误：认证失败、格式错误等不应被重试</li>
 * </ul>
 *
 * @param maxAttempts 最大总尝试次数（含首次调用），必须 &gt;= 1
 * @param baseDelay   首次重试前的基础等待时间
 * @param maxDelay    单次等待的上限，防止退避时间无限增长
 */
public record RetryPolicy(
        int maxAttempts,
        Duration baseDelay,
        Duration maxDelay
) {
    /** 默认策略：最多 3 次尝试，基础等待 1 秒，上限 10 秒。 */
    public static final RetryPolicy DEFAULT =
            new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(10));

    /** 紧凑构造器：校验参数合法性。 */
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 >= 1");
        }
        if (baseDelay == null || baseDelay.isZero() || baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay 必须大于零");
        }
        if (maxDelay == null || maxDelay.isZero() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("maxDelay 必须大于零");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay 不能小于 baseDelay");
        }
    }

    /**
     * 计算第 {@code attempt} 次重试前的等待时间（含抖动）。
     *
     * <p>计算公式：{@code min(baseDelay * 2^attempt, maxDelay) * (0.5 + random(0, 0.5))}。
     * 抖动范围是计算延迟的 50%-100%，确保不会等待过短也不会超过上限。</p>
     *
     * @param attempt 当前重试次数，从 0 开始（0 = 第一次重试）
     * @return 实际等待时间
     */
    public Duration calculateDelay(int attempt) {
        return calculateDelay(
                attempt,
                () -> ThreadLocalRandom.current().nextDouble()
        );
    }

    Duration calculateDelay(int attempt, DoubleSupplier randomSource) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 不能为负数");
        }
        if (randomSource == null) {
            throw new IllegalArgumentException("randomSource 不能为空");
        }

        long baseMillis = baseDelay.toMillis();
        long maxMillis = maxDelay.toMillis();
        long delayMillis = baseMillis;
        for (int i = 0; i < attempt && delayMillis < maxMillis; i++) {
            delayMillis = delayMillis > maxMillis / 2
                    ? maxMillis
                    : Math.min(delayMillis * 2, maxMillis);
        }

        // 随机抖动：乘以 [0.5, 1.0) 之间的随机因子
        double randomValue = randomSource.getAsDouble();
        if (randomValue < 0.0 || randomValue >= 1.0) {
            throw new IllegalArgumentException("随机值必须位于 [0, 1)");
        }
        double jitter = 0.5 + randomValue * 0.5;
        delayMillis = (long) (delayMillis * jitter);

        return Duration.ofMillis(delayMillis);
    }
}
