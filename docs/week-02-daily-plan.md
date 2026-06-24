# 第二周按天执行计划：Spring AI 框架接入与对比

## 总体安排

- 日期：2026-06-25（周四）至 2026-07-01（周三）
- 总投入：约 9.5 小时
- 工作日：每天 1–1.5 小时；周末：每天 2 小时
- 技术：Java 21、Spring Boot 3.x、Spring AI、JUnit 5、WireMock、WebTestClient
- 原则：保留第一周原生客户端作为对照基准，接入 Spring AI 并实现同步与流式接口，比较两种方案在配置、异常、Token 观测和供应商耦合方面的差异

最终应得到一个独立 Spring Boot 模块、同步 `/api/chat` 和流式 `/api/chat/stream` 端点、Mock 与少量集成测试、一份原生客户端与 Spring AI 的对比报告。

## 第 8 天：6 月 25 日，周四（1 小时）

**目标：** 搭建 Spring Boot + Spring AI 项目骨架。

**执行任务：**

1. 在 `codes/` 下创建 `spring-ai-chat` 模块（独立 Maven 项目或子模块）。
2. 固定版本：Java 21、Spring Boot 3.4.x、Spring AI（选择当前稳定版）、Jackson。
3. 配置 `application.yml`：模型名称、API Key（通过环境变量 `${DEEPSEEK_KEY}`）、base URL、超时参数。
4. 引入 Spring AI 的 OpenAI 兼容 starter（DeepSeek 兼容 OpenAI 协议）。
5. 编写启动类，验证 Spring Boot 能正常启动。
6. 确认第一周 `llm-basics` 模块保持不动，作为对照基准。

**建议结构：**

```text
spring-ai-chat/
├─ pom.xml
├─ src/main/java/
│  ├─ config/ChatConfig.java
│  ├─ controller/ChatController.java
│  ├─ service/ChatService.java
│  └─ SpringAiChatApplication.java
├─ src/main/resources/
│  └─ application.yml
└─ src/test/java/
```

**产出：** 可启动的 Spring Boot 项目、`application.yml` 配置、`.gitignore` 更新。

**验收：**

- [ ] Spring Boot 可正常启动
- [ ] API Key 通过环境变量注入，未进入源码或配置
- [ ] 版本已在 `pom.xml` 中显式固定
- [ ] `llm-basics` 模块未被修改

## 第 9 天：6 月 26 日，周五（1 小时）

**目标：** 实现同步 `/api/chat` 端点。

**执行任务：**

1. 使用 Spring AI 的 `ChatClient` 或 `ChatModel` 实现同步聊天请求。
2. 创建 `POST /api/chat` 端点，接收用户消息，返回模型回答。
3. 在响应中包含：模型名称、回答内容、Token 用量（输入/输出/总计）、耗时。
4. 实现 `ChatService` 封装调用逻辑，Controller 只负责 HTTP 层。
5. 手动调用一次验证端到端可用。

**产出：** `ChatController`、`ChatService`、请求/响应 DTO、一次成功调用记录。

**验收：**

- [ ] `POST /api/chat` 可正常返回模型回答
- [ ] 响应包含 Token 用量和耗时
- [ ] Controller 不包含业务逻辑
- [ ] 异常时返回合理的 HTTP 状态和错误信息

## 第 10 天：6 月 27 日，周六（2 小时）

**目标：** 为同步端点编写 Mock 测试和少量集成测试。

**执行任务：**

1. 使用 Mock 替换 `ChatModel`，验证 Controller 和 Service 的行为。
2. 测试正常响应、Token 记录、空回答和异常场景。
3. 编写 1-2 个受控集成测试，调用真实 DeepSeek API（通过 profile 或 tag 隔离，默认不运行）。
4. 对比 Spring AI 异常与第一周 `LlmErrorType` 的映射差异。

**产出：** 单元测试、集成测试（隔离运行）、异常映射对照表。

**验收：**

- [ ] `mvn test` 默认只运行 Mock 测试，全部通过
- [ ] 集成测试可通过 profile/tag 单独触发
- [ ] 记录 Spring AI 异常与第一周 `LlmErrorType` 的对应关系
- [ ] 测试不依赖真实 API Key

## 第 11 天：6 月 28 日，周日（2 小时）

**目标：** 实现流式 `/api/chat/stream` 端点。

**执行任务：**

1. 使用 Spring AI 的 `StreamingChatModel` 或 `Flux` 实现流式响应。
2. 创建 `POST /api/chat/stream` 端点，使用 SSE（Server-Sent Events）或 chunked transfer 返回。
3. 每个事件包含增量文本片段；最终事件包含完整 Token 统计。
4. 测试流式响应能逐块返回。
5. 处理流式异常：上游超时、客户端断开、模型错误。

