package com.foxmimi.springaichat.tool.controller;

import com.foxmimi.springaichat.tool.service.ToolService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具调用端点
 * <p>
 * {@code POST /api/tool}：单轮工具问答。模型自主决策是否调用工具并填参，
 * 应用层不做关键词路由（路由 = 装配方式错）。
 * </p>
 */
@RestController
@RequestMapping("/api")
public class ToolController {

    private final ToolService toolService;

    public ToolController(ToolService toolService) {
        this.toolService = toolService;
    }

    @PostMapping("/tool")
    ToolResponse chat(@RequestBody ToolRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return toolService.chat(request.message());
    }
}
