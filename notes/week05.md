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

**诚实边界（Day31 对象层坐实，advisor 时序仍待 Day34）：** `MessageWindowChatMemory` 的对象层裁剪已在**真实 2.0.0** 实测坐实——`maxMessages=12` + 1 条 system **稳定保留最近 5 轮**（见 Day31 记录：加 8 轮后 `get` 出 `1 system + U4A4…U8A8` = 11 条，USER 计数 = 5），并靠"应有 6 轮却只剩 5 轮"的算术**独立复验了 system 占坑不被逐**——纯行为实测、不依赖读源码，比当初核实 `main` 分支源码那次证据更硬。仍未直接验证的是 `MessageChatMemoryAdvisor` 答话那一刻"先存本轮 user、再取历史"的调用时序：它会在裁剪临界点让窗口多一条"半轮"的 user，可能使某一轮答话瞬间实际可见 4 轮而非 5 轮。离线实测用的是"整轮 add"，复现不了这个 user 先入的时序，故这一层留到 **Day34 真实调用**时在临界轮观察。结论：对象层保留数已定论（稳态 5 轮）；临界时序的 ±1 轮是 Day34 的验证点。

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

### 决定 5：clear 能力用 `DELETE` 端点暴露（Day32）

**决定：** 新增 `DELETE /api/conversation/{conversationId}` 结束/重置一条会话，成功返回 **204 No Content**；`ConversationService.clear(id)` 委托 `ChatMemory.clear(id)`。

**为什么 DELETE 而非 POST 动作式：** clear 的语义就是"删除这条会话的服务端记忆资源"，`DELETE + 资源 id` 是最贴的 REST 表达；且 clear 天然**幂等**（清一个不存在的 id 也不报错），正对上 DELETE 的幂等语义。`POST /{id}/clear` 是动作式命名，语义不如 DELETE 直接，本项目不采用。

**正确性地基（Day30 同款坑的复用）：** clear 注入的 `ChatMemory` **必须与 `conversationChatClient` 上 advisor 读写的是同一个 bean**——全局只有一个 `chatMemory` bean，注入无歧义，故 clear 清的正是 chat 端点用的那份记忆。若注入成另一个 bean，clear 会清一份空记忆、**静默失效**（同 Day30 `@Qualifier` 拼错踩的坑）。

**边界立场与决定 4 一致：** clear 端点同样对空白 `conversationId` 拒绝（400），**复用** Day30 已验证的 `IllegalArgumentException → 400` 异常链，不为 clear 单开一套。

**为什么最小化、不做更多：** 计划要求"按需求最小化"。本周只需"结束会话"这一个动作，不做"列出所有会话""重命名"等——那些属于**业务持久化层**（给人看的会话管理），不是 `ChatMemory`（给模型看的上下文）该管的，混进来就越界了（呼应本周开篇 ChatMemory vs 业务持久化的边界）。

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

## 历史窗口裁剪策略：按条数 vs 按 token（Day31 准备事项 3）

> 承接决定 2（`maxMessages=12` 的条数窗口）：既然选了按条数，就得说清它相对按 token 裁剪的取舍与盲区，才不算"用默认值再说"。

| 维度 | 按条数（`MessageWindowChatMemory` 的做法） | 按 token 数 |
| --- | --- | --- |
| 计量单位 | 消息条数（一轮问答 = user + assistant = 2 条） | 累计 token 数 |
| 事前可预测性 | **高**——"稳定保留 N 轮"是可承诺的（Day31 实测 12 → 5 轮，每次一样） | **低**——保留轮数随每轮长短浮动，同预算这次留 8 轮、下次可能只留 3 轮 |
| 实现成本 | 低——只数个数；且 Spring AI 默认实现天生按条数，零额外成本 | 高——每条先过 tokenizer 算 token，还依赖具体 tokenizer |
| 贴合模型硬约束 | 差——条数只是 token 的**代理指标**，会失真 | 好——精确贴着 token 上限与成本这条真正的硬线 |
| 主要盲区 | 一条**超长消息**可单条撑爆上下文，窗口只数"才几条"、毫无察觉 | 保留轮数不可预测；计数还依赖具体 tokenizer |

