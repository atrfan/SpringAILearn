# 第五周按天执行计划：会话记忆与多轮对话

## 总体安排

- 日期：2026-07-16（周四）至 2026-07-22（周三）
- 总投入：约 10.5 小时
- 工作日：每天 1–1.5 小时；周末：每天 2 小时
- 技术：Java 21、Spring Boot 4.1.0、Spring AI 2.0.0（`ChatMemory`、`MessageWindowChatMemory`、`InMemoryChatMemoryRepository`、`MessageChatMemoryAdvisor`）、JUnit 5
- 原则：第四周收口了阶段项目一（无状态的单轮结构化服务）。本周开启**新阶段——会话维度**：给系统引入"记住上文"的能力，把无状态的单轮请求升级为按 `conversationId` 组织的多轮对话。核心不是"多调几次模型"，而是想清楚**会话状态该挂在哪一层、历史怎么注入、怎么隔离、怎么裁剪**——把这些边界一次性定在正确的位置，而不是让会话状态到处泄漏。

最终应得到：一个**独立于现有无状态端点**的多轮会话端点，基于 `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` + `MessageChatMemoryAdvisor` 实现跨轮记忆；不同 `conversationId` 之间严格隔离；历史窗口有明确的裁剪策略并被验证生效；约 10–12 条离线测试（零 API 调用）+ 5–6 条端到端多轮用例；以及"内存版记忆的代价"这一思考题的回答，为后续持久化与 RAG 埋下衔接点。

> **与第四周的差异：** 第四周是硬性里程碑收口周，重心在"输出可信"。本周是新阶段的**起步周**，重心在"把会话状态放对位置"。刻意只做**内存版**（`InMemoryChatMemoryRepository`）——会话窗口、advisor 装配、`conversationId` 隔离、历史裁剪这些核心概念在内存版就能全部掌握；JDBC 持久化本周只做概念对比、列入技术债务，不落地数据库，避免建表/数据源配置稀释本周焦点。

> **承接第四周：** 直接偿还 Day28 第五周准备清单的 4 条：会话 ID 的生成与传递、`ChatMemory` 与业务持久化的区别、历史窗口裁剪策略、"现有分层能否直接挂会话维度"。复用 `spring-ai-chat` 模块与 `OpenAIConfig` 里的 `ChatClient` 装配点、`GlobalExceptionHandler` 与 `ErrorResponse` 结构。**保留** `MyChatService.chat()` / `chatStream()` 的无状态语义不动——本周新增端点，不改造已达标的 week02 聊天基线。

## 第 29 天：7 月 16 日，周四（1 小时）

**目标：** 建立会话记忆的概念框架，完成 `conversationId` 传递、窗口策略、分层归属三个关键设计决定。

**学习内容（约 30 分钟）：**

- **Chat Memory 与业务持久化的区别**（Day28 准备事项 2）：`ChatMemory` 管的是"下一次调模型时要塞进上下文的历史消息"，是**给模型看的**、有窗口上限、会被裁剪；业务库里落地保存的对话记录是**给人和业务看的**、要完整留存、生命周期不同。这条边界本周必须先想清楚，别把两者混成一件事。
- **`conversationId` 的作用**：它是 `ChatMemory` 存取历史的 key，一个 id 一条独立的对话线。谁生成、怎么在请求间传递，是本周第一个设计决定。
- **`ChatMemory` 接口**：`add(id, messages)` / `get(id)` / `clear(id)` 三个方法就是记忆的全部操作面。
- **`MessageWindowChatMemory`**：默认实现，维护一个最多 N 条的消息窗口（默认 20），超出时逐出旧消息、保留 system 消息。
- **Advisor 机制**：`MessageChatMemoryAdvisor` 挂到 `ChatClient` 上后，会在每次调用时**自动**把该 `conversationId` 的历史读出来注入、并把本轮问答写回记忆——历史注入不需要也不应该手工拼接。

**执行任务（偏设计，约 30 分钟）：**

