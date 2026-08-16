package com.foxmimi.springaichat.tool.dto;

import java.util.List;

/**
 * CMA 天气数据
 *
 * @param location    站点位置信息
 * @param now         当前实况
 * @param daily       预报列表
 * @param alarm       预警列表
 * @param lastUpdate  最近更新时间
 */
public record CmaWeatherData(CmaLocation location, CmaNow now,
                             List<CmaDaily> daily, List<CmaAlarm> alarm,
                             String lastUpdate) {
}
