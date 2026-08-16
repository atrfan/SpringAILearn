package com.foxmimi.springaichat.tool.dto;

/**
 * 天气回喂模型层 View DTO（字段白名单消毒后）
 * <p>
 * 剥离 {@link WeatherDto} 中的 {@code internalSource}/{@code longitude}/
 * {@code latitude}/{@code path} 等元数据，只保留模型回答天气所需字段。
 * </p>
 *
 * @param city          城市名
 * @param tempC         气温（℃）
 * @param humidity      湿度（%）
 * @param condition     天气状况描述
 * @param windDirection 风向
 * @param windScale     风力描述
 * @param feelst        体感温度（℃）
 */
public record WeatherViewDto(String city, double tempC, int humidity, String condition,
                             String windDirection, String windScale, double feelst) {
}
