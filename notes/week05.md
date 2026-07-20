# 第 5 周学习笔记：会话记忆与多轮对话

> **学习日期：** 2026-07-16～
>
> **学习阶段：** 第 5 周（新阶段起步：会话维度）
>
> **文档定位：** 记录会话记忆的核心概念（ChatMemory vs 业务持久化、窗口裁剪、advisor 注入机制），并沉淀本周关键设计决定（conversationId 传递、窗口策略、分层归属）。本周把无状态单轮请求升级为按 conversationId 组织的多轮对话，只做内存版。

## 核心概念：ChatMemory vs 业务持久化

同样是"存对话"，两者是两个东西，三个维度对比：

| 维度 | `ChatMemory`（Spring AI 的） | 业务持久化（自己落库的对话记录） |
| --- | --- | --- |
| **用途** | 给 **AI** 看——让模型知道我们对话的上下文，会被塞进下一次调用的上下文里 | 给 **用户** 看（补：也用于审计/追溯/统计，总之给人/业务系统看，不进模型上下文） |
| **生命周期** | 按决定 2，只保留最近 **5 轮**（窗口满即滚动） | 数据库里**一直保留**，不因窗口满而删 |
| **是否裁剪** | 会——超过 5 轮的旧消息被裁掉 | 不会——完整留存 |

> 一句话：ChatMemory 保证的是"最近的上下文"、不保证完整；业务持久化要的恰恰是完整。这和第 4 周"结构化输出保证形状、不保证内容真实"是同一个方向——每种机制只负责它那一层的保证，别指望它兼管别的。

## 本周设计决定

### 决定 1：conversationId 由前端生成（方案 A）

**决定：** 采用**方案 A——前端生成 UUID**，每轮请求都带上同一个 id。

**理由（选 A 而非 B 的真正依据）：** 无状态 + 简单。前端造好 id 才发请求，服务端从头到尾不维护"下一个 id 发几"这类状态，也没有方案 B"首轮 id 为空"的边界尴尬。这与项目一贯的无状态原则一致。

**软肋与豁免（不是选 A 的理由，是对 A 缺点的处理）：** A 的唯一软肋是客户端可任意指定 id、包括冒用他人的 conversationId 读到别人历史。当前是单用户、无登录鉴权的学习环境，该风险不成立，故本周不处理——但**记为技术债**（见下），而非丢弃。

> **思维复盘：** 初次表述曾把"单用户无盗用风险"当成选 A 的理由，实际它只是对 A 缺点的豁免。选型理由（无状态）和缺点豁免（风险不成立）是两回事，不能混。

**关联技术债（本周待汇总时纳入）：** 生产环境接入登录鉴权后，必须补 conversationId 的**归属校验**（服务端确认该 id 属于当前用户），否则方案 A 存在跨用户读取历史的越权风险。

### 决定 2：历史窗口 `maxMessages = 12`（带 1 条 system，保留最近 5 轮）

**决定：** 会话端点**带 system 消息**；`MessageWindowChatMemory` 的 `maxMessages` 定为 **12**，目标是稳定保留最近 **5 轮**对话。

**算式（核实到源码后的正确算法）：** `maxMessages` 封顶的是**含 system 在内的消息总数**，system 消息**不被逐出但照样占坑**。所以：5 轮 = 10 条非 system + 1 条 system = **11 条**是装下"1 system + 5 轮"的算术下限；再 **+1 坑缓冲 = 12**，用来吸收源码里"窗口必须从完整一轮（USER）起"的对齐逻辑——它在裁剪时可能为对齐多删一条 assistant，导致答话时实际只可见 4 轮。12 能让答话时稳定可见 5 轮。

**关键认知纠错：** 曾假设"system 单独保留、不占额度"，抠 `MessageWindowChatMemory.process()` 逐字源码后确认**反了**——`cutIndex = processedMessages.size()(含 system) - maxMessages`，即总数封顶、system 占坑，只是永不被删。第一次抓文档给的"不占额度、5+20=25"结论是错的，靠核实到源码才没把错传下去。

