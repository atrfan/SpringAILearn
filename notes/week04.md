# 第 4 周学习笔记：结构化输出与输出可信

> **学习日期：** 2026-07-09～
>
> **学习阶段：** 第 4 周（第一个硬性里程碑周）
>
> **文档定位：** 记录结构化输出的核心概念（Record / JSON Schema / Bean Validation 的分工、三类错误框架、失败处理链），并沉淀本周的关键设计决定（"未提及"映射、错误码方案、重试上限）。本周把 `/api/extract` 从 JSON 文本透传升级为经解析与校验的 Java 对象，并以 30+ 案例验收报告收口阶段项目一。
>
> **当前进度：** 第四周全部完成（Day22–Day28）。`ExtractServiceTest` 26 条离线参数化测试（零 API 调用）+ `ExtractIntegrationTest` 9 条真实调用端到端用例（含补齐 week03 遗留的 Day17 用例4），汇总 35 条案例写入 `docs/stage1-acceptance-report.md` 并完成三条红线自证；验收过程中发现并修复了 `ExtractService` 对 `MismatchedInputException`（真正的字段类型不匹配）捕获缺口的一处真实 bug。Day22 概念框架第 1、3、4 节与 Day28 思考题均已补齐，阶段项目一里程碑自检、技术债务清单、第五周准备清单见 Day28 记录。

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

**"透传"指什么**：服务端拿到模型吐出的文本，不做任何解析和校验，原样转发给调用方——就像一根水管，中间没有任何加工或检查。week03 时代的 `/api/extract` 就是这样：`content` 字段类型是 `String`，模型输出长得像 JSON，但没有人验证过它真的是合法 JSON、字段齐全、类型正确。

**四种情况分别炸在哪、怎么炸**：

| 情况 | 炸在哪 | 炸的方式 |
| --- | --- | --- |
| 非法 JSON | `JSON.parse()` | 必定抛异常，整个响应体解析失败，前端业务逻辑一行都还没跑到 |
| 缺字段 | 具体使用该字段的那一行（如 `response.data.date.split("-")`） | 必定抛异常——`date` 键不存在，`response.data.date` 是 `undefined`，对 `undefined` 调用方法抛 `TypeError: Cannot read properties of undefined` |
| 错类型 | 具体使用该字段的那一行 | 不确定，取决于前端具体怎么用这个字段——可能抛异常（如 `amount` 是数字却调用 `.includes()`，`Number` 没有这个方法，直接抛 `TypeError: not a function`），也可能悄悄跑出错误结果而不报错 |
| 编造值 | 无 | 全程不抛任何异常——`JSON.parse` 成功、字段都在、类型也对，只有人肉眼盯着页面数据、凭对现实的了解才能发现"这个值是错的" |

**为什么"边界处一次拦截"优于"下游各处防御"**：

1. **多消费方场景**：现在只有一个前端调用 `/api/extract`，但如果不在后端拦截，将来每新增一个下游（报表系统、别的团队的服务），都得各自重新写一遍"处理缺字段""处理错类型""处理非法 JSON"的防御逻辑——纯粹的重复劳动。
2. **没有统一标准**：不同前端团队对"这个字段可能缺""这个字段可能类型不对"的理解和处理方式不会一致，有人认真校验、有人偷懒直接用，行为不可预测；上表也说明"错类型"这一类炸不炸、怎么炸完全没法预测，丢给下游各自处理只会让这种不确定性到处蔓延。
3. **"正确的数据形状"只应该有一个权威来源**：后端离模型输出最近，天然应该是把关的位置——校验一次，所有下游（不管现在有几个、以后加几个）都能直接信任拿到的数据，不用重新自证。这和 week03 Day19 笔记的结论是一回事：**可靠防线是输出侧收敛与校验**。

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
| 格式错误 | 模型输出的文本能不能被解析成目标结构 `ExtractResult`——判断的是"整体结构对不对"，不看字段内容合不合理。对应代码里 `converter.convert(raw.content())` 这一步：JSON 语法非法、或字段类型和 Record 声明的类型对不上（如 `name` 该是字符串却给了数组/对象），都算格式错误。 | 解析层 | `name` 被模型抽成数组 `["张三"]`（探针实测抛 `MismatchedInputException`），或输出压根不是合法 JSON |
| 语义错误 | 已经能成功解析成 `ExtractResult` 对象（格式已过关），但**单个字段自己**的值不满足声明的格式约束——只看这一个字段，不需要知道其他字段是什么。对应代码里 `validator.validate(result)` 这一步，靠 `@Size`/`@Pattern` 等注解判断。语义错误建立在格式错误已排除的前提上，两者有先后顺序，代码里也是先 `convert()` 再 `validate()`。 | 校验层（Bean Validation） | `amount` 抽出 `"1200美元"`，能解析成字符串没问题，但不满足 `@Pattern(regexp = "\\d+[元￥]")` |
| 业务校验错误 | 不是"某一个字段该不该错"，而是**几个字段合在一起**说明了什么——需要联合看多个字段，且判断依据来自业务领域知识（"输入总与报销场景相关"这一前提），而非通用格式规则。`@Size`/`@Pattern` 这类注解只能表达单字段自身合不合法，没法表达"三个字段凑一起有没有意义"，所以业务规则只能单独写一段判断，放不进字段级注解里。 | 业务规则层（服务层自定义校验器） | `name`/`date`/`amount` 三字段全部为 `null`——单独看每个字段都合法（文本里确实可能没提名字/日期/金额），但三者都缺意味着这次抽取没有产出任何有效信息，基于"输入必是报销相关"的前提，这本身就值得报错 |

