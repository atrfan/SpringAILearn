package com.foxmimi.springaichat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring Boot 上下文加载测试
 * <p>
 * 验证应用能否正常启动，所有 Bean 能否正确装配。
 * </p>
 */
@Tag("integration")
@SpringBootTest
class SpringAiChatApplicationTests {

    /**
     * 验证 Spring 应用上下文能够正常加载
     */
    @Test
    void contextLoads() {
    }

}