**先厘清一个易被挑刺的点：** token 不是"算不准"。对一段确定的文本，token 数是**确定的**（同一 tokenizer、同一段字，结果固定）。真正不可预测的是**事前不知道模型这轮会吐多少 token**（思考多长、格式多啰嗦、答多详细都不定），于是"这轮吃掉多少 token 预算"事前无法预测；而条数事前铁定（一轮就是 2 条）。所以按 token 的软肋落在**裁剪边界的可预测性**上——不是计数失真，是**保留轮数不稳定**。

**两边的盲区都要认：**
- **按条数的盲区**：条数只是 token 的代理，代理会失真——一条超长消息（用户粘一大段文本）可能单条就撑爆模型上下文，而条数窗口只数"没超几条"，对 token 早已爆掉毫无察觉。
- **按 token 的存在理由**：模型真正的硬约束是 **token 上限**（超了截断 / 报错）与**成本**（按 token 计费），token 裁剪精确贴着这条硬线，条数贴不住。

**本周为什么先用按条数：** 内存版学习环境，消息都不长（单条撑爆的盲区暂不成立），且本周要的正是"稳定保留最近 5 轮"这种**可预测**行为——按条数直接满足，还顺着 Spring AI 默认实现零成本落地。按 token 精确，但换来轮数不可预测 + 计算开销，当下不划算。故**按条数是当下正解，按 token 列技术债**，留到"消息可能超长"的真实场景（如接入长文档、RAG 上下文）再上。

> **一句话：** 条数简单、可预测，但对超长单条失真；token 精确贴合硬约束，但保留轮数不可预测、还要先算 token。选型跟着"当下最痛的约束"走——本周痛的是"稳定保留几轮"，不是"token 会不会爆"，所以选条数。

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

### 2026-07-22（Day31）

- 实际投入：
- 今日目标：验证多会话隔离；离线观察窗口裁剪行为，实测决定 2 的 `maxMessages=12` 到底稳定保留几轮。
- 完成内容（①②③均落地；"模型是否还记得早期信息"的真实调用按计划留 Day34）：
  - 新增离线观察测试 `MessageWindowChatMemoryObservationTest`（3 条，零 API）：直接对真实 `MessageWindowChatMemory` 操作，逐轮打印窗口内容，只对铁定不变量断言（总数≤上限、system 恒在且唯一、会话互不串、`clear` 只清自己）；
  - **观察 A**（`maxMessages=4`）：稳定保留 `system + 最近 1 轮`；逐出时**整轮一起丢**、system 恒在最前；
  - **观察 B**（`maxMessages=12`，生产值）：加 8 轮后窗口 = `1 system + U4A4…U8A8` = 11 条，**USER 计数 = 5**；
  - **观察 C**：两个 `conversationId` 互不可见，`clear(A)` 后 A 空、B 完好。
- 产出路径：`codes/spring-ai-chat/src/test/java/com/foxmimi/springaichat/memory/MessageWindowChatMemoryObservationTest.java`；`notes/week05.md`（新增"按条数 vs 按 token"对比一节 + 本记录 + 决定 2 诚实边界更新）。
- 测试或实验结果：`mvn test -Dtest=MessageWindowChatMemoryObservationTest` → Tests run: 3, Failures: 0，BUILD SUCCESS，零 API 调用。核心结论：**决定 2 的 `maxMessages=12` 在真实 2.0.0 实测稳定保留 5 轮，成立**；总数是 11 不是 12，因 **system 占 1 坑 + 对齐不留半轮**，第 12 个坑稳态下必然空着。
- 遇到的问题：无阻塞。附带收获：纯从"5 轮 vs 应有 6 轮"的算术**独立复验了"system 占坑不被逐"**，不用读源码——比决定 2 当初读 `main` 分支源码那次证据更硬。
- 明日调整：进 Day32——`clear` 会话、空历史首轮、`conversationId` 边界处理（决定 4 接进 `GlobalExceptionHandler`）。advisor 答话时序（临界轮可见 4 还是 5 轮）留到 Day34 真实调用时验证。

### 2026-07-22（Day32）