1. 在 `notes/week05.md` 中用自己的话写出 **Chat Memory 与业务持久化的区别**（用途、生命周期、是否裁剪三个维度各说一句）。
2. 决定 **`conversationId` 的生成与传递方案**：前端生成后每轮带上，还是首轮由后端生成并回传？写下选择与理由（影响端点入参与"缺 id 时怎么办"的边界处理）。
3. 决定 **历史窗口策略**：`MessageWindowChatMemory` 的 `maxMessages` 初始值定多少，为什么（想清楚这是"条数"不是"token 数"，两者的差异留到 Day31 展开）。
4. 决定 **会话记忆挂在哪一层**（Day28 准备事项 4 的正面回答）：是给现有 `ChatClient` bean 加 `defaultAdvisors`，还是**新建一个带 memory advisor 的独立 `ChatClient`/service**，只服务新端点、不污染 `MyChatService` 的无状态语义？写下决定与理由。

**产出：** `notes/week05.md` 初稿、`conversationId` 传递决定、窗口策略决定、分层归属决定。

**验收：**

- [ ] 能用三个维度说清 `ChatMemory` 与业务持久化的区别
- [ ] `conversationId` 由谁生成、怎么传，有明确决定与理由
- [ ] `maxMessages` 是一个写下来的数字，而不是"用默认值再说"
- [ ] 会话记忆挂在哪一层有明确决定，且能说清为什么不污染无状态端点

## 第 30 天：7 月 17 日，周五（1.5 小时）

**目标：** 新增独立多轮会话端点，装配 `ChatMemory`，实现一次"能记住上文"的多轮对话。

**执行任务：**

1. 新建会话请求体（含 `message` 与 `conversationId`，按 Day29 传递决定确定 `conversationId` 是否必填）。
2. 按 Day29 分层决定装配 `ChatMemory`：配置 `MessageWindowChatMemory`（内部用 `InMemoryChatMemoryRepository`）bean，并通过 `MessageChatMemoryAdvisor` 挂到服务于新端点的 `ChatClient` 上。**不要**动 `MyChatService` 用的那条无状态链路。
3. 新增独立端点（如 `POST /api/conversation`）与对应 service；每次调用时通过 advisor 参数把本轮的 `conversationId` 传进去。
4. 手动验证一次两轮对话：第一轮告诉模型一个事实（如"我叫陈明"），第二轮提问（如"我叫什么？"），确认第二轮能引用第一轮的信息——记忆链路成立。
5. **红线自查：** 全程不允许手工拼接历史字符串（如把上几轮的问答自己 `concat` 进 user 文本、或从别处 `substring` 历史）。历史的读取与注入**只能**交给 `MessageChatMemoryAdvisor`；如果你在写"把历史拼进 prompt"的代码，说明装配方式错了。

**产出：** 会话请求体、`ChatMemory` 装配、独立会话端点与 service、一次成功的两轮对话记录。

**验收：**

- [ ] 新端点能跨轮记住上文（第二轮引用第一轮信息）
- [ ] `/api/chat`、`/api/chat/stream` 保持无状态语义，未被改动
- [ ] 历史注入由 advisor 承担，代码中无手工拼接历史的逻辑
- [ ] 会话记忆装配在独立链路上，未污染 `MyChatService`

## 第 31 天：7 月 18 日，周六（2 小时）

**目标：** 验证多会话隔离，落地历史窗口裁剪并观察其行为。

**执行任务：**

1. **会话隔离验证：** 用两个不同的 `conversationId` 各自对话，确认 A 会话的历史在 B 会话里完全不可见——`conversationId` 是隔离边界，不同会话的历史绝不能互相串。
2. **窗口裁剪观察：** 把 `maxMessages` 调到一个小值，构造一段超过窗口的多轮对话，观察最旧的消息被逐出、system 消息被保留的行为；记录"当早期信息被裁掉后，模型是否还记得它"的实际表现。
3. **裁剪策略对比（Day28 准备事项 3）：** 在 `notes/week05.md` 写下**按条数裁剪**（`MessageWindowChatMemory` 的做法）与**按 token 数裁剪**的差异——各自的优点与盲区（例如：按条数简单但一条超长消息可能撑爆上下文；按 token 精确但要先算 token）。说明本周为什么先用按条数。
4. 手动记录一次"裁剪生效"的完整观察（对话轮次、窗口大小、被逐出的消息、模型是否还记得早期信息）。

**产出：** 会话隔离验证记录、窗口裁剪生效的观察记录、按条数 vs 按 token 裁剪的对比笔记。

**验收：**

