package com.foxmimi.springaichat.service;

import com.foxmimi.springaichat.exception.PromptInputTooLongException;
import com.foxmimi.springaichat.exception.PromptTemplateException;
import com.foxmimi.springaichat.model.domain.PromptTemplateDefinition;
import com.foxmimi.springaichat.model.domain.RenderedPrompt;
import com.foxmimi.springaichat.model.response.PromptSummary;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prompt 模板服务
 * <p>
 * 负责在应用启动时从 {@code resources/prompts/} 下加载全部 YAML 模板，映射为
 * {@link PromptTemplateDefinition}，并对外提供按 id 渲染的能力。核心职责是把
 * "模板存储"与"模型调用"解耦：本类只做加载与变量替换，不接触 ChatClient。
 * </p>
 * <p>
 * 设计要点（承接第三周 Day15 变量边界结论）：
 * <ul>
 *     <li>模板从外部 YAML 文件加载，Prompt 不以裸字符串散落进代码；</li>
 *     <li>用户输入只通过变量绑定流入 User 占位符，不进入 System 约束；</li>
 *     <li>声明的变量缺失时抛出 {@link PromptTemplateException}，绝不渲染残缺 Prompt；</li>
 *     <li>输入边界统一在渲染前收口（Day19）：非字符串类型报错、超长拒绝
 *         （{@link PromptInputTooLongException}）、整行 "====" 分隔符中和，
 *         调用方无需各自重复这套防线。</li>
 * </ul>
 * </p>
 */
