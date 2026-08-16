package com.foxmimi.springaichat.tool.dto;

/**
 * CMA autocomplete 解析后的城市站点
 * <p>
 * autocomplete 返回的 {@code data} 是 {@code |} 分隔的字符串，前三段分别为
 * {@code stationId}、地名、上级地名，尾部字段可能变化，故仅取前三段。
 * </p>
 *
 * @param stationId   站点编号
 * @param name        地名
 * @param parentName  上级地名
 */
public record CmaStation(String stationId, String name, String parentName) {
}
