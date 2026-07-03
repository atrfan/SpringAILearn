package com.foxmimi.springaichat.service;

import com.foxmimi.springaichat.exception.PromptTemplateException;
import com.foxmimi.springaichat.model.PromptSummary;
import com.foxmimi.springaichat.model.PromptTemplateDefinition;
import com.foxmimi.springaichat.model.RenderedPrompt;
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
 *     <li>声明的变量缺失时抛出 {@link PromptTemplateException}，绝不渲染残缺 Prompt。</li>
 * </ul>
 * </p>
 */
@Service
public class PromptTemplateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptTemplateService.class);

    /** 模板文件位置：类路径下 prompts 目录中的全部 yaml 文件 */
    private static final String TEMPLATE_LOCATION = "classpath*:prompts/*.yaml";

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
     * @throws PromptTemplateException 模板不存在，或声明的变量缺失/为空时抛出
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
            // 注：超长文本截断、非法类型等更完整的输入边界处理属于 Day19 范围
            if (value == null || (value instanceof String text && text.isBlank())) {
                missing.add(name);
            } else {
                model.put(name, value);
            }
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