- [ ] 不同 `conversationId` 的历史严格隔离，验证通过
- [ ] 能演示窗口裁剪生效（旧消息被逐出、system 保留）
- [ ] 按条数 vs 按 token 的裁剪差异有记录，且说明本周选择的理由

## 第 32 天：7 月 19 日，周日（2 小时）

**目标：** 会话生命周期（清除、空历史）与 `conversationId` 边界的处理。

**执行任务：**

1. **会话清除：** 用 `ChatMemory.clear(conversationId)` 结束/重置一条会话，验证 clear 之后该会话历史确实清空、后续对话从零开始。决定这个能力怎么对外暴露（一个结束会话的端点，还是仅内部用），按需求最小化，不过度设计。
2. **空历史首轮：** 确认一个全新的 `conversationId` 首轮对话正常（`get` 返回空历史不应报错）。
3. **`conversationId` 边界：** 按 Day29 传递决定，落地"缺失 / 空白 / 非法 `conversationId`"时的明确行为（拒绝并返回结构化错误？还是后端补一个新 id？）——不允许"缺 id 就静默共用同一条历史"这种会导致跨用户串话的隐患。
4. 边界错误接进 `GlobalExceptionHandler`，返回结构化错误而非堆栈；复用 `ErrorResponse` 结构与既有错误码风格。
5. 手动构造 2–3 个边界输入验证行为。

**产出：** clear 能力、空历史首轮验证、`conversationId` 边界处理与异常映射、手动验证记录。

**验收：**

- [ ] `clear` 后会话历史清空，后续从零开始
- [ ] 全新 `conversationId` 首轮正常，空历史不报错
- [ ] `conversationId` 缺失/非法有明确行为，绝无"缺 id 静默共用历史"的串话隐患
- [ ] 边界错误返回结构化响应，而非堆栈

## 第 33 天：7 月 20 日，周一（1.5 小时）

**目标：** 离线单元测试——约 10–12 条，全部不调模型（本周测试成本的大头，API 成本为零）。

> **拆分依据：** 记忆的核心行为——历史存取、会话隔离、窗口裁剪、clear、`conversationId` 边界——都可以用**真实的 `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` 对象**直接断言，或对 service 层 mock 掉 `ChatClient`，完全不需要真实模型。真实调模型的用例集中在 Day34，控制在 5–6 条。

**执行任务：**

1. 为记忆链路搭测试骨架：直接对 `ChatMemory` / `InMemoryChatMemoryRepository` 断言，或对会话 service mock `ChatClient`、用真实 `ChatMemory`。
2. 按四类场景铺用例，目标 10–12 条：
   - **历史存取**（约 3 条）：`add` 后 `get` 能取回、按 `conversationId` 取到正确的那条线、`add` 多轮后顺序正确；
   - **会话隔离**（约 3 条）：两个 id 互不可见、A 的 `clear` 不影响 B、并发写不同 id 互不干扰；
   - **窗口裁剪**（约 3 条）：超过 `maxMessages` 后最旧消息被逐出、system 消息保留、恰好等于窗口不裁；
   - **边界与 clear**（约 2–3 条）：空历史 `get` 不报错、`clear` 后 `get` 为空、缺失/非法 `conversationId` 的行为。
3. 确认默认 `mvn test` 全绿且零 API 调用（沿用 week04 的 `excludedGroups` 机制）。

**产出：** 10–12 条离线测试、按四类场景组织的用例清单（作为本周验收素材）。

**验收：**

- [ ] 用例数不少于 10，覆盖存取/隔离/裁剪/边界四类
- [ ] 默认 `mvn test` 不调用付费 API
- [ ] 会话隔离与窗口裁剪各有断言，不是笼统的"能跑通"

## 第 34 天：7 月 21 日，周二（1.5 小时）

**目标：** 端到端多轮用例（5–6 条，真实调模型）+ 观察"记忆的成本"。

**执行任务：**

1. 端到端用例（打 `@Tag("integration")` 或手动执行，5–6 条）：
   - 多轮记忆 2–3 条：跨轮引用（"我叫 X"→"我叫什么"）、多轮累积信息后综合提问、指代消解（"它""这个"指向上文）；
   - 会话隔离 1 条：真实起两个会话，确认互不串话；
   - 窗口裁剪 1 条：真实对话超过窗口后，确认早期信息确实被"忘记"；
   - 注入/污染 1 条：尝试在一轮里让模型"泄漏另一个会话的内容"或"忘掉系统约束"，验证隔离与 system 保留是否守住。
