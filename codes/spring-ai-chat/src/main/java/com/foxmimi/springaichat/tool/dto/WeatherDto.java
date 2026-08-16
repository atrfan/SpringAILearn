package com.foxmimi.springaichat.tool.dto;

/**
 * 下游天气领域 DTO（应用内部使用，含 provider 标记与 location 元数据）
 * <p>
 * {@code tempC}/{@code humidity}/{@code feelst} 用基本类型：下游已通过
 * {@code code===0} 双层校验才解析，字段缺失按 0 兜底可接受；保留这些
 * 元数据用于服务端观测与日志，但在回喂模型前必须映射为
 * {@link WeatherViewDto} 做字段白名单消毒。
 * </p>
 *
 * @param city           城市名
 * @param tempC          气温（℃）
 * @param humidity       湿度（%）
 * @param condition      天气状况描述
 * @param windDirection  风向
 * @param windScale      风力描述
 * @param feelst         体感温度（℃）
 * @param internalSource 下游来源标记（如 "CMA"），不进模型回复
 * @param longitude      经度（元数据，不进模型回复）
 * @param latitude       纬度（元数据，不进模型回复）
 * @param path           地理路径（元数据，不进模型回复）
 */
public record WeatherDto(String city, double tempC, int humidity, String condition,
                         String windDirection, String windScale, double feelst,
                         String internalSource, Double longitude, Double latitude, String path) {
}
