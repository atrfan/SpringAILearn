package com.foxmimi.springaichat.tool.dto;

/**
 * CMA 当前实况
 * <p>
 * 所有数值字段使用 {@code Double} 包装类型：CMA 的 {@code temperature}/
 * {@code precipitation} 可能合法地等于 {@code 0.0}，基本类型无法区分"缺失"与
 * "0"，包装类型缺失为 {@code null}、0 为 {@code 0.0}，可区分。
 * </p>
 *
 * @param precipitation         降水量（mm）
 * @param temperature           气温（℃）
 * @param pressure              气压（hPa）
 * @param humidity              湿度（%）
 * @param windDirection         中文风向
 * @param windDirectionDegree   风向角度
 * @param windSpeed             风速（m/s）
 * @param windScale             风力描述
 * @param feelst                体感温度（℃，可选）
 */
public record CmaNow(Double precipitation, Double temperature, Double pressure, Double humidity,
                     String windDirection, Double windDirectionDegree, Double windSpeed,
                     String windScale, Double feelst) {
}