**诚实边界（Day30 需实测）：** `process()` 裁剪逻辑已定论；但"答话瞬间可见几轮"还取决于 `MessageChatMemoryAdvisor` 先存 user 还是先取历史的调用时序，这部分**未**核实到源码。故 12 是"下限 11 + 一坑保险"，真实轮数留到 Day30 接线时用真实对话数一遍确认。注：核实用的是 `main` 分支源码，需复核 2.0.0 是否一致。

### 决定 3：方式 B——建独立的 `conversationChatClient` bean 承载记忆

**决定：** 采用**方式 B**。新建一个带 `MessageChatMemoryAdvisor` 的独立 `ChatClient` bean（如 `conversationChatClient`），只注入给新的会话 service；`OpenAIConfig` 里原有的 `ChatClient` bean 保持无 advisor，用 **`@Primary`** 标记，`MyChatService` / `PromptChatService` 一行不改，继续注入这个 primary bean 保持无状态。

**装配现实（决定的地基）：** 当前只有一个 `ChatClient` bean，被 `MyChatService`（`/api/chat`、`/api/chat/stream`）和 `PromptChatService`（summarize/classify/extract）**共享注入**。若直接给它加 `defaultAdvisors`，记忆会污染全部老端点，无状态语义当场破坏——这是必须绕开的陷阱。

**为什么 B 而非 C：** 本场景是**静态二分**——会话端点永远有记忆、其余端点永远无记忆。这类"按用途分"最贴 B：多 ChatClient bean 是 Spring AI 的成熟模式（按用途/人格各配一个专用 client），调用点保持干净（不用每次记着挂 advisor，漏挂即退化成无状态 bug），"谁有记忆"写在装配层一眼可见。C（单 bean、按请求挂 advisor）更适合"动态按调用决定要不要记忆"的场景，不是本例。

**消歧方案：** 两个 `ChatClient` bean 会让 Spring 注入起歧义，用 **`@Primary`** 标老 bean（默认注入它，老 service 无感）；新会话 service 用 `@Qualifier("conversationChatClient")` 精确点名带记忆的那个。

**装配要点（Day30 落地时照此写）：**
- `chatMemory` bean：`MessageWindowChatMemory.builder().maxMessages(12).build()`（内存版，默认 `InMemoryChatMemoryRepository`）。
- `conversationChatClient`：`.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())`。
- 会话 service 每轮调用通过 `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))` 传入本轮 id。

### 决定 4：缺失 / 非法 conversationId 一律拒绝，只校验非空白

**决定：** conversationId 缺失、空白或非法时 → **直接返回结构化错误（Day32 落地，如 400），不处理**，不补生成。校验强度：**只要求非空白**，不强校 UUID 格式。

**理由：**
- **拒绝而非后端补生成**：与决定 1"前端拥有 id 的生成职责"自洽——没带好 id 就是客户端违约，挡在门外最安全，杜绝"缺 id 静默共用同一条历史"的串话红线（计划硬红线）。后端补生成会把生成职责拉回服务端，退回半个方案 B，立场拧巴。
- **因决定 1 消解的问题**：选 A 后"首轮 id 为空"不存在（前端总是先造 id 再发），所以本决定只需覆盖"客户端 bug/手搓请求导致 id 缺失"这一种异常路径。
- **只校验非空白**：非空白是最小护栏；不强校 UUID 格式，因为"防冒用/防伪造 id"整体已作为技术债留到有鉴权时（见决定 1 技术债），本周学习环境下强校格式属过度设计。

**Day32 落地提示：** 该拒绝逻辑接进 `GlobalExceptionHandler`，复用 `ErrorResponse` 结构与既有错误码风格；错误应结构化，不返回堆栈。

