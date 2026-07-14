package com.foxmimi.springaichat.service;

import com.foxmimi.springaichat.exception.ExtractBusinessException;
import com.foxmimi.springaichat.exception.ExtractRetryExhaustedException;
import com.foxmimi.springaichat.model.domain.ExtractResult;
import com.foxmimi.springaichat.model.domain.RenderedPrompt;
import com.foxmimi.springaichat.model.response.ChatResponse;
import com.foxmimi.springaichat.model.response.ExtractResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import tools.jackson.core.exc.StreamReadException;
import jakarta.validation.Validator;

import java.util.Map;

@Slf4j
@Service
public class ExtractService {

    private static final BeanOutputConverter<ExtractResult> converter = new BeanOutputConverter<>(ExtractResult.class);

    private final PromptChatService promptChatService;

    private final PromptTemplateService promptTemplateService;
    private final Validator validator;

    public ExtractService(PromptChatService promptChatService, PromptTemplateService promptTemplateService, Validator validator) {
        this.promptChatService = promptChatService;
        this.promptTemplateService = promptTemplateService;
        this.validator = validator;
    }

    public ExtractResponse extract(String message) {
        RenderedPrompt base = promptTemplateService.render("extract", Map.of("content", message));
        String systemWithSchema = base.system() + "\n\n" + converter.getFormat();
        RenderedPrompt withSchema = new RenderedPrompt(base.templateId(), base.version(), base.model(), systemWithSchema, base.user()); // recode 类属性是final，所以不能直接修改ase的属性，而是重新创建一个对象
        int tryCount = 0;
        ChatResponse raw = null;
        ExtractResult result = null;
        boolean success = false;
        while(tryCount < 3){
            raw = promptChatService.chat(withSchema);

            tryCount ++;
            try {
                result = converter.convert(raw.content());
            } catch (StreamReadException e) {
                log.error("AI 模型返回的抽取结果无法解析为 JSON，尝试重新调用模型进行抽取，当前尝试次数：{}", tryCount);
                withSchema = new RenderedPrompt(base.templateId(), base.version(), base.model(), systemWithSchema, withSchema.user() + "\n 上次输出的不是合法的JSON，请确保输出正确");
                continue;
            }
            if (!validator.validate(result).isEmpty()) {
                log.error("AI 模型返回的抽取结果未通过语义校验（Bean Validation），字段内容不符合约定格式，尝试重新调用模型进行抽取，当前尝试次数：{}", tryCount);
                withSchema = new RenderedPrompt(base.templateId(), base.version(), base.model(), systemWithSchema, withSchema.user() + "\n 字段内容不符合约定格式，尝试重新调用模型进行抽取");
                continue;
            }

            if (result.date() == null && result.name() == null && result.amount() == null) {
                throw new ExtractBusinessException("AI 模型返回的抽取结果违反业务规则：name/date/amount 三字段全部为 null，视为本次抽取未产出有效信息。", raw.content());
            }
            success = true;
            break;
        }

        if (!success) {
            log.error("AI 模型抽取重试耗尽，已尝试 {} 次仍未产出有效结果", tryCount);
            throw new ExtractRetryExhaustedException("AI 模型多次尝试后仍未能产出通过校验的抽取结果", raw.content());
        }

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
