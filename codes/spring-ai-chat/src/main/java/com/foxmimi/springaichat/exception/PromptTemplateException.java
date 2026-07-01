package com.foxmimi.springaichat.exception;

/**
 * Prompt 模板异常
 * <p>
 * 当模板不存在、或渲染所需变量缺失时抛出，用于在真正调用模型前中断流程，
 * 避免把残缺的 Prompt 发送给模型。
 * </p>
 * <p>
 * 本周（Day16）只保证异常语义清晰；将其映射为稳定的结构化错误码
 * （复用 {@link com.foxmimi.springaichat.model.ErrorResponse}）属于 Day19 的工作。
 * </p>
 */
public class PromptTemplateException extends RuntimeException {

    public PromptTemplateException(String message) {
        super(message);
    }
}
