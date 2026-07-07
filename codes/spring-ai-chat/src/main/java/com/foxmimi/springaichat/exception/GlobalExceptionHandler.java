package com.foxmimi.springaichat.exception;

import com.foxmimi.springaichat.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.net.SocketTimeoutException;
import java.time.Instant;

/**
 * 全局异常处理器
 * <p>
 * 使用 {@link RestControllerAdvice} 统一拦截并处理控制器层抛出的异常，
 * 将不同类型的异常映射为结构化的 {@link ErrorResponse} JSON 响应，
 * 避免向客户端暴露原始异常堆栈信息。
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleAsyncRequestNotUsable(AsyncRequestNotUsableException exception) {
        LOGGER.debug("客户端在流式响应中断开连接");
    }

    /**
     * 处理非法请求参数（如 message 为空）
     * 返回 HTTP 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> handleInvalidRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    /**
     * 处理请求体 JSON 格式不合法的情况
     * 返回 HTTP 400 Bad Request
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadableRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求体必须是合法的 JSON");
    }

    /**
     * 处理模板变量超长：由客户端输入过长触发，返回 HTTP 400 Bad Request。
     * 异常消息由 {@link PromptInputTooLongException} 自行构造（变量名 + 实际长度 + 上限），
     * 不含模板内部细节，可以直接透出给调用方。
     */
    @ExceptionHandler(PromptInputTooLongException.class)
    ResponseEntity<ErrorResponse> handleInputTooLong(PromptInputTooLongException exception) {
        return error(HttpStatus.BAD_REQUEST, "INPUT_TOO_LONG", exception.getMessage());
    }

    /**
     * 处理其余 Prompt 模板异常（模板不存在、变量缺失/类型非法）
     * <p>
     * 这些情况意味着服务端模板配置或调用代码有问题（Controller 已负责拦截空输入，
     * 走到渲染层仍缺变量说明是代码 bug），因此返回 HTTP 500；
     * 具体细节（模板 id、变量名）只记录日志，不透出给客户端。
     * </p>
     */
    @ExceptionHandler(PromptTemplateException.class)
    ResponseEntity<ErrorResponse> handlePromptTemplateException(PromptTemplateException exception) {
        LOGGER.error("Prompt 模板配置或渲染错误: {}", exception.getMessage());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "PROMPT_TEMPLATE_ERROR", "Prompt 模板配置或渲染错误");
    }

    /**
     * 处理 AI 模型服务的瞬时性异常（如限流、超时等临时故障）
     * <ul>
     *   <li>若根因是 SocketTimeoutException，返回 HTTP 504 Gateway Timeout</li>
     *   <li>否则返回 HTTP 503 Service Unavailable</li>
     * </ul>
     */
    @ExceptionHandler(TransientAiException.class)
    ResponseEntity<ErrorResponse> handleTransientAiException(TransientAiException exception) {
        if (hasCause(exception, SocketTimeoutException.class)) {
            return error(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", "模型服务响应超时");
        }
        return error(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", "模型服务暂时不可用");
    }

    /**
     * 处理 AI 模型服务的非瞬时性异常（如认证失败、模型不存在等不可恢复错误）
     * 以及上游响应异常，返回 HTTP 502 Bad Gateway
     */
    @ExceptionHandler({NonTransientAiException.class, UpstreamResponseException.class})
    ResponseEntity<ErrorResponse> handleUpstreamFailure(RuntimeException exception) {
        return error(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "模型服务调用失败");
    }

    /**
     * 兜底处理：捕获所有未被上述处理器匹配的异常
     * 记录错误日志并返回 HTTP 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        LOGGER.error("处理聊天请求时发生未预期异常", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误");
    }

    /**
     * 构建统一格式的错误响应
     *
     * @param status  HTTP 状态码
     * @param code    业务错误码
     * @param message 用户可读的错误描述
     * @return 包含错误信息的 ResponseEntity
     */
    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, Instant.now().toEpochMilli()));
    }

    /**
     * 递归检查异常链中是否包含指定类型的根因异常
     *
     * @param throwable 待检查的异常
     * @param causeType 目标异常类型
     * @return 如果异常链中存在指定类型则返回 true
     */
    private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
