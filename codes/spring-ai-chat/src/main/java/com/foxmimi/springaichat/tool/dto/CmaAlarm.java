package com.foxmimi.springaichat.tool.dto;

/**
 * CMA 预警信息
 *
 * @param id        预警 ID
 * @param title     预警标题
 * @param type      预警类型
 * @param level     预警级别
 * @param effective 生效时间
 */
public record CmaAlarm(String id, String title, String type, String level, String effective) {
}
