package com.foxmimi.springaichat.service;

import com.foxmimi.springaichat.model.ChatResponse;
import com.foxmimi.springaichat.model.ExtractResponse;
import com.foxmimi.springaichat.model.ExtractResult;
import com.foxmimi.springaichat.model.RenderedPrompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ExtractService {

    private static final BeanOutputConverter<ExtractResult> converter = new BeanOutputConverter<>(ExtractResult.class);

    private final PromptChatService promptChatService;

    private final PromptTemplateService promptTemplateService;

    public ExtractService(PromptChatService promptChatService, PromptTemplateService promptTemplateService) {
        this.promptChatService = promptChatService;
        this.promptTemplateService = promptTemplateService;
    }

    public ExtractResponse extract(String message) {
        RenderedPrompt base = promptTemplateService.render("extract", Map.of("content", message));
        String systemWithSchema = base.system() + "\n\n" + converter.getFormat();
        RenderedPrompt withSchema = new RenderedPrompt(base.templateId(), base.version(), base.model(), systemWithSchema, base.user()); // recode 类属性是final，所以不能直接修改ase的属性，而是重新创建一个对象
        ChatResponse raw = promptChatService.chat(withSchema);
        var result = converter.convert(raw.content());
        return new ExtractResponse(
                raw.model(),
                result,
                raw.promptTokens(),
                raw.completionTokens(),
                raw.totalTokens(),
                raw.elapsedMillis()
        );
    }
}
