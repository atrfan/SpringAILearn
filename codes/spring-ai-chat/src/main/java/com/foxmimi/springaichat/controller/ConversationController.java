package com.foxmimi.springaichat.controller;

import com.foxmimi.springaichat.model.request.ConversationRequest;
import com.foxmimi.springaichat.model.response.ConversationResponse;
import com.foxmimi.springaichat.service.ConversationService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}