**为什么必须分层拦截、不能混在一起判断：** 三类错误有严格的先后依赖——没有先确认"能解析成对象"（格式层），根本没有对象可以喂给 Bean Validation（语义层）；没有先确认"每个字段自身内容合法"（语义层），业务规则去联合判断多个字段也没有意义（比如格式错误阶段 `amount` 都还没解析出来，业务层判断"三字段全空"根本无从谈起）。如果把三层混在一份代码里各处零散检查，出错时既定位不到具体在哪一层出的问题，也没法针对不同层给可区分的错误码。

## 4. 失败处理链

修复提示 → 有限重试 → 降级响应。

<!-- 用自己的话回答三个问题：
1. 修复提示回喂什么内容？（上一次的原始输出 + 具体失败原因）
2. 为什么重试必须有硬上限？（成本失控 / 无限循环）
3. 降级时为什么不能返回最后一次的原始文本？（等于把"输出不可信"的东西又透传给下游，回到起点） -->

1. **修复提示回喂的是什么**：不是"上一次模型吐出的原始文本"，也不是精确到具体字段的失败原因，而是一句**固定的、描述错误类别的提示语**——格式错误分支拼的是"上次输出的不是合法的JSON，请确保输出正确"，语义错误分支拼的是"字段内容不符合约定格式，尝试重新调用模型进行抽取"，两个分支文字不同但都很粗粒度。这不是"给模型提供样本学习"（模型参数没有任何变化），而是同一次会话里的**上下文纠错**：多加一句提示，让模型在生成下一轮回复时注意到问题所在。当前实现的精度取舍：没有带上一轮原始输出、没有具体到是哪个字段违反了哪条规则——如果后续发现重试成功率不高，这是一个可以优化的方向（把具体失败字段和规则也拼进提示里）。

2. **为什么必须有硬上限**：一是成本，模型能力有限或输入本身模糊时，无限重试意味着无限次真实调用、Token 消耗失控；二是**请求会永远悬挂**——如果这次输出天生就很难满足校验，不管调多少次都可能过不了，无限重试会让这一次 HTTP 请求永远不返回，前端用户一直等，请求线程也被一直占用，拖垮的不只是钱包，还有服务的可用性。硬上限本质是"及时止损"：与其无限期赌"模型下一次会不会突然开窍"，不如设一个数字，到点就承认这次做不到，把决定权交还给调用方。

3. **降级时为什么不能返回最后一次的原始文本**：如果把重试耗尽后的原始文本（哪怕是非法 JSON、缺字段的半成品）直接塞进对外的错误响应，本质上和 week03 时代的"JSON 文本透传"是同一件事——只是触发条件从"正常路径"变成了"失败路径"。当前实现里，原始文本只放在 `rawContent` 字段供**日志**排查用，对外的错误响应始终是我们自己写的固定文案（如"AI 模型多次尝试后仍未能产出通过校验的抽取结果"）。如果反过来把原始文本也返回给调用方，等于把"这活儿没干成"的烂摊子甩给了下游——而下游连模型都调不了，更没办法比后端更好地处理一段我们自己都判定为不可信的文本。降级的意义是"干脆利落地说清楚失败了"，而不是"半成品也一起扔过去"。

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