**产出：** `ChatController` 流式端点、`ChatService` 流式方法、SSE 事件格式。

**验收：**

- [ ] 流式端点可逐块返回模型回答
- [ ] 客户端断开后服务端不再持续生成
- [ ] 上游超时时返回错误事件或合理终止
- [ ] 不把框架抽象误认为供应商能力一致（记录 Spring AI 流式与原生流式的差异）

## 第 12 天：6 月 29 日，周一（1.5 小时）

**目标：** 流式端点测试与边界场景验证。

**执行任务：**

1. 使用 `WebTestClient` 或类似工具测试流式端点。
2. 验证正常流式输出、中途取消、超时和异常场景。
3. 对比第一周记录的连接超时与请求超时行为。
4. 记录 Spring AI 在流式场景下暴露的 Token 观测能力（是否可获取输入/输出 Token）。

**产出：** 流式测试用例、Token 观测能力记录、边界场景测试结果。

**验收：**

- [ ] 流式测试覆盖正常、取消、超时和异常场景
- [ ] 记录流式响应是否支持 Token 统计
- [ ] 对比原生客户端与 Spring AI 在超时处理上的差异
- [ ] 所有测试通过

## 第 13 天：6 月 30 日，周二（1 小时）

**目标：** 完成原生客户端与 Spring AI 的对比分析。

**执行任务：**

1. 按以下维度逐项对比，形成对照表：

| 对比维度 | 原生客户端 (llm-basics) | Spring AI (spring-ai-chat) |
|---|---|---|
| 配置方式 | | |
| 请求/响应 DTO | | |
| 异常分类与映射 | | |
| Token 观测 | | |
| 流式支持 | | |
| 重试机制 | | |
| 供应商耦合度 | | |
| 测试便利性 | | |
| 代码量 | | |

2. 分析 Spring AI 隐藏了哪些协议细节，哪些隐藏是有益的，哪些需要警惕。
3. 评估从原生客户端迁移到 Spring AI 的成本与收益。
4. 列出 Spring AI 的已知限制和需要注意的陷阱。

**产出：** 对比分析文档（可整合进 `notes/week02.md`）。

**验收：**

- [ ] 对比表覆盖至少 8 个维度
- [ ] 每个维度有具体代码或行为示例
- [ ] 不把"框架更简洁"等同于"框架更好"，需说明代价
- [ ] 记录至少 3 个 Spring AI 隐藏的重要细节

## 第 14 天：7 月 1 日，周三（1 小时）

**目标：** 完成验收、总结并衔接第三周。

**执行任务：**

1. 运行全部自动化测试。
2. 检查 Git 中是否包含密钥或敏感数据。
3. 整理对比分析结论。
4. 总结 Spring AI 的优势、限制和注意事项。
5. 列出技术债务和第三周准备事项。

**产出：**

- `notes/week02.md` 学习笔记与验收报告
- 测试结果汇总
- 原生客户端 vs Spring AI 对比结论
- 技术债务清单
- 第三周准备清单

**验收：**

- [ ] 一条 Maven 命令可以构建并测试
- [ ] 同步和流式端点均可用
- [ ] 完成对比分析报告
- [ ] 能说明 Spring AI 隐藏了哪些协议细节
- [ ] 能回答"什么时候该用框架，什么时候该用原生"

## 第二周完成定义

- [ ] Spring Boot + Spring AI 项目可正常启动
- [ ] API Key 未进入仓库
- [ ] 同步 `/api/chat` 可用，响应包含 Token 和耗时
- [ ] 流式 `/api/chat/stream` 可用，支持逐块返回
- [ ] Mock 测试覆盖正常、异常和边界场景
- [ ] 集成测试通过 profile/tag 隔离，默认不调用真实 API
- [ ] 完成原生客户端与 Spring AI 的多维度对比
- [ ] 记录 Spring AI 在异常、Token 观测和供应商耦合方面的限制
- [ ] 所有自动化测试可重复运行
- [ ] 形成技术债务和第三周衔接清单

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

- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- [Spring AI OpenAI 集成](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
- [DeepSeek API Reference](https://api-docs.deepseek.com/)
- [Spring Boot 3.x 文档](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/)
- [第一周学习笔记](../notes/week01.md)
- [第一周每日计划](./week-01-daily-plan.md)

> Spring AI 版本和 API 可能快速变化。开始编码前应确认所选版本的官方文档，不要直接复制旧版本的配置或依赖。
