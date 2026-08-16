package com.foxmimi.springaichat.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具相关配置（外化）
 * <p>
 * 下游地址 / 超时等全部外化到 {@code application.yaml} 的 {@code tool.*} 配置块，
 * 不在代码里硬编码。
 * </p>
 *
 * @param weather 天气下游配置
 */
@ConfigurationProperties("tool")
public record ToolProperties(Weather weather) {

    /**
     * CMA 天气下游配置
     *
     * @param baseUrl           下游基础地址
     * @param timeoutMs         读超时（毫秒）
     * @param connectTimeoutMs  连接超时（毫秒）
     */
    public record Weather(String baseUrl, int timeoutMs, int connectTimeoutMs) {
        public Weather {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://weather.cma.cn";
            }
        }
    }
}