### 2026-07-17（Day26 + Day27 合并）

- 实际投入：
- 今日目标：Day26 离线参数化测试（20-22 条）+ Day27 端到端真实用例（8-10 条）+ 阶段验收报告，一次性完成
- 完成内容：
  - **清理遗留半成品**：`ChatControllerTest.java` 因 Day23 `ChatController` 构造器新增 `extractService` 参数后一直未修复、被整体注释掉绕过编译，本次直接删除（内容已被后续测试覆盖）；同时清掉 `target/` 目录里一个源码已不存在的 `ExtractServiceTest.class` 陈旧编译产物（导致 `mvn test` 报 `IllegalAccessError`）。
  - **探针实测澄清一个分类误区**：写了一个临时探针类调用 `BeanOutputConverter.convert()`，发现 JSON 中某个 key **整体缺失**（而非显式 `null`）会被 Jackson 静默映射为 `null`，和"未提及"走同一条路径——它不是格式错误，是正常样本的一种表达方式；这一发现让 Day26 的用例分类更准确。
  - **发现并修复一处真实 bug**：探针进一步发现，JSON 语法非法（如截断/根本不是 JSON）抛 `tools.jackson.core.exc.StreamReadException`，但字段类型真正不匹配（如 `name` 被抽成数组/对象）抛的是 `tools.jackson.databind.exc.MismatchedInputException`——它不是 `StreamReadException` 的子类，`ExtractService` 原来的 `catch (StreamReadException e)` 抓不住，会导致这类异常穿透成未分类异常，返回 `500 INTERNAL_ERROR` 而非任何 `EXTRACT_*` 错误码，违反"三类错误可区分错误码"的验收标准。已将 catch 目标改为两者共同的基类 `tools.jackson.core.JacksonException`（`ExtractService.java:12,48`）。
  - **`ExtractServiceTest`**（离线，26 条，零 API 调用）：mock `PromptChatService`/`PromptTemplateService`、用真实 `Validator`，通过伪造模型输出文本驱动格式/语义错误重试耗尽（14 条参数化）、语义错误重试成功、格式错误重试成功、业务违例三种表达方式不重试（3 条参数化）、正常样本含"未提及"两种表达与边界值（7 条参数化）。
  - **`ExtractIntegrationTest`**（真实调用，9 条）：正常样本×2、诱导语义错误、业务违例、诱导格式错误（越狱提示）、分隔符逃逸+祈使句注入、Day17 遗留用例4（classify 候选类别带空白）+ 对照组、尝试复现重试后成功。
  - **`docs/stage1-acceptance-report.md`**：汇总 35 条案例（26 离线 + 9 端到端），三条红线逐条自证。
- 产出路径：`service/ExtractService.java`（bug 修复）、`service/ExtractServiceTest.java`（新建）、`ExtractIntegrationTest.java`（新建）、`docs/stage1-acceptance-report.md`（新建）、`controller/ChatControllerTest.java`（删除）、`notes/week04.md`
- 测试或实验结果：
  - `mvn test -Dtest=ExtractServiceTest`：26/26 通过，零 API 调用。
  - `mvn test -Dtest=ExtractIntegrationTest -Dgroups=integration`：9 条真实调用，**首次真实复现"重试耗尽"路径**（用例3，语义错误 3 次重试后耗尽）；格式错误单独触发、重试后成功两条路径第三次真实尝试仍未复现，继续只靠离线测试覆盖；分隔符逃逸+祈使句注入未能攻破抽取端点（对比 week03 Day19 摘要端点被同类注入攻破），印证结构化输出的抗注入能力；Day17 遗留用例4验证 `classify` 归一化 trim 逻辑正确。
  - 全量回归 `mvn clean test`：0 失败。
