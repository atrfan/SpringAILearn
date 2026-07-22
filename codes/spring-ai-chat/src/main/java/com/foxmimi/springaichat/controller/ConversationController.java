package com.foxmimi.springaichat.controller;

import com.foxmimi.springaichat.model.request.ConversationRequest;
import com.foxmimi.springaichat.model.response.ConversationResponse;
import com.foxmimi.springaichat.service.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class ConversationController {
    private final ConversationService conversationService;

    ConversationController(ConversationService chatService) {
        this.conversationService = chatService;
    }

    @PostMapping("/conversation")
    ConversationResponse chat(@RequestBody ConversationRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (!StringUtils.hasText(request.conversationId())) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        return conversationService.chat(request);
    }

    /**
     * 结束/重置一条会话：清除该 conversationId 的历史记忆。
     * <p>
     * 与 chat 端点保持一致的边界立场（决定 4）：conversationId 空白即拒绝，返回 400。
     * clear 本身幂等，清除后端点返回 204 No Content。
     * </p>
     */
    @DeleteMapping("/conversation/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@PathVariable String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        conversationService.clear(conversationId);
    }
}

