package com.foxmimi.springaichat.tool.dto;

/**
 * CMA 逐日预报
 *
 * @param date              日期（如 "2026/08/03"）
 * @param high              最高温（℃）
 * @param low               最低温（℃）
 * @param dayText           白天天气描述
 * @param dayCode           白天天气代码
 * @param dayWindDirection  白天风向
 * @param dayWindScale      白天风力
 * @param nightText         夜间天气描述
 * @param nightCode         夜间天气代码
 * @param nightWindDirection 夜间风向
 * @param nightWindScale    夜间风力
 */
public record CmaDaily(String date, Double high, Double low, String dayText, Integer dayCode,
                       String dayWindDirection, String dayWindScale, String nightText, Integer nightCode,
                       String nightWindDirection, String nightWindScale) {
}