- 遇到的问题：
  - 第一次批量跑 9 条真实用例时，4 条（含预期应成功的正常样本）返回了 `500 INTERNAL_ERROR`，单独重跑与整体重跑均未复现，怀疑是短时间内连续真实请求触发的瞬时网络/限流波动，非代码缺陷，已记入报告"遗留事项"观察。
  - Jackson 3.x 的包名从 `com.fasterxml.jackson` 改成了 `tools.jackson`，探针写完第一版用错了包名导致找错 jar，靠 `javap` 反编译确认真实类层级关系（`MismatchedInputException extends DatabindException extends JacksonException`）才定位到正确的公共基类。
- 明日调整：进入 Day28，阶段项目一逐项自检、回答本周思考题、整理技术债务清单与第五周准备清单。

### 2026-07-17（Day28）

- 实际投入：
- 今日目标：阶段项目一收口——里程碑自检、回答本周思考题（已完成，见第 4 节上方"本周思考题"）、技术债务清单、第五周准备清单
- 完成内容：

**阶段项目一逐项自检（对照项目一最小功能清单）**

| 功能项 | 结论 | 依据 |
| --- | --- | --- |
| 同步与流式聊天 | 达标 | `ChatController.chat()` / `chatStream()`（week02 交付，本周未改动，回归测试仍通过） |
| Prompt 模板 | 达标 | `PromptTemplateService` + `GET /api/prompts`（week03 交付） |
| Record 结构化输出 | 达标 | `ExtractResult` + `BeanOutputConverter`，`/api/extract` 返回解析后的对象而非文本（Day23，本周核心交付） |
| 参数校验与错误处理与测试 | 达标，含一处过程修复 | Bean Validation + 业务规则 + 三类可区分错误码（Day24/25）；`ExtractServiceTest` 26 条离线 + `ExtractIntegrationTest` 9 条端到端（Day26/27）；验收过程中发现并修复了 `MismatchedInputException` 穿透捕获的缺口（见 Day26+27 记录），修复后已被测试覆盖 |
| Token/延迟/调用结果记录 | 达标 | `ChatResponse`/`ExtractResponse` 均含 `promptTokens`/`completionTokens`/`totalTokens`/`elapsedMillis`，重试次数与失败样本走 `LOGGER.error`（Day25 设计决定 5.3） |

**里程碑结论**：项目一五项最小功能全部达标，`docs/stage1-acceptance-report.md` 的三条红线自证全部通过。**未完全达标/有局限项**（如实记录，不靠新功能掩盖）：

1. 格式错误单独触发、重试后成功两条路径，Day24/25/27 三次真实调用尝试均未能稳定复现，只能靠离线测试（`ExtractServiceTest`）覆盖，缺乏真实世界证据。
2. 修复提示的效果没有做过对照实验——不知道当前"粗粒度类别提示"相比"带上原始输出的精确提示"，对重试成功率有多大影响。
3. Day27 批量真实调用出现过一次瞬时批量 `500 INTERNAL_ERROR`（4/9 条），未复现、未定位根因，怀疑是短时间内连续真实请求触发的网络/限流波动。

**本周技术债务清单**

1. **格式/重试成功路径缺真实调用证据**：见里程碑结论第 1 条，三次真实尝试均未复现，长期来看应该设计更"刁钻"的输入，或考虑对模型输出做故障注入式测试来补足。
2. **修复提示效果未量化**：当前"粗粒度类别提示"是否比"精确到字段+原始输出"的提示重试成功率更高，没有做过对照实验，见里程碑结论第 2 条。
3. **摘要端点仍无输出侧防护**：week03 技术债务第 2 条延续到本周——自由文本任务没有类似结构化输出这样的低成本兜底手段，本周范围只覆盖了抽取端点。
4. **失败样本未形成自动化评估**：重试失败、业务违例等都走 `LOGGER.error` 记录，但没有汇总/告警机制，生产环境失败率异常升高不会被自动感知。
5. **批量真实调用瞬时 500 未定位根因**：见里程碑结论第 3 条，`PromptChatService`/`GlobalExceptionHandler` 目前对模型服务异常的分类可能不够细，值得后续观察是否为 DeepSeek 侧限流。
6. **集成测试无常态化运行机制**：延续 week03 技术债务第 4 条，`ExtractIntegrationTest`/`ChatIntegrationTest` 仍是手动触发，无 CI 定期跑。

**第五周准备清单（结构化输出 → 会话记忆的衔接）**