- 实际投入：
- 今日目标：会话生命周期——`clear` 能力落地 + `conversationId` 边界处理收口。
- 完成内容（核对后发现决定 4 的边界处理 Day30 已落地，本日真正增量只有 `clear` 能力）：
  - **现状核对**：`conversationId` 缺失/空白拒绝 + 400 映射，Day30 已在 `ConversationController`（`StringUtils.hasText`）+ `GlobalExceptionHandler`（`IllegalArgumentException → 400 INVALID_REQUEST`）完成，本日不重复造；
  - **新增 clear 能力（决定 5）**：`ConversationService.clear(id)` 委托 `ChatMemory.clear(id)`（构造器多注入同一个 `chatMemory` bean）；`ConversationController` 加 `DELETE /api/conversation/{conversationId}` → 204，空白 id 复用 400 异常链；
  - `mvnw compile` BUILD SUCCESS。
- 产出路径：`service/ConversationService.java`、`controller/ConversationController.java`、`notes/week05.md`（决定 5 + 本记录）。
- 测试或实验结果（本轮零 API）：
  - clear **记忆层清空**：Day31 观察 C 已铁证 `clear(A)`→`get(A)` 空、不影响 B；`clear()` 即直接委托 `chatMemory.clear()`，机制已成立；
  - clear 端点**边界 400**：复用 Day30 已验证的同一条 `IllegalArgumentException → 400` 链；自动化断言并入 Day33（计划本就含"边界与 clear"离线用例）；
  - clear **端到端"真实不记得"**：并入 Day34（端到端日），本轮不花 API。
- 遇到的问题：无。附带确认——边界处理不必重写，Day30 已达标，避免了重复造轮子。
- 明日调整：进 Day33——离线单元测试 10-12 条，含 `clear` 后 `get` 为空、空历史 `get` 不报错、缺失/非法 id 行为（正好把本日 clear 的自动化断言补上）。

### 2026-07-23（Day33）

- 实际投入：
- 今日目标：离线**断言式**单元测试 10–12 条，覆盖存取/隔离/裁剪/边界四类，默认 `mvn test` 零 API。
- 完成内容：
  - 新增 `MessageWindowChatMemoryUnitTest`（12 条，`@Nested` 分四类），直接对真实 `MessageWindowChatMemory` + 默认内存仓库断言，无 `@Tag("integration")`，默认即跑；
  - 与 Day31 观察测试**分工而非重复**：Day31 是观察式（打印 + 只断言铁定不变量、故意不硬断言几轮），本日是断言式（对四类行为硬断言），Day31 原样保留不动；
  - **历史存取（3）**：add 后原样取回、多轮保持写入顺序、按 `conversationId` 各取各的线；
  - **会话隔离（3）**：两 id 内容双向互不可见、`clear(A)` 不影响 B、8 线程并发写不同 id 互不干扰；
  - **窗口裁剪（3）**：超 `maxMessages` 逐出最旧、system 占坑但永不被逐且恒在最前、恰好等于窗口不裁；
  - **边界与 clear（3）**：未知 id 空历史 `get` 返回空列表不报错、`clear` 后 `get` 为空、空白/null id 抛 `IllegalArgumentException` 而非 UUID 串被正常接受。
- 产出路径：`codes/spring-ai-chat/src/test/java/com/foxmimi/springaichat/memory/MessageWindowChatMemoryUnitTest.java`；`notes/week05.md`（本记录）。
- 测试或实验结果：`mvn test -Dtest=MessageWindowChatMemoryUnitTest` → surefire XML `tests="12" failures="0" errors="0" skipped="0"`，零 API。关键坐实：
  - **决定 4 的机制来源被单测坐实**——`add`/`get`/`clear` 三个方法开头均 `Assert.hasText(conversationId)`，空白/null 一律抛 `IllegalArgumentException`；这正是 advisor 缺 `CONVERSATION_ID` 抛错、最终映射 400 的底层原因。`"not-a-uuid"` 被正常接受（返回空），印证「只校非空白、不强校 UUID 格式」；
  - **并发用例的诚实边界**：只验证了「不同 id 并发写互不干扰」（底层 `ConcurrentHashMap` 对不同 key 安全），**未**验证「同一 id 并发写的原子性」——那是更强命题，本用例不覆盖，不过度解读。
