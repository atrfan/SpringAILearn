# 第 1 周学习笔记：LLM 基础与 Java 原生 API 客户端

> **学习日期：** 2026-06-20～2026-06-22
>
> **学习阶段：** 第 1 周 · 第 1～3 天
>
> **本节目标：** 理解 LLM 的基本概念，并使用 Java 21 原生 `HttpClient` 实现可复用、可观测、可测试的 DeepSeek API 客户端。

## 目录

- [1. Token](#1-token)
- [2. 上下文窗口](#2-上下文窗口)
- [3. 消息角色](#3-消息角色)
- [4. 常用生成参数](#4-常用生成参数)
- [5. Chat Model 与 Embedding Model](#5-chat-model-与-embedding-model)
- [6. Java 原生 LLM API 客户端](#6-java-原生-llm-api-客户端)
- [7. 关键结论](#7-关键结论)
- [8. 自测问题](#8-自测问题)
- [9. 参考资料](#9-参考资料)

---

## 1. Token

### 1.1 什么是 Token

Token 是模型处理文本时使用的离散编码单元。它可能是一个完整单词、一个子词、一个汉字、标点符号，或者其他字符序列，具体切分结果取决于模型使用的 tokenizer。

例如，英文单词 `tokenization` 可能被拆分为：

```text
token + ization
```

因此，把 Token 简单理解为“AI 眼里的词”只适合作为初步类比，并不严格。更准确的说法是：

> Token 是文本经过 tokenizer 编码后，模型实际接收和生成的基本单位。

可以使用 [OpenAI Tokenizer](https://platform.openai.com/tokenizer) 观察具体文本的切分方式。

![Tokenizer 示例](./img/image-20260620140720049.png)

> [!NOTE]
> “英文约 4 个字符或 0.75 个单词对应 1 Token”只是粗略经验，不能直接套用于中文、代码、公式或特殊符号。实际数量应由目标模型对应的 tokenizer 计算。

### 1.2 Token、字符与单词的区别

| 单位 | 示例 | 特点 |
|---|---|---|
| 字符（Character） | `t`、`o`、`中` | 粒度最细，序列通常较长 |
| 单词（Word） | `tokenization`、`模型` | 符合人类语言习惯，但词表可能过大，且存在未登录词问题 |
| Token | `token`、`ization`，或单个汉字/字符组合 | 由 tokenizer 决定，在词表规模与序列长度之间折中 |

很多 tokenizer 使用 BPE 或相关子词算法，但不能把所有模型的分词方式都笼统断言为 BPE；应以具体模型说明为准。

### 1.3 Token 为什么重要

Token 数量通常直接影响：

- **上下文容量：** 输入与输出不能无限增长。
- **请求成本：** API 往往按输入和输出 Token 分别计费。
- **响应延迟：** 输入越长、生成越多，处理时间通常越长。
- **信息密度：** 冗长 Prompt 会占用可用于文档、历史消息和回答的空间。

---

## 2. 上下文窗口

上下文窗口（Context Window）是模型在一次推理中能够处理的最大 Token 范围。通常需要同时考虑：

```text
指令 + 历史消息 + 当前输入 + 工具结果/检索内容 + 模型输出
```

对于推理模型，还可能存在不可见的 reasoning tokens。它们可能占用输出预算或上下文容量，具体计算方式取决于模型与 API，不能一概而论为“可见思维链”。API 通常不会向应用暴露模型的完整内部思维链。

### 2.1 超出窗口后会发生什么

不同 API、SDK 或应用框架可能采取不同策略：

- 直接拒绝请求并返回长度错误；
- 由客户端或框架截断部分输入；
- 由应用删除旧消息或压缩历史记录；
- 减少可生成的最大输出长度。

因此，“超出部分一定会自动截断”并不准确。生产系统必须显式设计 Token 预算和历史裁剪策略，不能依赖未知的默认行为。

### 2.2 上下文窗口不等于长期记忆

模型只能利用本次请求中实际提供的上下文。即便 API 支持 Conversation、`previous_response_id` 等会话机制，也只是平台或应用帮助保存并重新关联上下文，不代表模型参数在对话后被永久修改。

> [!IMPORTANT]
> “模型记住了上一次对话”通常意味着系统重新提交或引用了历史状态，不意味着模型在一次聊天后完成了训练。

---

## 3. 消息角色

不同 API 和模型支持的角色可能不同。常见角色如下：

| 角色 | 主要职责 | 注意事项 |
|---|---|---|
| `developer` | 定义应用级行为、规则和约束 | 通常优先于用户消息；适合放置由开发者控制的稳定指令 |
| `system` | 提供高层系统指令 | 是否使用及其具体优先级需查看目标 API 和模型文档，不能与 `developer` 无条件视为同一角色 |
| `user` | 表达最终用户的请求和输入数据 | 属于不可信输入，不应获得修改高层规则的权限 |
| `assistant` | 保存模型先前的回复或提供示例回答 | 可用于多轮上下文和 few-shot 示例，但不保证示例事实正确 |

示例：

```text
Developer: 你是企业知识库助手。只能根据提供的资料回答；证据不足时明确拒答。
User:      根据员工手册，年假如何计算？
Assistant: 根据当前提供的员工手册片段……
```

### 3.1 指令角色不是安全边界

高优先级消息有助于约束模型行为，但不能替代程序控制：

- 权限应在数据库或检索层执行；
- 工具参数必须由 Java 代码校验；
- 模型输出必须视为不可信数据；
- 敏感信息不能仅靠一句“不要泄露”保护。

把 `User` 类比为“函数参数”有一定帮助，但模型并不是严格执行函数，输出具有概率性，仍需验证。

---

## 4. 常用生成参数

> [!WARNING]
> 参数名称、取值范围和支持情况与 API、模型及版本有关。下面是概念说明，不是所有 OpenAI 模型都支持的固定参数表。编码前必须检查所用端点和模型的官方 API Reference。

| 参数/概念 | 作用 | 使用注意 |
|---|---|---|
| `temperature` | 调整采样随机性 | 较低值通常更稳定，但不保证事实正确；部分推理模型可能不支持调整 |
| `top_p` | 核采样，只考虑累计概率达到阈值的候选 Token | 通常不建议在实验中同时大幅调整 `temperature` 与 `top_p`，否则难以归因 |
| 输出上限 | 限制可生成的最大 Token 数 | Responses API 与 Chat Completions API 的字段名可能不同，例如 `max_output_tokens` 或 `max_completion_tokens` |
| `stop` | 遇到指定序列时停止生成 | 并非所有模型都支持；停止序列也可能意外截断有效内容 |
| `frequency_penalty` | 根据出现频率降低重复 Token 的概率 | 适用范围和取值限制应以具体模型文档为准 |
| `presence_penalty` | 对已经出现过的 Token 施加惩罚 | 可能促进话题扩展，也可能降低回答连贯性 |
| `seed` | 尝试提高重复请求的可复现性 | 即使支持，也不应承诺完全确定性；后端变化仍可能导致结果不同 |
| `stream` | 增量返回生成事件 | 改善首 Token 等待体验，但不会自动减少总计算量或总成本 |
| `reasoning.effort` | 调整推理模型的推理投入 | 可选值和支持模型会变化，必须根据当前模型文档配置 |

### 4.1 原文中需要删除或修正的参数描述

- **`top_k`：** 它是常见采样概念，但不是 OpenAI 主流文本生成 API 中可默认假定存在的通用请求参数，不应直接列入 OpenAI 参数模板。
- **`max_tokens`：** 这是部分旧接口中的字段。新代码应根据所用 API 使用当前字段，不能无条件复制。
- **`seed`：** 只能提高可复现性，不能“保证输出一致”。
- **`reasoning_effort`：** 不应绑定未经核实的模型名称，也不能假定所有模型都支持相同枚举值。

### 4.2 参数使用原则

与其死记“事实问答必须设为 0、创意写作必须设为 1.2”，更合理的做法是：

1. 先定义任务成功标准；
2. 固定模型、Prompt 和测试集；
3. 每次只修改一个主要变量；
4. 重复运行并记录准确性、稳定性、Token 和延迟；
5. 根据实验结果选择参数。

> [!CAUTION]
> `temperature = 0` 只表示尽量选择高概率输出，不表示模型具备事实数据库，也不表示不会产生稳定、重复的错误。

---

## 5. Chat Model 与 Embedding Model

### 5.1 什么是 Embedding

Embedding 是数据的数值向量表示，用于保留内容或语义的某些特征。语义相近的文本，其向量在所选距离度量下通常更接近。

Embedding 常用于：

- 语义检索
- 聚类
- 推荐
- 异常检测
- 分类
- RAG 文档召回

“Embedding 用于衡量文本相关性”方向基本正确，但应补充两个限制：

1. 向量模型只生成表示，不直接给出自然语言答案；
2. 向量距离表示模型学习到的相似性，不等价于事实正确、逻辑蕴含或业务相关。

### 5.2 两类模型对比

| 对比维度 | Chat Model | Embedding Model |
|---|---|---|
| 输入 | 文本、消息，部分模型还支持图像、音频等 | 通常是文本或其他待编码数据 |
| 输出 | 自然语言、结构化文本、工具调用等 | 固定维度的数值向量 |
| 主要任务 | 问答、生成、摘要、抽取、推理 | 检索、聚类、推荐、相似度计算 |
| 是否直接生成答案 | 是 | 否 |
| 是否天然有状态 | 否；状态通常由应用或 API 会话机制管理 | 否；相同输入独立编码 |
| RAG 中的职责 | 基于检索上下文生成回答 | 将查询和文档编码为向量以支持召回 |

典型 RAG 数据流：

```mermaid
flowchart LR
    A[用户问题] --> B[Embedding Model]
    B --> C[查询向量]
    C --> D[向量检索]
    D --> E[相关文档片段]
    A --> F[Chat Model]
    E --> F
    F --> G[带依据的回答]
```

原文中“聊天模型是有状态的”是不准确的。聊天体验之所以表现出连续性，是因为应用或 API 保存并关联了历史消息，而不是因为模型实例天然保存了每个用户的会话。

---

## 6. Java 原生 LLM API 客户端

### 6.1 本阶段完成的调用链

项目路径为 `codes/llm-basics`，没有使用 Spring AI 或厂商 SDK，而是直接实现以下过程：

```text
ChatRequest Java 对象
    → Jackson 序列化
请求 JSON
    → Java HttpClient 发送 HTTPS POST
DeepSeek Chat Completions API
    → 返回 HTTP 状态和响应 JSON
    → Jackson 反序列化
ChatResponse Java 对象
    → 读取回答、推理内容、Token 和耗时
```

不使用上层 SDK 的目的不是重复造轮子，而是理解 SDK 通常隐藏的协议细节：

- 请求地址如何组成；
- API Key 如何进入 HTTP Header；
- Java 字段如何映射到 JSON 字段；
- HTTP 成功与业务解析成功为什么是两回事；
- Token、状态码和耗时如何被观测。

### 6.2 项目职责划分

| 类型 | 职责 | 不应承担的职责 |
|---|---|---|
| `App` | 读取环境变量、组装请求、调用客户端、输出结果 | 拼接 HTTP 请求和解析原始 JSON |
| `DeepSeekClient` | 序列化、发送请求、判断状态、解析响应和统计耗时 | 构造具体业务 Prompt |
| `ChatMessage` | 表达发送给模型的请求消息 | 表达响应专属字段 |
| `ChatRequest` | 表达完整请求 JSON | 执行网络调用 |
| `AssistantMessage` | 表达模型返回的消息和推理内容 | 表达用户输入 |
| `ChatResponse` | 表达完整响应，并安全读取回答 | 保存 API Key 或发送请求 |

这种分层使入口类不依赖原始 JSON，也为后续单元测试和故障模拟留下边界。

### 6.3 使用 record 表达 DTO

DTO（Data Transfer Object）用于表示跨边界传输的数据。Java `record` 会自动提供构造器、访问方法、`equals()`、`hashCode()` 和 `toString()`，适合表示 API 请求与响应：

```java
public record ChatMessage(
        String role,
        String content
) {}
```

访问 record 字段使用：

```java
message.role();
message.content();
```

而不是传统 JavaBean 的 `getRole()` 和 `getContent()`。

DTO 应主要描述协议结构。如果把网络发送、重试或业务判断全部塞入 DTO，会破坏职责边界，也会使测试困难。

### 6.4 Jackson 序列化与反序列化

Java 对象转换成 JSON 称为序列化：

```java
String requestBody = objectMapper.writeValueAsString(chatRequest);
```

JSON 转换成 Java 对象称为反序列化：

```java
ChatResponse response = objectMapper.readValue(
        httpResponse.body(),
        ChatResponse.class
);
```

当 Java 命名和 JSON 命名不一致时，使用 `@JsonProperty`：

```java
@JsonProperty("reasoning_content")
String reasoningContent
```

对应关系为：

```text
JSON reasoning_content ↔ Java reasoningContent
```

当服务端可能增加当前程序不关心的字段时，可以在响应 DTO 上使用：

```java
@JsonIgnoreProperties(ignoreUnknown = true)
```

但不能机械地忽略所有字段。有业务价值的字段应显式建模，否则协议变化可能被程序静默吞掉。

### 6.5 请求和响应 DTO 为什么要分离

请求消息通常只有：

```json
{
  "role": "user",
  "content": "Hello"
}
```

启用思考模式后的响应消息还可能包含：

```json
{
  "role": "assistant",
  "content": "最终回答",
  "reasoning_content": "推理内容"
}
```

因此项目使用 `ChatMessage` 表达请求消息，使用 `AssistantMessage` 表达响应消息。二者强行共用一个 DTO 会导致：

- 请求对象出现没有语义的 `reasoningContent`；
- 序列化时可能发送 `reasoning_content: null`；
- 响应增加 `tool_calls` 等字段后，共用类型会继续膨胀；
- 无法从类型上区分“用户输入”和“模型输出”。

类型相似不代表职责相同。DTO 应按协议方向和真实语义划分，而不是为了少写一个类而合并。

相应的具体示例如图：

![image-20260622153133531](./img/image-20260622153133531.png)

### 6.6 实际请求 JSON

当前代码构造的请求近似为：

```json
{
  "model": "deepseek-v4-pro",
  "messages": [
    {
      "role": "system",
      "content": "You are a helpful assistant"
    },
    {
      "role": "user",
      "content": "Hello"
    }
  ],
  "stream": false,
  "reasoning_effort": "high",
  "thinking": {
    "type": "enabled"
  }
}
```

Python SDK 示例中的：

```python
extra_body={"thinking": {"type": "enabled"}}
```

表示让 SDK 把 `thinking` 追加到最终请求体。`extra_body` 本身不是最终协议字段。原生 Java 客户端应发送顶层 `thinking`，不能把它错误地嵌套在 `extra_body` 中。

### 6.7 base URL 与完整接口 URL

厂商 SDK 通常只要求配置：

```text
https://api.deepseek.com
```

当代码调用 `chat.completions.create()` 时，SDK 会追加 `/chat/completions`。Java 原生 `HttpClient` 不知道方法名对应哪个路径，因此必须使用最终地址：

```text
https://api.deepseek.com/chat/completions
```

两种写法并不矛盾：一个是 SDK 的基础地址，另一个是实际发送请求的完整地址。

### 6.8 API Key 与 Bearer 认证

项目从环境变量读取密钥：

```java
String apiKey = System.getenv("DEEPSEEK_KEY");
```

并通过 HTTP Header 发送：

```http
Authorization: Bearer <API_KEY>
```

服务器使用该凭证完成身份验证、权限判断、限流、计费和审计。Bearer 凭证一旦泄露，持有者通常可以冒用，因此必须满足：

- 不写入 Java 源码；
- 不提交到 Git；
- 不打印完整密钥；
- 不把认证 Header 放进普通日志；
- 只通过 HTTPS 发送。

### 6.9 Java HttpClient 的关键配置

连接超时配置在 `HttpClient`：

```java
HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
```

请求超时配置在 `HttpRequest`：

```java
HttpRequest.newBuilder()
        .timeout(Duration.ofSeconds(30));
```

两者区别：

| 超时类型 | 限制对象 | 典型故障 |
|---|---|---|
| 连接超时 | 建立 TCP/TLS 连接的时间 | 网络不可达、目标服务无法建立连接 |
| 请求超时 | 一次请求完成所允许的总时间 | 服务排队、模型生成或响应传输过慢 |

如果复用了已有连接，连接超时未必再次发生。推理模型响应较慢，不能不加分析地设置极短请求超时。

### 6.10 HTTP 成功不等于调用链成功

本次实际遇到的异常为：

```text
UnrecognizedPropertyException:
Unrecognized field "reasoning_content"
```

这说明请求已经经过以下阶段：

```text
网络连接成功
    → 服务端处理成功
    → HTTP 响应返回
    → Jackson 解析响应时失败
```

根因不是 API 无法访问，而是响应 JSON 中存在 `reasoning_content`，原 DTO 只有 `role` 和 `content`。修复方式是新增响应专属 `AssistantMessage`，显式映射该字段，并对其他未知扩展字段保持兼容。

一次完整调用至少有四层成功条件：

1. 网络连接成功；
2. HTTP 状态成功；
3. JSON 可以反序列化；
4. 必需业务字段存在且语义有效。

只检查 HTTP 200 不足以证明应用调用成功。

### 6.11 HTTP 状态与异常分类

第三天将原来统一抛出的 `IllegalStateException` 改为 `LlmClientException`。异常通过 `LlmErrorType` 表达稳定的故障类别，并保留可选的 HTTP 状态码、原始响应体和底层异常原因。上层无需解析异常字符串即可决定是否重试、告警或修正配置。

当前分类如下：

| `LlmErrorType` | 来源 | 默认可重试 |
|---|---|---|
| `AUTHENTICATION` | HTTP 401、403 | 否 |
| `RATE_LIMIT` | HTTP 429 | 是 |
| `SERVER_ERROR` | HTTP 5xx | 是 |
| `REQUEST_TIMEOUT` | HTTP 408 或 `HttpTimeoutException` | 是 |
| `NETWORK_ERROR` | `IOException`、线程中断 | 是 |
| `RESPONSE_FORMAT` | 请求序列化或响应 JSON 解析失败 | 否 |
| `CLIENT_ERROR` | 其他非 2xx 客户端错误 | 否 |

| 故障 | 常见表现 | 是否通常适合重试 |
|---|---|---|
| 参数或请求结构错误 | 400 | 否，应先修正代码或参数 |
| API Key 错误 | 401 | 否，应修正认证配置 |
| 权限不足 | 403 | 否，应检查账号或模型权限 |
| 资源或路径错误 | 404 | 否，应修正 URI 或模型标识 |
| 限流 | 429 | 可以有限重试，并遵守 `Retry-After` |
| 临时服务故障 | 502、503、504 | 通常可以有限重试 |
| 临时网络中断 | `IOException` 等 | 视错误性质有限重试 |
| JSON 映射失败 | Jackson 异常 | 否，重复请求不会修正 DTO |

合理重试需要同时满足：

```text
错误确实具有暂时性
+ 有限次数
+ 指数退避
+ 随机抖动
+ 遵守 Retry-After
+ 考虑 POST 重复执行和重复计费
```

“发生异常就重试”不是容错，而是把确定性错误重复多次。

`retryable` 目前只是错误类别的策略元数据，不表示客户端已经实现自动重试。把“允许考虑重试”和“立即执行重试”分开，可以避免认证错误、格式错误被无意义地重复调用。

### 6.12 耗时统计边界

项目使用：

```java
long start = System.nanoTime();
long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
```

`System.nanoTime()` 是适合计算持续时间的单调时间源，不受手工调时、NTP 校时等系统时间变化影响。名称中的 nano 表示返回单位，不保证硬件测量精度一定达到 1 纳秒。

当前计时包含网络连接、服务端排队、模型生成和响应体接收，但不包含后续 Jackson 反序列化。比较不同实验结果之前，必须先保证计时起止位置一致。

### 6.13 Token 用量记录

响应中的 `usage` 包含：

| 字段 | Java 字段 | 含义 |
|---|---|---|
| `prompt_tokens` | `promptTokens` | 输入消息消耗的 Token |
| `completion_tokens` | `completionTokens` | 模型输出消耗的 Token |
| `total_tokens` | `totalTokens` | 总 Token，通常是前两者之和 |

当前 `CallResult` 和程序入口已经分别输出模型、HTTP 状态、耗时、输入 Token、输出 Token、总 Token 和成功状态。这样才能判断成本变化来自 Prompt 变长还是回答变长。推理 Token 的具体计入方式依模型和 API 而异，应以实际响应与官方说明为准。

### 6.14 可复用构造器与依赖注入

客户端保留了只接收 API Key 的默认构造器，同时增加完整构造器，可注入：

- API URI；
- `HttpClient`；
- `ObjectMapper`；
- 请求超时时间。

这不是为了增加构造器复杂度，而是把外部基础设施从业务逻辑中分离。生产代码使用默认配置，测试代码则可把 API URI 指向本地服务，从而避免真实网络、真实密钥和 Token 消耗。

构造器同时校验 API Key、请求超时时间和注入依赖，避免把无效配置推迟到网络调用阶段才暴露。

### 6.15 本地自动化测试

测试使用 JDK 自带的 `HttpServer` 在随机本地端口模拟响应，没有访问真实 DeepSeek API。当前覆盖五个场景：

1. HTTP 200 正常响应，并验证模型、状态码、耗时、三类 Token、成功状态和回答；
2. HTTP 401 映射为不可重试的认证错误；
3. HTTP 429 映射为可重试的限流错误；
4. HTTP 503 映射为可重试的服务端错误；
5. HTTP 200 但响应体不是合法 JSON，映射为不可重试的响应格式错误。

2026-06-22 执行结果：

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

本轮没有使用 WireMock。JDK `HttpServer` 足以验证当前最小行为；第六天需要模拟延迟、缺字段、更多状态和重试次数时，再引入 WireMock 更合适。

### 6.16 第三天完成度与技术债务

已完成：

- API URI、`HttpClient`、`ObjectMapper` 和请求超时可注入；
- 认证、限流、服务端、请求超时、网络、格式和普通客户端错误可分类；
- 输入、输出、总 Token 及耗时可读取；
- 本地测试不依赖真实 API Key，不产生 Token 费用；
- JUnit 测试可由 Maven Surefire 发现并执行。

仍未完成：

- 没有有限重试、指数退避、抖动和 `Retry-After` 处理；
- 没有结构化、脱敏的观测日志，目前主要由 `App` 输出结果；
- 没有测试连接超时、请求超时、网络中断、HTTP 408 和其他 4xx；
- 没有严格区分空回答与 `choices`、`message`、`usage` 等关键结构缺失；
- 异常仍保存原始响应体，调用方必须避免未经脱敏直接写入日志。

---

## 7. 关键结论

1. Token 不是严格意义上的“词”，而是 tokenizer 产生的模型处理单位。
2. 上下文窗口包含本次推理需要处理的输入和输出预算，但具体计算方式依模型和 API 而异。
3. 超出上下文窗口不一定自动截断，也可能直接报错。
4. Developer、System、User 和 Assistant 不能随意合并，它们承担不同的指令或消息职责。
5. 生成参数不是跨模型统一标准，必须以具体 API Reference 为准。
6. `temperature = 0` 不等于无幻觉，也不保证事实正确。
7. Chat Model 和 Embedding Model 通常都不是天然有状态的。
8. Embedding 相似度不等于事实正确或业务相关，仍需评测。
9. HTTP 200 只表示 HTTP 层成功，不保证 JSON 映射和业务字段有效。
10. 请求 DTO 与响应 DTO 应按协议职责分离，不能只因字段相似而强行复用。
11. `@JsonProperty` 用于明确字段映射，`@JsonIgnoreProperties` 用于兼容不关心的扩展字段。
12. SDK 的 base URL 会由 SDK 拼接资源路径，原生 HTTP 客户端必须提供完整 URI。
13. API Key 应通过环境变量和 Bearer Header 传递，禁止进入源码和日志。
14. `System.nanoTime()` 适合测量持续时间，但实验必须保持计时边界一致。
15. 只记录总 Token 不足以分析成本与长度变化，必须区分输入和输出 Token。
16. 重试必须基于故障类型；401、400 和 JSON 映射错误不应盲目重试。
17. 依赖注入使真实 API 地址和本地测试服务可替换，是客户端可测试性的前提。
18. 可重试标记只是策略信息，自动重试还必须有次数、退避、抖动和幂等性约束。
19. 本地模拟测试可以验证协议行为，但不能替代少量受控的真实 API 集成验证。

## 8. 自测问题

- [ ] 我能否解释 Token 为什么不是字符，也不一定是单词？
- [ ] 我能否列出一次请求中可能占用上下文窗口的内容？
- [ ] 我能否解释上下文窗口与长期记忆的区别？
- [ ] 我能否说明为什么 `temperature = 0` 仍可能产生幻觉？
- [ ] 我能否说明 Developer 指令为什么不能替代后端鉴权？
- [ ] 我能否解释 Chat Model 与 Embedding Model 在 RAG 中的不同职责？
- [ ] 我能否说明向量相似度为什么不等于事实正确？
- [ ] 我能否解释 Java 对象与 JSON 之间的双向转换过程？
- [ ] 我能否区分 `@JsonProperty` 与 `@JsonIgnoreProperties` 的作用？
- [ ] 我能否解释请求消息和响应消息为什么使用不同 DTO？
- [ ] 我能否解释 base URL 与完整 API URI 的关系？
- [ ] 我能否说明连接超时与请求超时的区别？
- [ ] 我能否从 `UnrecognizedPropertyException` 判断故障发生在哪一层？
- [ ] 我能否说明为什么 HTTP 200 不等于整个调用成功？
- [ ] 我能否解释为什么使用 `System.nanoTime()` 测量耗时？
- [ ] 我能否区分输入、输出和总 Token？
- [ ] 我能否判断 400、401、429、503 和 JSON 映射错误是否适合重试？
- [ ] 我能否解释为什么 API URI、HttpClient、ObjectMapper 和超时时间需要支持注入？
- [ ] 我能否说明 `retryable = true` 为什么不等于可以无限重试？
- [ ] 我能否解释本地 HTTP 测试能验证什么、不能验证什么？

## 9. 参考资料

- [OpenAI Tokenizer](https://platform.openai.com/tokenizer)
- [OpenAI Models](https://developers.openai.com/api/docs/models)
- [OpenAI Text Generation Guide](https://developers.openai.com/api/docs/guides/text)
- [OpenAI Embeddings Guide](https://developers.openai.com/api/docs/guides/embeddings)
- [OpenAI API Reference](https://platform.openai.com/docs/api-reference)
- [llm-basics 项目说明](../codes/llm-basics/README.md)

> 文档中的模型名称、上下文长度、参数字段和可选值可能变化。写代码前应再次核对目标模型与 API 端点的当前官方文档。