1. 会话 ID 与多轮对话：现有 `/api/chat`、`/api/extract` 都是无状态的单轮请求，第五周要引入会话维度，需要确定会话 ID 由谁生成、怎么在请求间传递。
2. Chat Memory 与业务持久化的区别：Spring AI 的 `ChatMemory` 管的是"喂给模型的历史上下文"，和业务库里落地保存的对话记录是两回事——用途、生命周期、是否需要裁剪都不同，第五周要先把这条边界想清楚，别混用。
3. 历史窗口裁剪：多轮对话历史会持续增长，何时、按什么策略裁剪（按轮次/按 Token 数）需要在接入 `ChatMemory` 之前定下来，避免重蹈本周"先写代码再补设计决定"的覆辙。
4. 确认现有分层能否直接挂会话维度：`ChatController` → `PromptChatService`/`ExtractService` 这套分层里，`RenderedPrompt` 目前是无状态的纯函数式渲染结果，引入会话历史后要确认是在 `PromptTemplateService.render()` 前拼历史，还是在 `PromptChatService.chat()` 内部处理，避免把会话状态泄漏进本该保持无状态的模板渲染层。

- 产出路径：`notes/week04.md`（本节自检/债务清单/准备清单 + 前四节反思题）、`docs/week-04-daily-plan.md` 完成定义勾选
- 测试或实验结果：本日无新增代码，收尾自检见上。
- 遇到的问题：无。
- 明日调整：第四周结束，按第五周计划进入会话记忆主题。

## 本周思考题（Day28 回答）

> 模型返回格式合法但业务事实错误时，结构化输出解决了什么，又没有解决什么？
> （提示：它保证形状正确，不保证内容真实——这条边界决定了第 7 周起 RAG 引证的必要性。）

**"格式合法但业务事实错误"是什么意思**：比如原文写的是"报销了1200元"，模型抽取结果给出 `amount: "500元"`——`"500元"` 完全满足 `@Pattern(regexp = "\\d+[元￥]")` 的格式要求，会一路通过 `converter.convert()`（格式层）和 `validator.validate()`（语义层），最终作为一个"看起来完全正常"的成功响应返回，但这个值本身是编造/错误的。这属于第 1 节表格里的"编造值"一类，不是第 3 节定义的"语义错误"——语义错误是"字段内容不满足格式规则"（如 `amount` 没写单位），事实错误是"字段内容满足所有格式规则，但内容本身与原文事实不符"，两者是完全不同的问题。

**结构化输出解决了什么**：`BeanOutputConverter` + Bean Validation 这整套机制，回答的始终是"这个值符不符合我们预先声明的形状/格式规则"——JSON 语法对不对（格式层）、单字段内容满足不满足约束（语义层）、多字段合在一起有没有意义（业务层）。这三层覆盖了本周技术债务里"抽取结果仅 JSON 文本透传"暴露的全部问题：非法 JSON、缺字段、错类型、格式不对、全字段无意义，都能被拦下并映射为可区分的错误码。

**结构化输出没有解决什么**：内容的**真实性**。Bean Validation 这类校验只能检查对象"内部自洽"（这个字符串符不符合正则），无法检查"这个值是否真的等于原文事实"——因为真实性需要一个外部参照（原文到底写的是多少钱），而不是内部形状规则。只要编造出的值恰好落在合法格式范围内，它就会一路畅通无阻地通过所有校验，最终以"成功响应"的样子出现，没有任何环节会报错，这是比"编造值全程不炸"（第 1 节表格）更进一步的认识：**不仅不炸，还会被系统判定为"完全正确"**。

**为什么这条边界指向 RAG**：要验证"抽取的金额是否真的等于原文里的金额"，需要的是让模型的输出能够引用到具体的原文出处，让"这个值哪来的"变得可核查——这已经超出"结构化输出"这个技术本身的能力范围，是第 7 周起 RAG（检索增强生成）要解决的问题：结构化输出保证**形状正确**，RAG 引证保证**内容可溯源**，两者是互补而非替代关系。

## 参考资料

- [Spring AI Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)
- [Spring AI ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Jakarta Bean Validation](https://beanvalidation.org/)
- [JUnit 5 Parameterized Tests](https://junit.org/junit5/docs/current/user-guide/#writing-tests-parameterized-tests)
- [第三周学习笔记](./week03.md)
- [第四周每日计划](../docs/week-04-daily-plan.md)