@Service
public class PromptTemplateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptTemplateService.class);

    /** 模板文件位置：类路径下 prompts 目录中的全部 yaml 文件 */
    private static final String TEMPLATE_LOCATION = "classpath*:prompts/*.yaml";

    /**
     * 单个模板变量的最大字符数。超限直接拒绝而不是截断：截断会悄悄改变用户数据的
     * 语义（摘要半篇文章的结果看似合理实则失真），拒绝则把取舍权留给调用方。
     * 上限取值考虑的是本项目三类任务（摘要/分类/抽取）的合理输入规模与调用成本，
     * 远小于模型上下文窗口，后续按需调整。
     */
    public static final int MAX_VARIABLE_LENGTH = 4000;

    /**
     * 数据分隔符整行匹配：行内只有 4 个及以上连续 '='（允许首尾空白）。
     * 模板骨架用 "====" 整行包裹用户数据，如果用户文本里恰好也有这样一行，
     * 模型看到的数据区会被提前"关闭"，其后的内容就可能被当作指令——这正是
     * 注入逃逸的入口，渲染前必须中和（见 {@link #neutralizeDelimiter(String)}）。
     */
    private static final Pattern DELIMITER_LINE = Pattern.compile("(?m)^[ \\t]*={4,}[ \\t]*$");

    /** id -> 模板定义。启动时一次性加载，运行期只读 */
    private final Map<String, PromptTemplateDefinition> templates = new HashMap<>();

    /**
     * 启动时加载全部模板
     * <p>
     * 逐个解析 YAML 为 {@link PromptTemplateDefinition}，按 id 去重存入内存。
     * 任一模板缺字段或 id 重复都会导致启动失败，从而尽早暴露配置问题。
     * </p>
     *
     * @throws IOException 读取模板资源失败时抛出
     */
    @PostConstruct
    void loadTemplates() throws IOException {
        // SafeConstructor 只允许解析基本的 map/list/标量，避免 YAML 反序列化出意料之外的类型
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(TEMPLATE_LOCATION);

        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                Map<String, Object> raw = yaml.load(in);
                PromptTemplateDefinition definition = toDefinition(raw, resource.getFilename());

                PromptTemplateDefinition existing = templates.putIfAbsent(definition.id(), definition);
                if (existing != null) {
                    throw new IllegalStateException(
                            "重复的模板 id [" + definition.id() + "]，冲突文件: " + resource.getFilename());
                }
                LOGGER.info("加载 Prompt 模板: id={}, version={}, file={}",
                        definition.id(), definition.version(), resource.getFilename());
            }
        }

        if (templates.isEmpty()) {
            LOGGER.warn("未在 {} 下加载到任何 Prompt 模板", TEMPLATE_LOCATION);
        }
    }

    /**
     * 渲染指定模板
     * <p>
     * 先校验模板存在、声明的变量均已提供，再用 Spring AI 的 {@link PromptTemplate}
     * 完成 User 正文的占位符替换。System 约束原样返回，不参与用户变量替换。
     * </p>
     *
     * @param templateId 模板 id
     * @param variables  运行时变量（key 为变量名）
     * @return 渲染结果，含 System 文本与替换后的 User 文本
     * @throws PromptTemplateException      模板不存在、声明的变量缺失/为空或类型非法时抛出
     * @throws PromptInputTooLongException  变量文本超过 {@link #MAX_VARIABLE_LENGTH} 时抛出
     */
    public RenderedPrompt render(String templateId, Map<String, Object> variables) {
        PromptTemplateDefinition definition = templates.get(templateId);
        if (definition == null) {
            throw new PromptTemplateException("未找到模板: " + templateId);
        }

        Map<String, Object> provided = variables == null ? Map.of() : variables;

        // 只收集模板声明过的变量：缺失的记为异常，多余的直接忽略，避免污染或触发渲染报错
        Map<String, Object> model = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : definition.variables()) {
            Object value = provided.get(name);
            // 变量缺失、为 null 或空白都视为无效，宁可报错也不发出残缺 Prompt
            if (value == null || (value instanceof String blank && blank.isBlank())) {
                missing.add(name);
                continue;
            }
            // 变量一律要求是字符串：调用方（Controller）负责把列表等结构拼成文本再传入，
            // 非字符串到达这里说明服务端代码有 bug，直接报错而不是隐式 toString 蒙混过去
            if (!(value instanceof String text)) {
                throw new PromptTemplateException(
                        "模板 [" + templateId + "] 的变量 [" + name + "] 类型非法: "
                                + value.getClass().getName() + "，必须是字符串");
            }
            if (text.length() > MAX_VARIABLE_LENGTH) {
                throw new PromptInputTooLongException(name, text.length(), MAX_VARIABLE_LENGTH);
            }
            model.put(name, neutralizeDelimiter(text));
        }
        if (!missing.isEmpty()) {
            throw new PromptTemplateException(
                    "模板 [" + templateId + "] 缺少必需变量: " + String.join(", ", missing));
        }

        // PromptTemplate是 Spring AI 提供的一个字符串模板工具。他会根据字符串模板和变量，自动渲染
        String renderedUser = new PromptTemplate(definition.user()).render(model);
        return new RenderedPrompt(
                definition.id(),
                definition.version(),
                definition.model(),
                definition.system(),
                renderedUser);
    }

    /**
     * 返回全部已加载模板的只读元数据视图，按 id 升序排列，供 {@code GET /api/prompts} 列表端点使用。
     * <p>
     * 只投影治理元数据（版本、用途、适用模型）与变量契约，刻意不含
     * {@link PromptTemplateDefinition#system}/{@link PromptTemplateDefinition#user} 正文——
     * 把"不对外暴露模板骨架"这一决定收敛在服务层，调用方（Controller）拿不到完整定义，
     * 也就无从泄露少样本等内部内容。
     * </p>
     */
    public List<PromptSummary> summaries() {
        return templates.values().stream()
                .sorted(Comparator.comparing(PromptTemplateDefinition::id))
                .map(def -> new PromptSummary(
                        def.id(), def.version(), def.purpose(), def.model(), def.variables()))
                .toList();
    }

    /**
     * 中和用户文本中的数据分隔符，防止其提前"关闭"模板骨架里的数据区。
     * <p>
     * 只处理整行都是 '='（4 个及以上）的行——这是模板中 "====" 分隔符唯一可能被
     * 冒充的形态；行中间夹杂 '=' 的正常文本（如 "a==b"、Markdown 标题下划线以外的用法）
     * 不受影响。替换方式是把命中行内的半角 '=' 换成全角 '＝'：视觉上几乎无差别、
     * 不丢内容，但对模型而言不再构成分隔符。这样既守住边界，又把对用户数据的
     * 改动降到最小（相比整体转义或直接拒绝）。
     * </p>
     */
    private String neutralizeDelimiter(String text) {
        return DELIMITER_LINE.matcher(text).replaceAll(match -> match.group().replace('=', '＝'));
    }

    /**
     * 把 YAML 解析出的原始 map 转换为强类型的模板定义，并做基础字段校验。
     */
    @SuppressWarnings("unchecked")
    private PromptTemplateDefinition toDefinition(Map<String, Object> raw, String filename) {
        if (raw == null) {
            throw new IllegalStateException("模板文件内容为空: " + filename);
        }

        String id = requireText(raw, "id", filename);
        String purpose = requireText(raw, "purpose", filename);
        String modelName = requireText(raw, "model", filename);
        String system = requireText(raw, "system", filename);
        String user = requireText(raw, "user", filename);

        Object versionValue = raw.get("version");
        if (!(versionValue instanceof Integer version)) {
            throw new IllegalStateException("模板 [" + filename + "] 的 version 必须是整数");
        }

        Object variablesValue = raw.get("variables");
        if (!(variablesValue instanceof List<?> rawVariables) || rawVariables.isEmpty()) {
            throw new IllegalStateException("模板 [" + filename + "] 的 variables 必须是非空列表");
        }
        List<String> variables = new ArrayList<>();
        for (Object item : rawVariables) {
            if (!(item instanceof String name) || name.isBlank()) {
                throw new IllegalStateException("模板 [" + filename + "] 的 variables 含有非法变量名");
            }
            variables.add(name);
        }

        return new PromptTemplateDefinition(
                id, version, purpose, modelName, List.copyOf(variables), system, user);
    }

    /**
     * 读取一个必填的字符串字段，缺失或空白时抛出，尽早暴露模板配置错误。
     */
    private String requireText(Map<String, Object> raw, String key, String filename) {
        Object value = raw.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("模板 [" + filename + "] 缺少必填字段: " + key);
        }
        return text;
    }
}