2. **观察记忆的成本：** 复用 `ChatResponse` 的 `promptTokens`，记录随对话轮次增加，`promptTokens` 如何增长（历史被塞进上下文的真实开销）；对比裁剪前后的 token 差异。把"记忆不是免费的"这一观察写进笔记——它是 Day35 思考题和后续裁剪策略的依据。
3. 逐条记录用例、输入、期望、实际、通过与否。

**产出：** 5–6 条端到端多轮用例记录、token 随历史增长的观察数据。

**验收：**

- [ ] 端到端用例不超过 6 条，成本可控
- [ ] 多轮记忆在真实调用中生效，会话不串
- [ ] `promptTokens` 随历史增长被观察并记录（"记忆有成本"有数据支撑）
- [ ] 窗口裁剪在真实调用中被验证过至少一次（早期信息被忘记）

## 第 35 天：7 月 22 日，周三（1 小时）

**目标：** 本周收口、笔记整理、技术债务清单、衔接第六周。

**执行任务：**

1. 整理 `notes/week05.md`：Chat Memory 与业务持久化的边界、`conversationId` 传递决定、窗口裁剪策略、隔离与 clear 的观察、token 成本数据。
2. 回答本周思考题并写进笔记：**会话记忆让模型"记住"了什么，又没能让它"知道"什么？**（提示：记忆保存的是**这次会话说过的话**，不是外部知识——模型仍只能基于训练知识 + 当前上下文窗口回答，记忆无法引入训练集之外的新事实。这条边界和第四周"结构化输出保证形状、不保证真实"是同一个方向，共同指向第 7 周起的 RAG。）
3. 补充一个部署视角的反思：内存版 `ChatMemory` 在**服务重启**后会怎样？**多实例部署**时同一个 `conversationId` 落到不同实例会怎样？——写下结论，它就是"为什么后续需要持久化 repository"的直接理由。
4. 汇总本周技术债务（候选：内存版重启即失忆、多实例不共享记忆、按条数裁剪对超长消息失效、流式端点尚未接入记忆、历史增长导致 token 成本无上限、业务持久化与 `ChatMemory` 尚未打通）。
5. 列出第六周准备事项（承接本周债务与下一主题）。

**产出：**

- `notes/week05.md` 学习笔记与思考题回答
- 部署视角反思（重启/多实例）
- 技术债务清单
- 第六周准备清单

**验收：**

- [ ] 思考题有回答，说清记忆"记住对话"与"不知道外部知识"的边界
- [ ] 重启/多实例的行为有明确结论，指向持久化的必要性
- [ ] 列出至少 3 项本周技术债务
- [ ] 明确第六周的衔接点

## 第五周完成定义

- [ ] 新增独立多轮会话端点，能跨轮记住上文
- [ ] `/api/chat`、`/api/chat/stream` 保持无状态语义，回归通过
- [ ] 会话记忆用 `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` + `MessageChatMemoryAdvisor` 装配，历史注入不手工拼接
- [ ] 不同 `conversationId` 严格隔离，历史不串话
- [ ] 历史窗口裁剪生效，裁剪策略有记录
- [ ] `clear` / 空历史 / `conversationId` 缺失有明确行为，无静默串话隐患
- [ ] 离线测试不少于 10 条，默认 `mvn test` 零 API 调用
- [ ] 端到端验证多轮记忆，观察并记录 token 随历史增长
- [ ] 完成思考题、部署视角反思、技术债务清单与第六周准备清单

## 每日记录模板

```markdown
## YYYY-MM-DD

- 实际投入：
- 今日目标：
- 完成内容：
- 产出路径：
- 测试或实验结果：
- 遇到的问题：
- 明日调整：
```

## 参考资料

- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Spring AI Advisors](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [第四周学习笔记](../notes/week04.md)
- [第四周每日计划](./week-04-daily-plan.md)

> 本周是新阶段起步周：重心在"把会话状态放对位置"，不在堆功能。刻意只做内存版——持久化、流式接入记忆、业务库打通均不属于本周范围，作为技术债务留给后续周。若执行中时间紧张，砍的应是端到端条数（Day34）而不是离线用例（Day33）与设计决定（Day29）。
