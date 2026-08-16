package com.foxmimi.springaichat.tool.controller;

/**
 * 工具调用请求体
 *
 * @param message 用户消息
 */
public record ToolRequest(String message) {
}
