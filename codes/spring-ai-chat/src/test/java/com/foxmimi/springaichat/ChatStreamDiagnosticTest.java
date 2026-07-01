package com.foxmimi.springaichat;

import com.foxmimi.springaichat.service.MyChatService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式响应到达时间诊断测试。
 *
 * <p>该测试会调用真实模型服务，需要有效的 DEEPSEEK_KEY 和网络连接，
 * 因此使用 integration 标签隔离，不会被默认的 mvn test 执行。</p>
 */
@Tag("integration")
@SpringBootTest
class ChatStreamDiagnosticTest {

    private final MyChatService chatService;

    @Autowired
    ChatStreamDiagnosticTest(MyChatService chatService) {
        this.chatService = chatService;
    }

    @Test
    void printsArrivalTimeOfEveryStreamChunk() {
        long startMillis = System.currentTimeMillis();
        AtomicInteger chunkCount = new AtomicInteger();

        chatService.chatStream("请详细介绍一下 Spring AI")
                .doOnSubscribe(subscription ->
                        System.out.printf(
                                "+%d ms -> 开始订阅%n",
                                System.currentTimeMillis() - startMillis
                        ))
                .doOnNext(chunk ->
                        System.out.printf(
                                "+%d ms -> chunk-%d: [%s]%n",
                                System.currentTimeMillis() - startMillis,
                                chunkCount.incrementAndGet(),
                                chunk
                        ))
                .doOnComplete(() ->
                        System.out.printf(
                                "+%d ms -> 流结束%n",
                                System.currentTimeMillis() - startMillis
                        ))
                .blockLast(Duration.ofMinutes(2));

        assertThat(chunkCount.get())
                .as("模型至少应返回一个流式片段")
                .isPositive();
    }
}
