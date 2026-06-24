package com.foxmimi.springaichat.controller;

import com.foxmimi.springaichat.model.ChatRequest;
import com.foxmimi.springaichat.model.ChatResponse;
import com.foxmimi.springaichat.service.MyChatService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private final MyChatService chatService;

    /**
     * 通过构造器注入聊天服务
     *
     * @param chatService 聊天服务实例
     */
    ChatController(MyChatService chatService) {
        this.chatService = chatService;
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
}