- 遇到的问题：`@Nested` 的 surefire 怪癖——纯文本 `...UnitTest.txt` 摘要显示 `Tests run: 0`（外层类无直接 `@Test`），但 XML 正确计 `tests="12"`。**取证以 XML 为准**，别被控制台那行 0 误判成没跑用例。取舍：若在意控制台读数，可把 `@Nested` 拆成扁平四类命名，代价是牺牲分组可读性——本日选保留 `@Nested`。
- 明日调整：进 Day34——端到端多轮用例 5–6 条（真实调模型），观察 `promptTokens` 随历史增长的成本，并在临界轮验证 advisor「先存本轮 user、再取历史」的时序（决定 2 留到 Day34 的那个 ±1 轮验证点）。

### 2026-07-23（Day34）

- 实际投入：
- 今日目标：端到端多轮用例（真实调 `deepseek-chat`，temperature=0），观察记忆生效/隔离/裁剪，并记录 `promptTokens` 随历史增长的真实成本。
- 完成内容（用 Postman Collection 手动驱动，6 个场景 / 23 个请求，产出 `day34-conversation.postman_collection.json`）：
  - **跨轮记忆 + clear 端到端**、**多轮累积综合提问**、**指代消解**、**会话隔离**、**窗口裁剪**、**注入/隔离守护**六类；
  - 行为结论：会话隔离**没串**（A 答“爱丽丝”、B 答“鲍勃”）；窗口裁剪**真实生效**——超窗后追问最早埋入的“暗号 7788”，模型**答不出**（早期轮已被逐出窗口），坐实决定 2 的条数裁剪在真实调用中成立。
- 产出路径：`codes/spring-ai-chat/day34-conversation.postman_collection.json`；`notes/week05.md`（本记录 + token 数据）。
- 测试或实验结果（`promptTokens` 实测，真实调用）：

  | 场景 | 轮次 | promptTokens | 说明 |
  | --- | --- | --- | --- |
  | 1 记忆+clear | 埋名字 / 追问 / clear 后再问 | **55 → 69 → 52** | 55=基线(system+本轮)；69=注入第 1 轮历史(+14)，记忆有成本；52=clear 后回落到基线，clear 端到端真实生效 |
  | 2 逐轮累积 | 第 1/2/3 轮 | **77 → 166 → 251** | 每轮约 +85，单调递增，历史越堆越贵，干净的成本曲线 |
  | 5 裁剪+超长消息 | 埋暗号 / 中段 / 末轮 | **52 → 3012 → 5166** | 见下「关键发现」 |

- **关键发现（按条数裁剪的盲区在真实调用中现身）：** 场景 5 的填充轮问的是 Java/HTTP/REST 等，模型**回答极长**。窗口按**条数**封顶 5 轮，成功把早期“暗号”轮逐出（5.8 模型答不出 7788，**裁剪的行为层生效**），**但 `promptTokens` 仍一路飙到 5166、并未停在低位**——因为保留的 5 轮里塞的是超长消息。这正是本周「按条数 vs 按 token」一节预言的盲区：**「一条超长消息可单条撑爆上下文，窗口只数‘才几条’、毫无察觉」**。行为层裁剪成功、成本层失控，两件事同时成立。它把「按 token 裁剪」从纸上推演升级为**有真实数据支撑的技术债**（Day35 汇总纳入）。
  > 附：这也部分**证伪**了 Day34 准备时“裁剪后 token 会稳定在窗口上限附近、不再无限涨”的预测——条数窗口只界定**轮数**、不界定 token，当每轮内容超长时，token 上界远高于预期。
- 遇到的问题：无阻塞。advisor「先存本轮 user、再取历史」的临界轮 ±1 时序（决定 2 留下的验证点）本轮用整轮问答未专门构造临界样本，未单独坐实——留作观察项，非阻塞。
- 明日调整：进 Day35——收口。整理笔记、回答思考题（记忆“记住对话”vs“不知道外部知识”的边界）、部署视角反思（重启/多实例）、技术债务清单（新增：**按条数裁剪对超长消息失效，token 成本无上限**，已有真实数据）、第六周准备清单。

## 参考资料

- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI Advisors](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Spring AI ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [第四周学习笔记](./week04.md)
- [第五周每日计划](../docs/week-05-daily-plan.md)
