package com.foxmimi.springaichat.exception;

import com.foxmimi.springaichat.handler.GlobalExceptionHandler;

/**
 * Prompt 变量超长异常
 * <p>
 * 当某个模板变量的文本长度超过 {@code PromptTemplateService} 设定的上限时抛出。
 * 处理策略选择"拒绝"而非"截断"：截断会悄悄改变用户数据的语义（比如摘要一篇被
 * 拦腰截断的文章，结果看似合理实则失真），宁可让调用方明确知道输入超限后自行取舍。
 * </p>
 * <p>
 * 与父类 {@link PromptTemplateException} 的区别：父类表示模板配置或渲染的服务端问题
 * （映射为 500），本异常由客户端输入触发（映射为 400），二者在
 * {@link GlobalExceptionHandler} 中分别处理。
 * </p>
 */
public class PromptInputTooLongException extends PromptTemplateException {

    public PromptInputTooLongException(String variableName, int actualLength, int maxLength) {
        super("变量 [" + variableName + "] 长度 " + actualLength + " 超过上限 " + maxLength);
    }
}
