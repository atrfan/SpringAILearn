package com.foxmimi.springaichat.tool.tool;

import com.foxmimi.springaichat.tool.dto.CurrentTimeDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时钟工具服务
 * <p>
 * 对模型暴露的第二个只读工具 {@link #getCurrentTime()}：纯 Java 实现、不调任何下游，
 * 与 {@link WeatherService} 保持同构（{@code @Service} + {@code @Tool} 标注 + Record DTO 返回）。
 * </p>
 */
@Slf4j
@Service
public class ClockService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 获取当前日期、时刻与本地时区
     * <p>
     * 模型决策调用此工具（无参数）；返回结构化 {@link CurrentTimeDto}，
     * 模型可直接引用 {@code date}/{@code time}/{@code timezone} 三个字段。
     * </p>
     *
     * @return 当前时间 DTO
     */
    @Tool(description = "获取当前时间")
    public CurrentTimeDto getCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        log.debug("调用了 ClockService.getCurrentTime() 方法: {}", now);
        return new CurrentTimeDto(
                now.format(DATE_FORMATTER),
                now.format(TIME_FORMATTER),
                ZoneId.systemDefault().getId()
        );
    }
}
