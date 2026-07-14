# 第 4 周学习笔记：结构化输出与输出可信

> **学习日期：** 2026-07-09～
>
> **学习阶段：** 第 4 周（第一个硬性里程碑周）
>
> **文档定位：** 记录结构化输出的核心概念（Record / JSON Schema / Bean Validation 的分工、三类错误框架、失败处理链），并沉淀本周的关键设计决定（"未提及"映射、错误码方案、重试上限）。本周把 `/api/extract` 从 JSON 文本透传升级为经解析与校验的 Java 对象，并以 30+ 案例验收报告收口阶段项目一。
>
> **当前进度：** Day25 完成——`ExtractService` 加上格式/语义错误的有限重试（修复提示拼进 user，总共最多 3 次调用），业务错误保持不重试直接短路；新增 `ExtractRetryExhaustedException`/`EXTRACT_RETRY_EXHAUSTED`（502）覆盖重试耗尽场景。正常一次成功、业务错误不重试两条经真实调用验证；语义错误重试成功、重试耗尽两条因模型抽取过于准确未能复现，格式错误路径同样未复现，三者留给 Day26 离线测试验证。（Day22 概念框架的第 1、3、4 节留待补写。）

第三周的技术债务第 1 条指出：抽取结果仅做 JSON 文本透传，服务端不解析、不校验。本周的核心主题是**输出可信**——"看起来像 JSON 的文本"不等于机器可消费的结果，下游需要的是形状确定、内容经过校验的对象。这也是对 Day19 注入结论（"可靠防线是输出侧收敛与校验"）在抽取端点上的落地。

## 目录

