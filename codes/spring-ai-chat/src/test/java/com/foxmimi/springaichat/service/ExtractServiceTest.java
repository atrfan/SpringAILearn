package com.foxmimi.springaichat.service;

import com.foxmimi.springaichat.exception.ExtractBusinessException;
import com.foxmimi.springaichat.exception.ExtractRetryExhaustedException;
import com.foxmimi.springaichat.model.domain.RenderedPrompt;
import com.foxmimi.springaichat.model.response.ChatResponse;
import com.foxmimi.springaichat.model.response.ExtractResponse;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExtractService} 离线单元测试（Day26）
 * <p>
 * 通过 mock {@link PromptChatService} 与 {@link PromptTemplateService}，用伪造的模型输出文本
 * 驱动 {@link ExtractService} 的解析、校验、重试与降级逻辑，全程不发起真实模型调用。
 * {@link Validator} 使用真实实现，让 {@link com.foxmimi.springaichat.model.domain.ExtractResult}
 * 上的 {@code @Size}/{@code @Pattern} 注解真实生效。
 * </p>
 * <p>
 * 用例分五类：格式/语义错误（重试耗尽路径）、语义错误重试后成功、业务违例（三字段全空，不重试）、
 * 正常样本（含"未提及"→null 与缺字段两种表达方式）。
 * </p>
 * <p>
 * <b>探针实测澄清一点分类误区</b>：JSON 中某个 key 整体缺失（而非显式 {@code null}）
 * 会被 Jackson 静默映射为 {@code null}，和"未提及"走的是同一条路径——它不是格式错误，
 * 是正常样本的一种表达形式，见 {@link #normalCases()} 的"缺字段"用例。
 * </p>
 */
class ExtractServiceTest {

    private static final RenderedPrompt FIXED_PROMPT =
            new RenderedPrompt("extract", 2, "deepseek-chat", "system-with-schema", "user-content");

    private final PromptChatService promptChatService = mock(PromptChatService.class);
    private final PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ExtractService extractService =
            new ExtractService(promptChatService, promptTemplateService, validator);

    @BeforeEach
    void setUp() {
        // extract() 内部先 render("extract", ...) 拿到基础 Prompt，再拼上 Schema；
        // 渲染逻辑本身不是本测试关注点，固定返回同一个 RenderedPrompt 即可。
        when(promptTemplateService.render(eq("extract"), any())).thenReturn(FIXED_PROMPT);
    }

    /**
     * 构造一个只关心 content 字段的伪造模型响应，其余元信息用固定值即可。
     */
    private static ChatResponse fakeChatResponse(String content) {
        return new ChatResponse("deepseek-chat", content, 10, 20, 30, 100L);
    }

    // ------------------------------------------------------------
    // 格式错误 / 语义错误：坏输出每轮都坏，重试三次后重试耗尽
    // ------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("formatOrSemanticErrorCases")
    void 坏输出重试三次后重试耗尽(String badContent, String caseName) {
        when(promptChatService.chat(any())).thenReturn(fakeChatResponse(badContent));

        assertThatThrownBy(() -> extractService.extract("随便一段输入"))
                .isInstanceOf(ExtractRetryExhaustedException.class);

        // 初次 + 最多 2 次重试 = 总共 3 次调用
        verify(promptChatService, times(3)).chat(any());
    }

    static Stream<Arguments> formatOrSemanticErrorCases() {
        return Stream.of(
                // --- 格式错误：JSON 语法非法 / 字段类型与 Record 不匹配 ---
                Arguments.of("not json at all", "格式错误-完全不是JSON"),
                Arguments.of("", "格式错误-空字符串"),
                Arguments.of("{\"name\":\"张三\",\"date\":\"2026-01-01\"", "格式错误-JSON截断缺右括号"),
                Arguments.of("\"name\":\"张三\"}", "格式错误-JSON截断缺左括号"),
                Arguments.of("```json\n{\"name\":\n```", "格式错误-markdown包裹但内容本身损坏"),
                Arguments.of("{\"name\":[\"张三\"],\"date\":null,\"amount\":null}", "格式错误-name错类型(数组)"),
                Arguments.of("{\"name\":{\"x\":1},\"date\":null,\"amount\":null}", "格式错误-name错类型(对象)"),
                Arguments.of("{\"name\":\"张三\",\"date\":null,\"amount\":[\"1200元\"]}", "格式错误-amount错类型(数组)"),
                // --- 语义错误：解析成功，但字段内容不满足校验注解 ---
                Arguments.of("{\"name\":\"这是一个超过十个字符的姓名字段\",\"date\":null,\"amount\":null}", "语义错误-name超长(超过10字符)"),
                Arguments.of("{\"name\":\"这是一个二十个字符长度姓名测试文本\",\"date\":null,\"amount\":null}", "语义错误-name严重超长(20字符)"),
                Arguments.of("{\"name\":\"张三\",\"date\":null,\"amount\":\"1200美元\"}", "语义错误-amount单位为双字符(美元)"),
                Arguments.of("{\"name\":\"张三\",\"date\":null,\"amount\":\"1200人民币\"}", "语义错误-amount单位为多字符(人民币)"),
                Arguments.of("{\"name\":\"张三\",\"date\":null,\"amount\":\"1200\"}", "语义错误-amount缺单位"),
                Arguments.of("{\"name\":\"张三\",\"date\":null,\"amount\":\"元1200\"}", "语义错误-amount单位在数字前")
        );
    }

    // ------------------------------------------------------------
    // 语义错误重试后成功：第一次坏、第二次合法
    // ------------------------------------------------------------

    @Test
    void 语义错误重试后第二次成功() {
        String bad = "{\"name\":\"张三\",\"date\":null,\"amount\":\"1200美元\"}"; // 不满足 \d+[元￥]
        String good = "{\"name\":\"张三\",\"date\":null,\"amount\":\"1200元\"}";
        when(promptChatService.chat(any()))
                .thenReturn(fakeChatResponse(bad))
                .thenReturn(fakeChatResponse(good));

        ExtractResponse response = extractService.extract("张三报销了1200美元");

        assertThat(response.data().name()).isEqualTo("张三");
        assertThat(response.data().amount()).isEqualTo("1200元");
        verify(promptChatService, times(2)).chat(any());
    }

    @Test
    void 格式错误重试后第二次成功() {
        String bad = "not json at all";
        String good = "{\"name\":\"张三\",\"date\":null,\"amount\":null}";
        when(promptChatService.chat(any()))
                .thenReturn(fakeChatResponse(bad))
                .thenReturn(fakeChatResponse(good));

        ExtractResponse response = extractService.extract("张三的报销申请");

        assertThat(response.data().name()).isEqualTo("张三");
        verify(promptChatService, times(2)).chat(any());
    }

    // ------------------------------------------------------------
    // 业务违例：三字段全空（无论以何种方式表达），不重试，直接抛异常
    // ------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("businessViolationCases")
    void 业务违例三字段全空不重试(String content, String caseName) {
        when(promptChatService.chat(any())).thenReturn(fakeChatResponse(content));

        assertThatThrownBy(() -> extractService.extract("今天天气不错"))
                .isInstanceOf(ExtractBusinessException.class);

        verify(promptChatService, times(1)).chat(any());
    }

    static Stream<Arguments> businessViolationCases() {
        return Stream.of(
                Arguments.of("{\"name\":null,\"date\":null,\"amount\":null}", "业务违例-三字段显式null"),
                Arguments.of("{}", "业务违例-三字段键全部缺失"),
                Arguments.of("{\"date\":null}", "业务违例-混合(name/amount缺失,date显式null)")
        );
    }

    // ------------------------------------------------------------
    // 正常样本：一次成功，涵盖"未提及"两种表达方式（显式 null / 缺字段）与边界值
    // ------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {4}")
    @MethodSource("normalCases")
    void 正常样本一次成功(String content, String expectedName, String expectedDate, String expectedAmount, String caseName) {
        when(promptChatService.chat(any())).thenReturn(fakeChatResponse(content));

        ExtractResponse response = extractService.extract("随便一段合法输入");

        assertThat(response.model()).isEqualTo("deepseek-chat");
        assertThat(response.data().name()).isEqualTo(expectedName);
        assertThat(response.data().date()).isEqualTo(expectedDate);
        assertThat(response.data().amount()).isEqualTo(expectedAmount);
        verify(promptChatService, times(1)).chat(any());
    }

    static Stream<Arguments> normalCases() {
        return Stream.of(
                Arguments.of(
                        "{\"name\":\"陈明\",\"date\":\"2026-01-01\",\"amount\":\"1200元\"}",
                        "陈明", "2026-01-01", "1200元", "正常样本-三字段齐全"),
                Arguments.of(
                        "{\"name\":\"陈明\",\"date\":null,\"amount\":null}",
                        "陈明", null, null, "正常样本-部分未提及(显式null)"),
                Arguments.of(
                        "{\"name\":\"陈明\"}",
                        "陈明", null, null, "正常样本-缺字段(date/amount键不存在,等价于未提及)"),
                Arguments.of(
                        "{\"name\":\"陈明\",\"date\":\"2026-01-01\",\"amount\":null}",
                        "陈明", "2026-01-01", null, "正常样本-仅amount未提及"),
                Arguments.of(
                        "{\"name\":\"一二三四五六七八九十\",\"date\":null,\"amount\":\"5元\"}",
                        "一二三四五六七八九十", null, "5元", "正常样本-name恰好10字符边界"),
                Arguments.of(
                        "{\"name\":\"李四\",\"date\":null,\"amount\":\"88元\"}",
                        "李四", null, "88元", "正常样本-amount两位数字"),
                Arguments.of(
                        "{\"name\":\"王五\",\"date\":null,\"amount\":\"500￥\"}",
                        "王五", null, "500￥", "正常样本-amount使用￥符号")
        );
    }
}
