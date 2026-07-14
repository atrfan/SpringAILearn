package com.foxmimi.springaichat.exception;

import com.foxmimi.springaichat.handler.GlobalExceptionHandler;

/**
 * Prompt 模板异常
 * <p>
 * 当模板不存在、渲染所需变量缺失或类型非法时抛出，用于在真正调用模型前中断流程，
 * 避免把残缺的 Prompt 发送给模型。这些情况都属于服务端模板配置或调用代码的问题，
 * 由 {@link GlobalExceptionHandler} 映射为 500 + {@code PROMPT_TEMPLATE_ERROR}。
 * </p>
 * <p>
 * 客户端输入超长的场景由子类 {@link PromptInputTooLongException} 表达，映射为 400。
 * </p>
 */
public class PromptTemplateException extends RuntimeException {

    public PromptTemplateException(String message) {
        super(message);
    }
}