- [1. 为什么 JSON 文本不能直接给下游](#1-为什么-json-文本不能直接给下游)
- [2. Record / JSON Schema / Bean Validation 的分工](#2-record--json-schema--bean-validation-的分工)
- [3. 三类错误框架](#3-三类错误框架)
- [4. 失败处理链](#4-失败处理链)
- [5. 本周设计决定](#5-本周设计决定)
- [每日记录](#每日记录)
- [参考资料](#参考资料)

---

## 1. 为什么 JSON 文本不能直接给下游

<!-- 用自己的话回答：透传文本时，非法 JSON、缺字段、错类型、编造值分别会在下游的什么位置爆炸？为什么"在下游各处防御"不如"在边界处一次拦截"？ -->

【待填】

### 三者的职责边界

| 组件 | 管什么 | 在链路哪一步生效 |
| --- | --- | --- |
| `ExtractResult`（Record） | 定义**目标形状**：字段名、类型、可空性。只是数据载体，本身没有行为。 | 贯穿始终，是"契约"本身 |
| `BeanOutputConverter`（Day23 落地） | 桥接模型与 Record 的**两半职责**：`getFormat()` 从 Record 生成 JSON Schema 指令，`convert()` 把模型文本反序列化为 Record。 | 请求端注入 Schema + 响应端解析 |
| Bean Validation（Day24） | 解析成功后，对字段**内容**做声明式校验。 | 解析之后、对象流出之前 |

### BeanOutputConverter 的两半职责（今天的核心）

converter 有一对方法，它们是同一个契约的两端：

- `getFormat()` —— **请求端**：生成"你必须按这个 JSON 结构输出"的指令文本，拼进 system 发给模型。
- `convert(text)` —— **响应端**：把模型吐回的文本，按同一结构解析成 `ExtractResult`。

**核心原则：契约两端必须对齐。** 响应端用什么结构解析，请求端就必须用什么结构要求；而两半都来自**同一个 converter 对象**（`new BeanOutputConverter<>(ExtractResult.class)`），所以天然一致——这正是它取代手写 JSON 约定的价值。

> 今天卡过的坑：只调 `convert()` 不调 `getFormat()`，相当于"出口按严格表格读数据，入口却从没把表格发出去"——模型不知道该输出什么结构，`convert()` 大概率解析失败。**注入 Schema（`getFormat()` 拼进 system）这一步不能省。**

另一个易混点：**converter 的泛型对应"模型输出的数据形状"（`ExtractResult`），不是响应体 `ExtractResponse`**。后者是"数据 + 我们事后补的元信息（model/token/耗时）"，模型不该也无法输出这些。

### 单一事实来源

原来 `extract.yaml` 里手写"键为 name/date/amount"，一旦 Record 改字段、忘了改模板，两边就脱节。改用 `getFormat()` 后，Schema 从 `ExtractResult` **自动生成**：结构只在 Record 定义一次，请求指令与解析逻辑都从它派生，改 Record 则 Schema 自动同步。

### 一条链（本周整体）

```
提示词(system + 注入的 Schema) → 模型输出文本 → convert() 解析(格式层)
    → Bean Validation(语义层, Day24) → 业务规则(Day24) → 对象流出
```

## 3. 三类错误框架

本周核心概念框架。Day24 的错误码映射、Day26 的 20+ 条离线用例都按这个分类组织。

| 错误类别 | 定义（自己的话） | 拦截层 | 抽取场景例子 |
| --- | --- | --- | --- |
| 格式错误 | 【待填】 | 解析层 | 【待填，如：输出被 markdown 代码块包裹导致解析失败】 |
| 语义错误 | 【待填】 | 校验层（Bean Validation） | 【待填，如：amount 抽出 "-1200元"】 |
| 业务校验错误 | 【待填】 | 业务规则层（服务层自定义校验器） | 【待填，如：有金额但日期缺失】 |

<!-- 每类补一句"为什么必须在这一层拦、放到别的层拦为什么不对"。 -->

## 4. 失败处理链

修复提示 → 有限重试 → 降级响应。

<!-- 用自己的话回答三个问题：
1. 修复提示回喂什么内容？（上一次的原始输出 + 具体失败原因）
2. 为什么重试必须有硬上限？（成本失控 / 无限循环）
3. 降级时为什么不能返回最后一次的原始文本？（等于把"输出不可信"的东西又透传给下游，回到起点） -->

【待填】

## 5. 本周设计决定

### 5.1 ExtractResult Record 与"未提及"映射决定

现状：`extract.yaml` v1 约定字段未提及时输出字符串 `"未提及"`（哨兵值）。映射到 Record 后必须做选择，这个决定影响后续所有校验注解的写法。

候选方案：

| 方案 | 优点 | 代价 |
| --- | --- | --- |
| 映射为 `null` | 校验注解可用"可空、非空时须满足 X"的自然写法 | 需改模板约定让模型输出 JSON `null`，少样本同步改 |
| 保留哨兵字符串 | 模板不用动 | 字段永远非空，所有校验注解都要绕开哨兵值，下游每次判一遍 |
| `Optional` 字段 | 语义最明确 | Jackson 对 Record + Optional 需额外配置，校验注解写法更绕 |

**决定：** 未提及的字段映射为 **`null`**；三个字段均为 `String`（`amount` 保留原文写法，如 `"1200元"`，不拆数值与币种）。哨兵字符串 `"未提及"` 只是模型的输出约定，进入 Java 边界后必须消失——由 Day23 改模板让模型对未提及字段输出 JSON `null`。

**理由：**

1. **Bean Validation 的语义天然围绕 `null` 展开**，映射为 `null` 能让 Day24 的校验注解用最自然的写法：
   - 必填字段加 `@NotNull` / `@NotBlank`；
   - 可空字段不加 `@NotNull`，而 `@Pattern`、`@Size`、`@Positive` 等约束对 `null` 默认放行——正好等于"未提及就跳过、提及了才校验格式"的需求，无需任何特判。
2. **拒绝哨兵字符串泄漏**：若保留 `"未提及"`，字段永远非空，每个校验注解都要特意排除这个魔法值，且下游每处都得写 `if (!"未提及".equals(x))`，把一个模型约定扩散成全链路的隐性契约。`null` 把"缺失"的判断收敛在边界一次完成。
3. **不选 `Optional`**：语义虽最明确，但 Jackson 对 `record` + `Optional` 反序列化需 `Jdk8Module`，且校验要写成 `Optional<@Pattern(...) String>` 的容器元素约束，工程成本与本周收益不匹配。可空性用 `@NotNull` 的有无表达已经足够。
4. **`amount` 保留 `String` 原文**：模型直接吐数值容易丢币种、被千分位/中文数字干扰而更不稳定。Day24 的"数值性"校验用 `@Pattern`（数字 + 可选单位）承担即可；是否拆成 `BigDecimal amount + String currency` 留到确有下游计算需求时再说，不在本周过度设计。

**未提及映射对可空性的影响（Day24 落地时据此写注解）：**

- `name` / `date` / `amount` 三者都可能"未提及" → 都是**可空**字段，均不强制 `@NotNull`。
- "三者全空说明这条抽取无实质结果"属于**业务约束**（见 5.4），放到服务层校验，不在字段注解里表达。

**Record 定稿（`model` 包，已落地并通过真实调用验证）：**

```java
public record ExtractResult(
        String name,    // 人物姓名；未提及 -> null
        String date,    // 事件日期，保留原文写法；未提及 -> null
        String amount   // 金额，含币种/单位的原文（如 "1200元"）；未提及 -> null
) {}
```

- **踩坑记录**：初版把 `date` 误写成 `data`。Record 字段名靠 Jackson 匹配 JSON 键，名字对不上会**静默**映射失败（字段恒为 null、不报错），是最难查的一类 bug。
- **验证结论**：真实调用「陈明提交了一份报销申请。」返回 `{name:"陈明", date:null, amount:null}`——`date`/`amount` 是 JSON `null` 而非字符串，"未提及→null" 整条链路成立。

> **Day23 联动（已完成）：** `extract.yaml` 升到 v2——删除手写的 JSON 结构约束（交给 Schema）、"未提及"改为输出 JSON `null`、第二条少样本输出改为 `{"name":null,"date":null,"amount":null}`。提示里要明确写 JSON `null`（空值字面量）而**非**字符串 `"null"`：否则反序列化出的是字符串 `"null"`，会绕过 null 语义、泄漏成哨兵值。

### 5.2 三类错误的错误码方案

复用 `ErrorResponse` 结构。Day25 的"重试耗尽"错误码一并定下。

| 错误类别 | 错误码 | HTTP 状态 | 触发条件 |
| --- | --- | --- | --- |
| 格式错误（解析异常） | `EXTRACT_FORMAT_ERROR` | 502 | 输出非法 JSON / 缺字段 / 错类型 |
| 语义错误（Bean Validation 违例） | `EXTRACT_SEMANTIC_ERROR` | 502 | 字段内容不合理 |
| 业务校验错误（自定义校验器违例） | `EXTRACT_BUSINESS_ERROR` | 502 | 返回的 json 字段中全为 null |
| 重试耗尽（无法可靠解析） | `EXTRACT_RETRY_EXHAUSTED` | 502 | 达到重试上限仍未通过解析与校验 |

<!-- 命名风格与项目里已有错误码保持一致；HTTP 状态想清楚"是调用方的错还是模型/服务端的错"。 -->

### 5.3 重试上限与失败样本记录形式

- **重试范围：** 只有格式错误（解析失败）和语义错误（Bean Validation 违例）进入重试循环——这两类是"模型这次没输出对"，换一轮调用/带上修复提示有机会纠正。业务错误（三字段全空）不重试——这属于"输入文本本身与报销场景无关"，重试也大概率仍是空，直接短路返回 `EXTRACT_BUSINESS_ERROR`。
- **修复提示：** 把上一轮失败原因拼进下一轮的 **user** 文本（而非 system），让模型看到自己上次错在哪。
- **重试上限：** 总共最多 3 次调用（初次 + 最多 2 次重试）。
- **失败样本记录形式：** 循环内部每次失败直接用 `LOGGER.error` 记（不新增字段、不落地文件），记录本轮的错误类别与重试轮次；最终重试耗尽才对外抛 `EXTRACT_RETRY_EXHAUSTED`，返回给前端。
- **可观测性约定：** 不在 `ExtractResponse` 加字段，重试次数只体现在日志里。

### 5.4 字段间业务约束（Day24 落地）

`name`/`date`/`amount` 三个字段之间没有天然的逻辑约束，单看字段本身，任何组合（只有一个字段、甚至一个都没有）都是合理的——因为输入文本内容本身是任意的、不受限的。

但本接口的前提假设是：**输入总是与报销场景相关的描述**。基于这个前提，若三字段全部为 `null`，说明这次抽取没有产出任何有效信息——不管具体原因是原文本身信息不足、还是模型没理解好，在"输入必是报销相关"这个假设下，全空结果本身就值得报错让调用方知道，而不是静默放行一个空对象给下游，属于业务规则违反（`EXTRACT_BUSINESS_ERROR`）。

## 每日记录

### 2026-07-09（Day22）

- 实际投入：
- 今日目标：建立结构化输出概念框架，完成 Record 设计、"未提及"映射决定、错误码方案、重试上限决定
- 完成内容：
- 产出路径：`notes/week04.md`
- 测试或实验结果：（今日无编码，不适用）
- 遇到的问题：
- 明日调整：进入 Day23，定义 `ExtractResult` Record，用 `BeanOutputConverter` 改造 `/api/extract`，调整 `extract.yaml`（红线：不允许字符串截取或正则"猜"JSON）

### 2026-07-10（Day23）

- 实际投入：
- 今日目标：用 `BeanOutputConverter` 把 `/api/extract` 升级为返回 `ExtractResult` 结构化对象，格式约束改由 Schema 承担
- 完成内容：
  - 新建 `ExtractResult` Record（三个 `String` 字段，未提及→null）；
  - `extract.yaml` 升 v2：删手写 JSON 结构约束、哨兵值改 JSON `null`、少样本同步改；
  - 新建 `ExtractService`，走**路线 B（手动 `BeanOutputConverter`）**：`getFormat()` 拼到 system 末尾 → `new` 一个含 Schema 的 `RenderedPrompt`（record 不可变，只能造新的）→ `promptChatService.chat()` 拿原始文本 → `convert()` 解析 → 组装 `ExtractResponse`（data + 模型/token/耗时元信息）；
  - `ChatController.extract()` 改为返回 `ExtractResponse`，render 从 Controller 搬进 Service（因为拼 Schema 依赖 converter，是 Service 的内部知识）。
- 产出路径：`model/ExtractResult.java`、`model/ExtractResponse.java`、`service/ExtractService.java`、`controller/ChatController.java`、`resources/prompts/extract.yaml`
- 测试或实验结果：`mvnw compile` BUILD SUCCESS；手动调 `/api/extract` 两例——齐全样本三字段抽全，部分未提及样本 `date`/`amount` 返回 JSON `null`，均符合预期。
- 遇到的问题：
  - 三处"编译能过、静默出错/渲染报错"的低级但典型错误：`date` 误写 `data`（静默映射失败）、模板 id 误写 `extractw`（render 找不到模板）、换行符写成 `/n/n`（应为 `\n\n`）。
  - 路线选择：选**路线 B（手动 converter）**而非 `ChatClient.entity()`，因为要把模型**原始文本留在手里**，为 Day25 的"失败回喂重试"铺路；entity() 会把原始文本吞在框架内部。
  - `chat()` 是不懂业务的"搬运工"，只把 `RenderedPrompt` 的 system/user 原样发出——所以 Schema 必须在 `chat()` **之前**拼进 system，它不会主动加。
- 明日调整：进入 Day24，三层校验——补 `spring-boot-starter-validation`、给 `ExtractResult` 加校验注解（可空字段不加 `@NotNull`，`@Pattern`/`@Size` 对 null 放行）、加一条服务层业务约束、`GlobalExceptionHandler` 映射格式/语义/业务三类可区分错误码。

### 2026-07-13（Day24）

- 实际投入：
- 今日目标：落地三层校验——格式层（解析异常）、语义层（Bean Validation）、业务层（自定义规则），并让三类错误返回可区分的错误码
- 完成内容：
  - 补 `spring-boot-starter-validation` 依赖；
  - `ExtractResult` 加校验注解：`name` 加 `@Size(max = 10)`，`amount` 加 `@Pattern(regexp = "\\d+[元￥]")`（数字+单位，二者对 null 均默认放行），`date` 保持无约束（prompt 未对格式做任何约定）；
  - 新建 `ExtractFormatException` / `ExtractSemanticException` / `ExtractBusinessException` 三个异常类，均带 `rawContent` 字段（不参与 `getMessage()`，只供日志使用，避免原始模型输出泄漏给客户端）；
  - `ExtractService.extract()` 接入 `jakarta.validation.Validator`，按"格式解析 → 语义校验 → 业务规则（三字段全空）"顺序落地三层校验，全部只负责抛异常、不打日志；
  - `GlobalExceptionHandler` 新增三个独立 `@ExceptionHandler`，分别返回 502 + `EXTRACT_FORMAT_ERROR` / `EXTRACT_SEMANTIC_ERROR` / `EXTRACT_BUSINESS_ERROR`，日志集中在这里用 `exception.getRawContent()` 打印原始响应。
  - 同步补了 5.2（错误码方案：统一 502，靠字符串错误码区分）、5.4（业务约束：假设输入总与报销相关，三字段全空视为抽取失败）两处设计决定。
- 产出路径：`model/ExtractResult.java`、`exception/ExtractFormatException.java`、`exception/ExtractSemanticException.java`、`exception/ExtractBusinessException.java`、`service/ExtractService.java`、`exception/GlobalExceptionHandler.java`、`notes/week04.md`
- 测试或实验结果：`mvnw compile` 通过；手动调用 `/api/extract` 三例——
  - 业务错误：输入与报销无关文本（"今天天气不错…"），三字段全 null，返回 `EXTRACT_BUSINESS_ERROR`，符合预期；
  - 语义错误：输入"报销了1200美元"，模型抽出 `amount:"1200美元"`（"美元"两字符不满足 `[元￥]` 单字符单位），返回 `EXTRACT_SEMANTIC_ERROR`，符合预期；
  - 格式错误：用提示注入尝试让模型跳出 JSON 输出，模型仍遵守 Schema 输出合法 JSON（三字段全 null），未能触发 `EXTRACT_FORMAT_ERROR`——格式错误路径依赖模型偶然输出非法 JSON，无法通过真实调用稳定复现，留给 Day26 离线测试用伪造字符串直接验证。
- 遇到的问题：
  - `@Pattern` 没有名为 `value` 的属性，必须显式写 `regexp = "..."`，写成 `@Pattern("...")` 直接编译不过；
  - 正则里的反斜杠在 Java 字符串字面量中要二次转义（`\d` 非法，须写 `\\d`）；
  - 字符类 `[...]` 内部的 `|` 不表示"或"，会被当成普通候选字符（`[元|￥]` 实际是"元/|/￥ 三选一"）——"或"关系只能靠列举字符本身表达，多字符候选要跳出字符类用分组 `(元|人民币|美元)`；
  - 误注入 `org.springframework.validation.Validator`（Spring 自己的校验接口）而非 `jakarta.validation.Validator`（Bean Validation 标准接口），两者互不相关，前者读不到 `@Size`/`@Pattern` 注解；
  - 对可能为 `null` 的字段调用 `.isEmpty()` 直接 NPE——"未提及→null"这条 Day23 决定必须贯彻到所有判空逻辑，不能想当然用字符串判空方法；
  - 一度把三个异常合并进一个 `@ExceptionHandler` 共用 `EXTRACT_ERROR` 一个错误码，违反了"可区分错误码"的验收标准，拆回三个独立 handler；
  - 原始模型响应内容不能直接拼进异常 `message`（会被 Handler 透给客户端），但 Service 层按项目既有约定不该打日志——最终方案是给异常类加独立的 `rawContent` 字段，`getMessage()` 只给客户端安全文案，日志统一收到 `GlobalExceptionHandler` 里用 `getRawContent()` 打印。
- 明日调整：进入 Day25，有限重试 + 修复提示 + 失败样本记录；重试耗尽降级为独立错误码（不透传原始文本）；重试次数需可观测。

### 2026-07-14（Day25）

- 实际投入：
- 今日目标：给格式错误、语义错误加有限重试 + 修复提示，业务错误保持不重试；重试耗尽返回独立错误码
- 完成内容：
  - 补 5.2（重试耗尽错误码 `EXTRACT_RETRY_EXHAUSTED`，502）、5.3（重试范围只含格式/语义两类、修复提示拼进 user、重试上限总共 3 次调用、失败样本用 `LOGGER.error` 记在循环内部、不额外加可观测性字段）两处设计决定；
  - `ExtractService.extract()` 改造为重试循环：每轮重新调用模型 → 格式/语义失败则把失败原因拼进下一轮 user 文本、`continue`；业务规则违反（三字段全空）不重试、直接抛异常跳出循环；三者都通过则标记 `success = true` 并 `break`；
  - 循环跑满 3 次仍未成功（`success` 为 `false`）时，抛新建的 `ExtractRetryExhaustedException`；
  - `GlobalExceptionHandler` 新增 handler，映射 502 + `EXTRACT_RETRY_EXHAUSTED`；
  - 清理：改造后 `ExtractFormatException`/`ExtractSemanticException` 不再被 `ExtractService` 直接抛出，移除了这两个已用不上的 import 与对应的注释死代码。
- 产出路径：`service/ExtractService.java`、`exception/ExtractRetryExhaustedException.java`、`exception/GlobalExceptionHandler.java`、`notes/week04.md`
- 测试或实验结果：`mvnw compile` 通过；手动调用 `/api/extract` 验证——
  - 正常一次成功（齐全样本）：符合预期，未触发任何重试分支；
  - 业务错误（三字段全空）：符合预期，不进重试循环，直接返回 `EXTRACT_BUSINESS_ERROR`；
  - 语义错误重试后修复成功、重试耗尽（`EXTRACT_RETRY_EXHAUSTED`）：均未能复现——模型对测试样本的抽取一直很准确，没能稳定触发语义/格式失败，同格式错误一样留给 Day26 离线测试用伪造字符串直接验证。
- 遇到的问题：
  - 第一版格式错误分支 catch 住异常后忘了 `continue`，会带着上一轮的脏 `result`（甚至是 `null`）往下走到语义校验，`validator.validate(null)` 直接抛未处理的 `IllegalArgumentException`，而不是进入重试；
  - 修复提示一度被拼进了 system 而不是设计决定里定的 user；
  - `ExtractRetryExhaustedException`/`GlobalExceptionHandler` 的 handler 都先写好了，但一开始没有任何地方真正抛出这个异常——循环耗尽后会直接带着不合法的 `result` 走到 `return`，需要额外一个 `success` 标志位来区分"循环是靠 break 提前退出成功的，还是 3 次跑完仍未成功"；
  - `ExtractRetryExhaustedException` 的 Javadoc 最初是从 `ExtractBusinessException` 复制的，描述对不上，已改正。
- 明日调整：进入 Day26，20–22 条离线参数化测试，覆盖缺字段/错类型/非法值/超长/业务违例/正常样本五类；重点补上今天真实调用没能复现的三条路径（格式错误、语义错误重试成功、重试耗尽）；默认 `mvn test` 零 API 调用。

## 本周思考题（Day28 回答）

> 模型返回格式合法但业务事实错误时，结构化输出解决了什么，又没有解决什么？
> （提示：它保证形状正确，不保证内容真实——这条边界决定了第 7 周起 RAG 引证的必要性。）

【待填】

## 参考资料

- [Spring AI Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)
- [Spring AI ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Jakarta Bean Validation](https://beanvalidation.org/)
- [JUnit 5 Parameterized Tests](https://junit.org/junit5/docs/current/user-guide/#writing-tests-parameterized-tests)
- [第三周学习笔记](./week03.md)
- [第四周每日计划](../docs/week-04-daily-plan.md)
