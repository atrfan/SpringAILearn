package com.foxmimi.springaichat.tool.dto;

/**
 * CMA 站点位置信息
 * <p>
 * {@code longitude}/{@code latitude}/{@code timezone} 为可选字段，用包装类型保留
 * "缺失"信号（避免基本类型把缺失误判为 0）。
 * </p>
 *
 * @param id        站点编号
 * @param name      站点名称
 * @param path       地理路径（如 "中国, 北京, 北京"）
 * @param longitude 经度（可选）
 * @param latitude  纬度（可选）
 * @param timezone  时区（可选）
 */
public record CmaLocation(String id, String name, String path,
                          Double longitude, Double latitude, Integer timezone) {
}
