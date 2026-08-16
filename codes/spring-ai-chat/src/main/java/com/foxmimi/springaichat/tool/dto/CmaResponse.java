package com.foxmimi.springaichat.tool.dto;

/**
 * CMA 天气实况 + 预报接口响应
 * <p>
 * {@code /api/weather/view?stationid=...} 返回的顶层结构。成功判断双层：
 * HTTP 200 + Content-Type 含 application/json + {@code code === 0}。
 * </p>
 *
 * @param msg  消息
 * @param code 状态码，0 表示成功
 * @param data 实况 + 预报数据
 */
public record CmaResponse(String msg, int code, CmaWeatherData data) {
}