## ChatMemory 与 Advisor 的协作机制（对照 Spring AI 官方文档）

> 参考：[Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)、[Advisors](https://docs.spring.io/spring-ai/reference/api/advisors.html)。本节解释"记忆为什么不用手工拼、advisor 到底替我们做了什么"，是决定 3、以及 Day30 实测 token 37→62 的原理。

### 1. Advisor 是什么

Advisor 是 Spring AI 用来**拦截、改写、增强**与模型交互的机制，把"每次调用前后都要做的固定动作"（如注入历史、记日志）封装起来。核心接口：

```java
public interface Advisor extends Ordered {
    String getName();
}
```

分同步 / 流式两支：`CallAdvisor`（`adviseCall`，非流式）与 `StreamAdvisor`（`adviseStream`，返回 `Flux`）。

**around 拦截模型**：每个 advisor 拿到请求后，可以 before 改请求 → 调 `chain.nextCall(...)` 交给链上下一环 → after 改响应：

```java
public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
    // BEFORE：改请求
    ChatClientResponse resp = chain.nextCall(req);   // 交给下一环，最后一环发给模型
    // AFTER：改响应
    return resp;
}
```

多个 advisor 组成**链**，靠 `getOrder()` 排序：值小的先处理请求、后处理响应（栈式）。advisor 也可以**不调 nextCall 直接返回**，从而拦截整个请求。

### 2. MessageChatMemoryAdvisor 替我们做的三件事（记忆自动读写）

这正是"**为什么代码里不许出现手工拼历史**"的原因——advisor 在一次调用里自动完成：

1. **before（调模型前）**：用 `chatMemory.get(conversationId)` 把该会话历史取出来；
2. **注入**：作为**消息对象列表**塞进本次 prompt；
3. **after（调模型后）**：用 `chatMemory.add(conversationId, ...)` 把本轮的 user 输入与模型回复**写回**记忆。

> **对上 Day30 实测**：第二轮 `promptTokens` 从 37 涨到 62，多出来的正是 before 阶段被注入的第一轮历史——advisor 在背后读历史、拼进上下文，我们的 `ConversationService` 一行历史代码都没写。这就是"记忆有成本"的机制来源。

### 3. 两个内置记忆 advisor 的区别

| Advisor | 历史以什么形式注入 | 取自哪 |
| --- | --- | --- |
| **`MessageChatMemoryAdvisor`**（本项目用） | 作为**消息对象列表**加进 prompt（结构化） | `ChatMemory`（本项目=内存窗口） |
| `VectorStoreChatMemoryAdvisor` | 作为**文本**追加进 system message（模板含 `instructions` / `long_term_memory` 占位） | 向量库（长期记忆语义检索） |

本项目要的是"最近几轮的结构化上下文"，用 `MessageChatMemoryAdvisor`；`VectorStoreChatMemoryAdvisor` 属于跨会话长期记忆，超出本周范围。

### 4. conversationId 怎么传 + 它是强制的

每轮调用通过 advisor 参数传入，官方标准写法：

```java
chatClient.prompt()
    .user(userText)
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
    .call().content();
```

- 参数经由 `adviseContext` 在 advisor 链上传递；
- **官方明确：省略 `CONVERSATION_ID` 会在运行时抛 `IllegalArgumentException`，没有默认会话 id。** → 这反过来印证决定 4 的价值：与其让 advisor 抛一个原始运行时异常（落到兜底 → 500），我们在 Controller 提前做**非空白校验**，把这个硬性要求转成友好的 **400**。

### 5. 两种装配方式（对照决定 3）

官方给了两种挂 advisor 的方式，正是决定 3 里 B/C 的来源：

```java
// 方式 B（本项目）：建 client 时挂 defaultAdvisors，该 client 的每次调用都带记忆
ChatClient.builder(chatModel)
    .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
    .build();

// 方式 C：单 client，按请求临时挂
chatClient.prompt().advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))...
```

本项目按决定 3 选了 B——独立 `conversationChatClient` bean + `defaultAdvisors`，隔离写在装配层。

## 每日记录

### 2026-07-16（Day29）

- 今日目标：会话记忆四个设计决定（纯设计无代码）
- 完成内容：决定 1–4 + ChatMemory vs 业务持久化概念辨析，见上文各节
- 产出路径：`notes/week05.md`；已提交 commit fb047a1
- 遇到的问题：把"单用户无盗用风险"误当成选方案 A 的理由（实为对缺点的豁免），已在决定 1 复盘
- 明日调整：进 Day30，把四个决定落地成代码

### 2026-07-20（Day30）

- 实际投入：
- 今日目标：装配 `ChatMemory` + 独立多轮会话端点，验证跨轮记忆，不破坏无状态端点
- 完成内容：
  - 新增 `ConversationRequest`（message + conversationId）、`ConversationResponse`（复用 `ChatResponse` 字段 + conversationId 自描述）；
  - `OpenAIConfig` 三个 `@Bean`：`chatClient`（`@Primary`，无 advisor）、`chatMemory`（`MessageWindowChatMemory` maxMessages=12）、`conversationChatClient`（挂 `MessageChatMemoryAdvisor`）；
  - `ConversationService`：`@Qualifier("conversationChatClient")` 注入带记忆 client，system 消息，每轮传 `CONVERSATION_ID`，无手工拼历史；
  - `ConversationController`：`POST /api/conversation`，message + conversationId 非空白校验。
- 产出路径：`model/request/ConversationRequest.java`、`model/response/ConversationResponse.java`、`service/ConversationService.java`、`controller/ConversationController.java`、`config/OpenAIConfig.java`
- 测试或实验结果（真实调用 deepseek-v4-flash）：
  - **记忆生效**：同一 `conversationId` 两轮，第二轮答"你的名字是陈明"；`promptTokens` 37→62（历史注入的成本实锤）；
  - **会话隔离**：换 `conversationId` 提问，模型答"无法知道您的名字"，未串；
  - **回归无状态**：`/api/chat` 两轮，第二轮不记得名字，`@Primary` 隔离守住；
  - **校验**：空白 `conversationId` → 400 `INVALID_REQUEST`（调模型前拦截，零成本）；
  - `mvnw compile` BUILD SUCCESS。
- 遇到的问题（复核逮到的 6 个"编译能过但静默/启动出错"）：
  1. `@NotBlank` 标在 record 上但 Controller 无 `@Valid` → 校验空转；改回 Controller 手动 `StringUtils.hasText`；
  2. 漏定义 `ChatMemory` bean，直接构造器注入 → 要么启动失败，要么拿到自动配置的默认 `maxMessages=20`，决定 2 的 12 **静默丢失**；
  3. config 类构造器注入自己 `@Bean` 出的 `ChatMemory` → 循环依赖（`BeanCurrentlyInCreation`）；改为方法参数注入；
  4. `@Qualifier` 标在字段上（构造器注入时无效）且拼写 `conservation` → 实际注入到 `@Primary` 无记忆 client，**记忆静默失效**；挪到构造器参数 + 拼写 `conversation`；
  5. 漏 `.system(...)`——而决定 2 的 `maxMessages=12` 正是按"有 1 条 system 占坑"算的；
  6. 类名 `Conservation`（拼写）、端点 `/chat` 与 `ChatController` 撞车（启动 `Ambiguous mapping`）；改类名 + 路径 `/api/conversation`。
- 明日调整：进 Day31，深化会话隔离 + 历史窗口裁剪实测——正好真实验证决定 2 的 `maxMessages=12` 到底稳定保留几轮（呼应决定 2 留下的"Day30/31 需实测"边界）。

## 参考资料

- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI Advisors](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Spring AI ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [第四周学习笔记](./week04.md)
- [第五周每日计划](../docs/week-05-daily-plan.md)
