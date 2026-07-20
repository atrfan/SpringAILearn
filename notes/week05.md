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
