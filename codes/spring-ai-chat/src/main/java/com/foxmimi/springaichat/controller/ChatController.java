package com.foxmimi.springaichat.controller;

import com.foxmimi.springaichat.model.ChatRequest;
import com.foxmimi.springaichat.model.ChatResponse;
import com.foxmimi.springaichat.model.ClassifyRequest;
import com.foxmimi.springaichat.model.RenderedPrompt;
import com.foxmimi.springaichat.model.SummarizeRequest;
import com.foxmimi.springaichat.service.ClassifyService;
import com.foxmimi.springaichat.service.MyChatService;
import com.foxmimi.springaichat.service.PromptChatService;
import com.foxmimi.springaichat.service.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 聊天接口控制器
 * <p>
 * 提供 RESTful API 接口，接收用户的聊天消息并返回 AI 模型的响应结果。
 * 请求路径前缀为 {@code /api}。
 * </p>
 */
@RestController
@RequestMapping("/api")
class ChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatController.class);

    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(60);

    private final MyChatService chatService;

    private final PromptChatService promptChatService;

    private final ClassifyService classifyService;

    private final PromptTemplateService promptTemplateService;

    /**
     * 通过构造器注入聊天服务、Prompt 调用服务、分类服务与模板服务
     *
     * @param chatService            聊天服务实例
     * @param promptChatService      Prompt 调用服务实例，摘要与分类共用
     * @param classifyService        分类服务实例，负责分类结果归一化
     * @param promptTemplateService  Prompt 模板服务实例
     */
    ChatController(MyChatService chatService,
                    PromptChatService promptChatService,
                    ClassifyService classifyService,
                    PromptTemplateService promptTemplateService) {
        this.chatService = chatService;
        this.promptChatService = promptChatService;
        this.classifyService = classifyService;
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 处理聊天请求
     * <p>
     * 接收 POST 请求 {@code /api/chat}，校验消息内容非空后调用聊天服务。
     * </p>
     *
     * @param request 聊天请求体，包含用户消息
     * @return 聊天响应，包含模型回复和 Token 使用信息
     * @throws IllegalArgumentException 当消息为空或仅包含空白字符时抛出
     */
    @PostMapping("/chat")
    ChatResponse chat(@RequestBody ChatRequest request) {
        // 校验请求体和消息内容不能为空
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new IllegalArgumentException("message 不能为空");
        }
        // 去除消息两端空白后调用聊天服务
        return chatService.chat(request.message().trim());
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> chatStream(@RequestParam String message) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message 不能为空");
        }

        // 记录请求开始时间，用于后续统计本次流式请求的耗时
        long start = System.nanoTime();
        // 去除首尾空白后再交给下游服务处理，避免无效空格影响模型输入
        String trimmedMessage = message.trim();

        // 保存最后一次收到的 ChatResponse，方便流结束后输出完整的模型与用量信息
        final java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.model.ChatResponse> lastResponse =
                new java.util.concurrent.atomic.AtomicReference<>();

        return chatService.chatStream(trimmedMessage)
                // 设置整条流的超时时间，避免上游长时间无响应导致请求悬挂
                .timeout(STREAM_TIMEOUT)
                // 每次收到响应都更新一次最近的完整响应对象
                .doOnNext(lastResponse::set)
                // 只向前端输出真正需要的文本内容，兼容中间结果为空的情况
                .map(response -> {
                    var result = response.getResult();
                    // result 或其 output 为空时都不应解引用，避免 NPE 被 onErrorResume 静默吞掉
                    if (result == null || result.getOutput() == null) {
                        return "";
                    }
                    return Optional.ofNullable(result.getOutput().getText()).orElse("");
                })
                // 过滤空片段：流的最后一个 chunk 通常只带 usage 元数据、正文为空，
                // 若原样下发会给前端推送无意义的空 SSE 事件（lastResponse 已在上游 doOnNext 捕获，不受影响）
                .filter(text -> !text.isEmpty())
                // 流正常结束后，补充打印本次请求的完整响应与耗时信息
                // 注意：仅在正常完成（onComplete）时打印。错误由下游 onErrorResume 记录、
                // 客户端取消由 doOnCancel 记录，避免把失败/取消误记为一次成功的响应
                .doOnComplete(() -> {
                    var response = lastResponse.get();
                    if (response != null) {
                        var metadata = response.getMetadata();
                        LOGGER.info("本次请求 ChatResponse: model={}, usage={}, elapsed={}ms",
                                metadata.getModel(),
                                metadata.getUsage(),
                                elapsedMillisSince(start));
                    } else {
                        // 如果整个流过程中都没有拿到响应，则记录兜底日志，便于排查问题
                        LOGGER.info("本次请求结束，但未获得 ChatResponse");
                    }
                })
                // 流式请求异常时不中断整个接口，直接结束流并输出告警日志
                .onErrorResume(exception -> {
                    LOGGER.warn("流式聊天请求异常终止：{}", exception.toString());
                    return Flux.empty();
                })
                // 如果客户端主动断开连接，记录取消事件，便于分析前端中断情况
                .doOnCancel(() -> LOGGER.info("客户端取消流式聊天请求"));
    }

    @PostMapping("/summarize")
    ChatResponse summerizeChat(@RequestBody SummarizeRequest request) {
        // 校验请求体和消息内容不能为空
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new IllegalArgumentException("message 不能为空");
        }

        RenderedPrompt render = promptTemplateService.render("summarize", Map.of("content", request.message().trim()));
        return promptChatService.chat(render);
    }

    /**
     * 处理分类请求
     * <p>
     * 接收 POST 请求 {@code /api/classify}，候选类别由请求方传入。
     * 渲染分类模板后交给模型判断，再由 {@link ClassifyService} 把结果归一化到候选类别内。
     * </p>
     *
     * @param request 分类请求体，包含待分类文本与候选类别列表
     * @return 分类响应，content 字段为归一化后的类别（或"未知"）
     * @throws IllegalArgumentException 当消息为空，或候选类别列表为空时抛出
     */
    @PostMapping("/classify")
    ChatResponse classify(@RequestBody ClassifyRequest request) {
        // 校验请求体和消息内容不能为空
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new IllegalArgumentException("message 不能为空");
        }

        // 去除空白候选类别，保留调用方传入的原始文案用于归一化匹配
        List<String> categories = request.categories() == null
                ? List.of()
                : request.categories().stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .toList();
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("categories 不能为空");
        }

        RenderedPrompt render = promptTemplateService.render("classify", Map.of(
                "content", request.message().trim(),
                "categories", String.join(",", categories)));
        return classifyService.classify(render, categories);
    }

    private long elapsedMillisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}

