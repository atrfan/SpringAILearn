package com.foxmimi.client;

import java.time.Duration;

/**
 * 重试等待策略的抽象，用于在重试前暂停指定时间。
 *
 * <p>默认实现 {@link #THREAD_SLEEPER} 使用 {@link Thread#sleep} 阻塞当前线程。
 * 测试中可以注入不实际休眠的 mock 实现，既验证等待时间又避免拖慢测试。</p>
 */
@FunctionalInterface
public interface RetrySleeper {

    /** 默认实现：阻塞当前线程指定时长。 */
    RetrySleeper THREAD_SLEEPER = duration -> Thread.sleep(duration);

    /**
     * 暂停指定时长。
     *
     * @param duration 等待时间
     * @throws InterruptedException 等待期间线程被中断时抛出
     */
    void sleep(Duration duration) throws InterruptedException;
}
