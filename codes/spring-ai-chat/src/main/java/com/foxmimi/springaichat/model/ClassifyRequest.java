package com.foxmimi.springaichat.model;

import java.util.List;

/**
 * 分类请求体
 *
 * @param message    待分类的文本内容
 * @param categories 候选类别列表，由调用方传入；模型只能从中选择一个输出
 */
public record ClassifyRequest(String message, List<String> categories) {
}
