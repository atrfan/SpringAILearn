package com.foxmimi.springaichat.tool.dto;

/**
 * 当前时间回喂 DTO（结构化返回）
 * <p>
 * 与 {@link WeatherViewDto} 同构：工具方法返回结构化 Record 而非 {@code String} 透传，
 * 模型拿到的是字段明确的对象，可逐字段引用（呼应 week04 {@code BeanOutputConverter} 思路）。
 * </p>
 *
 * @param date     当前日期（yyyy-MM-dd）
 * @param time     当前时刻（HH:mm:ss）
 * @param timezone 本地时区 ID（如 Asia/Shanghai）
 */
public record CurrentTimeDto(String date, String time, String timezone) {
}
