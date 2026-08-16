package com.foxmimi.springaichat.tool.dto;

/**
 * CMA autocomplete 接口响应
 * <p>
 * {@code /api/autocomplete?q=...} 返回 {@code data} 为 {@code |} 分隔的字符串数组，
 * 形如 {@code ["54511|北京|北京|中国, 北京, 北京"]}。
 * </p>
 *
 * @param msg  消息
 * @param code 状态码，0 表示成功
 * @param data 城市与站点信息数组（{@code |} 分隔字符串）
 */
public record CmaAutocompleteResponse(String msg, int code, java.util.List<String> data) {
}
