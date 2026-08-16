package com.foxmimi.springaichat.tool.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 工具层配置
 * <p>
 * 注册 {@link ToolProperties}，并装配调真实 CMA 接口的
 * {@link RestClient}：带 {@code Referer} / {@code User-Agent} / {@code Accept}
 * 三头绕 WAF，连接 / 读超时外化自 {@link ToolProperties}。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(ToolProperties.class)
public class ToolConfig {

    private static final String REFERER = "https://weather.cma.cn/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String ACCEPT =
            "application/json, text/javascript, */*; q=0.01";

    /**
     * 装配调 CMA 接口的 RestClient
     * <p>
     * Bean 名 {@code weatherRestClient}，{@link com.foxmimi.springaichat.tool.client.WeatherClient}
     * 按参数名注入。
     * </p>
     */
    @Bean
    public RestClient weatherRestClient(ToolProperties toolProperties) {
        ToolProperties.Weather weather = toolProperties.weather();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(weather.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(weather.timeoutMs()));
        return RestClient.builder()
                .baseUrl(weather.baseUrl())
                .defaultHeader("Referer", REFERER)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", ACCEPT)
                .requestFactory(factory)
                .build();
    }
}
