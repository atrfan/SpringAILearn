package com.foxmimi.springaichat.service;

import com.foxmimi.springaichat.model.domain.RenderedPrompt;
import com.foxmimi.springaichat.model.response.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分类服务
 * <p>
 * 在 {@link PromptChatService} 的基础上叠加分类任务特有的归一化逻辑：
 * 模型返回的原始文本可能带多余空白、大小写差异，或者干脆不在候选类别内，
 * 这里统一收敛成"候选类别中的一个，或未知"，不把不受控的自由文本透传给调用方。
 * </p>
 */
@Service
public class ClassifyService {

    /** 无法归一化到任何候选类别时使用的约定类别 */
    private static final String UNKNOWN_CATEGORY = "未知";

    private final PromptChatService promptChatService;

    public ClassifyService(PromptChatService promptChatService) {
        this.promptChatService = promptChatService;
    }

    /**
     * 调用模型完成分类，并把结果归一化到候选类别集合内
     *
     * @param prompt     已渲染完成的分类 Prompt
     * @param categories 候选类别列表，用于归一化模型输出
     * @return 与 {@link PromptChatService#chat} 相同结构的响应，其中 content 已归一化
     */
    public ChatResponse classify(RenderedPrompt prompt, List<String> categories) {
        ChatResponse raw = promptChatService.chat(prompt);
        String normalized = normalize(raw.content(), categories);
        return new ChatResponse(
                raw.model(),
                normalized,
                raw.promptTokens(),
                raw.completionTokens(),
                raw.totalTokens(),
                raw.elapsedMillis());
    }

    /**
     * 把模型的原始输出归一化为候选类别之一
     * <p>
     * 去除首尾空白后按候选类别做忽略大小写匹配；匹配不上（包括模型自称"未知"、
     * 附带多余文字、或输出根本不在候选集合内）一律归到 {@link #UNKNOWN_CATEGORY}，
     * 不直接把模型的自由文本透传给调用方。
     * </p>
     */
    private String normalize(String rawCategory, List<String> categories) {
        if (rawCategory == null) {
            return UNKNOWN_CATEGORY;
        }
        String trimmed = rawCategory.strip();
        for (String category : categories) {
            if (category.strip().equalsIgnoreCase(trimmed)) {
                return category.strip();
            }
        }
        return UNKNOWN_CATEGORY;
    }
}
