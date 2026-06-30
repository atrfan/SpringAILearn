# 第 2 周学习笔记：Spring AI 与聊天服务

> **学习日期：** 2026-06-24～
>
> **学习阶段：** 第 2 周
>
> **文档定位：** 记录 Spring AI 的核心抽象、同步与流式聊天接口实现、测试过程，以及与第一周原生 Java HTTP 客户端的差异。
>
> **当前进度：** Day10 已完成，同步 `/api/chat` 端点及其 Mock/单元测试可用。

## 目录

- [1. Spring AI 同步调用](#1-spring-ai-同步调用)
- [2. ChatResponse 响应结构](#2-chatresponse-响应结构)
- [3. 获取模型答复内容](#3-获取模型答复内容)
- [4. 本次实现中的注意事项](#4-本次实现中的注意事项)
- [5. Day09：同步聊天端点实现](#5-day09同步聊天端点实现)
- [6. 异常处理设计](#6-异常处理设计)
- [7. Day09 验收结论](#7-day09-验收结论)
- [8. Day10：同步端点测试与异常映射验证](#8-day10同步端点测试与异常映射验证)
- [9. Day10 补充：流式响应问题排查](#9-day10-补充流式响应问题排查)
- [10. Day12：流式 `ChatResponse` 与 Token 统计](#10-day12流式-chatresponse-与-token-统计)

---

## 1. Spring AI 同步调用

当前使用 `ChatClient` 的链式 API 构造用户消息，并通过 `call().chatResponse()` 发起同步模型调用：

```java
var springAiResponse = chatClient.prompt()
        .user(message)
        .call()
        .chatResponse();
```

各步骤的职责如下：

1. `prompt()` 创建一次聊天请求的构造对象。
2. `user(message)` 添加用户消息。
3. `call()` 选择同步调用方式，并返回 `CallResponseSpec`。
4. `chatResponse()` 真正执行调用，返回 Spring AI 的 `ChatResponse`。

因此，不能把 `call()` 返回的 `CallResponseSpec` 当作已经取得的模型响应。真正的请求和结果读取发生在 `chatResponse()`、`content()` 等终结方法中。

## 2. `ChatResponse` 响应结构

调试结果表明，Spring AI 的 `ChatResponse` 主要由以下两部分组成：

```text
ChatResponse
├── metadata
│   ├── id
│   ├── model
│   ├── usage
│   │   ├── promptTokens
│   │   ├── completionTokens
│   │   └── totalTokens
│   └── 其他供应商或框架元数据
└── generations
    └── Generation
        ├── AssistantMessage
        │   ├── textContent
        │   ├── toolCalls
        │   ├── media
        │   └── metadata
        └── ChatGenerationMetadata
            └── finishReason
```

![Spring AI ChatResponse 调试结构](./img/week02-chat-response-structure.png)

### 2.1 `ChatResponseMetadata`

`springAiResponse.getMetadata()` 返回 `ChatResponseMetadata`。当前 DeepSeek 兼容接口返回的主要字段包括：

- `id`：本次模型响应的标识；
- `model`：实际使用的模型名称；
- `usage`：Token 使用情况；
- `rateLimit`：限流信息；供应商没有返回时可能为空实现；
- 其他放入 metadata map 的扩展信息。

Token 用量可以通过以下方式获取：

```java
var metadata = springAiResponse.getMetadata();
Usage usage = metadata.getUsage();

long promptTokens = usage.getPromptTokens();
long completionTokens = usage.getCompletionTokens();
long totalTokens = usage.getTotalTokens();
```

其中：

- `promptTokens` 表示输入消息消耗的 Token；
- `completionTokens` 表示模型生成内容消耗的 Token；
- `totalTokens` 通常为输入与输出 Token 之和。

这些值来自上游模型服务返回的 usage 数据，并不是 Spring AI 在本地重新分词计算的结果。

### 2.2 `generations`

`generations` 是 `Generation` 列表。每个 `Generation` 表示一个候选生成结果，主要包含：

- `AssistantMessage`：模型生成的消息，包括文本、工具调用和媒体内容；
- `ChatGenerationMetadata`：该候选结果的结束原因等元数据，例如 `STOP`。

虽然当前请求通常只返回一个候选结果，但数据模型仍然使用列表，以兼容可能返回多个候选结果的模型或配置。

## 3. 获取模型答复内容

当前代码使用以下调用链取得模型回答：

```java
String content = springAiResponse.getResult()
        .getOutput()
        .getText();
```

这三个方法分别完成不同层次的解包：

```text
ChatResponse
  └── getResult()  -> Generation
        └── getOutput() -> AssistantMessage
              └── getText() -> String
```

### 3.1 `getResult()` 的含义

`ChatResponse#getResult()` 并不执行新的模型调用。它只是返回 `generations` 列表中的第一个元素：

```java
public @Nullable Generation getResult() {
    if (CollectionUtils.isEmpty(this.generations)) {
        return null;
    }
    return this.generations.get(0);
}
```

![ChatResponse getResult 源码](./img/week02-get-result-source.png)

因此，可以近似理解为：

```java
springAiResponse.getResult()
```

等价于：

```java
springAiResponse.getResults().get(0)
```

但 `getResult()` 在列表为空时返回 `null`，而直接调用 `get(0)` 会抛出 `IndexOutOfBoundsException`。

### 3.2 `getOutput()` 的含义

`Generation#getOutput()` 返回的不是字符串，而是 `AssistantMessage`。该对象除回答文本外，还可能包含：

- 工具调用 `toolCalls`；
- 多模态内容 `media`；
- 消息级元数据 `metadata`；
- 消息角色 `ASSISTANT`。

因此，如果目标是取得纯文本回答，还需要继续调用 `getText()`：

```java
String content = springAiResponse.getResult()
        .getOutput()
        .getText();
```

## 4. 本次实现中的注意事项

### 4.1 一个响应规格对象不要执行两次

以下写法存在问题：

```java
var responseSpec = chatClient.prompt()
        .user(message)
        .call();

var response = responseSpec.chatResponse();
String content = responseSpec.content();
```

在当前使用的 Spring AI `2.0.0-M6` 中，`chatResponse()` 和 `content()` 都会触发响应执行。第一次调用会消费 advisor 调用链，第二次调用同一个 `responseSpec` 时可能出现：

```text
No CallAdvisors available to execute
```

正确方式是只执行一次 `chatResponse()`，然后从同一个响应对象读取 metadata 和回答内容：

```java
var response = chatClient.prompt()
        .user(message)
        .call()
        .chatResponse();

var metadata = response.getMetadata();
String content = response.getResult()
        .getOutput()
        .getText();
```

### 4.2 耗时必须覆盖终结调用

由于真正的同步请求发生在 `chatResponse()`，计时结束点必须放在该方法返回之后：

```java
long start = System.nanoTime();

var response = chatClient.prompt()
        .user(message)
        .call()
        .chatResponse();

long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
```

如果在 `call()` 后、`chatResponse()` 前结束计时，得到的主要是本地对象构造耗时，而不是模型请求的端到端耗时。

### 4.3 返回值可能为空

`getResult()` 标注为 `@Nullable`，意味着框架允许 `generations` 为空。生产代码不能无条件假设以下对象一定存在：

- `ChatResponse`；
- `ChatResponseMetadata`；
- `Usage`；
- 第一个 `Generation`；
- `AssistantMessage` 的文本内容。

后续实现统一异常响应时，需要把空结果和上游异常转换为稳定的 HTTP 错误结构，避免直接暴露 `NullPointerException`。

## 5. Day09：同步聊天端点实现

> **计划日期：** 2026-06-26  
> **实际完成日期：** 2026-06-24  
> **目标：** 使用 Spring AI 实现同步 `POST /api/chat` 接口，并返回回答内容、模型名称、Token 用量和端到端耗时。

### 5.1 模块分层

本次实现将 HTTP 接入、模型调用和传输对象分开：

```text
HTTP Client
    │
    │ POST /api/chat
    ▼
ChatController
    │ 参数校验、去除首尾空白
    ▼
MyChatService
    │ 构造 Prompt、调用 ChatClient、解析响应
    ▼
ChatClient
    │
    ▼
OpenAiChatModel
    │ OpenAI 兼容协议
    ▼
DeepSeek API
```

各类职责如下：

| 类 | 职责 |
|---|---|
| `ChatController` | 定义 HTTP 路由、读取 JSON 请求体、校验用户输入 |
| `MyChatService` | 调用模型、统计耗时、提取回答和 Token 元数据 |
| `ChatRequest` | 定义客户端请求结构 |
| `ChatResponse` | 定义成功响应结构 |
| `ErrorResponse` | 定义失败响应结构 |
| `GlobalExceptionHandler` | 将 Java/Spring AI 异常映射为稳定的 HTTP 响应 |
| `OpenAIConfig` | 创建应用使用的 `ChatClient` Bean |

Controller 不直接依赖 `ChatClient`，因此 HTTP 协议处理与模型调用逻辑没有混在一起。后续增加流式接口时，可以继续复用模型配置，并在 Service 层增加独立的流式方法。

### 5.2 请求接口

接口定义为：

```http
POST /api/chat
Content-Type: application/json
```

请求体：

```json
{
  "message": "who are you"
}
```

对应 DTO：

```java
public record ChatRequest(String message) {
}
```

Controller 的实现如下：

```java
@PostMapping("/chat")
ChatResponse chat(@RequestBody ChatRequest request) {
    if (request == null || !StringUtils.hasText(request.message())) {
        throw new IllegalArgumentException("message 不能为空");
    }
    return chatService.chat(request.message().trim());
}
```

这里完成了两项边界处理：

1. 拒绝 `null`、空字符串和只包含空白字符的消息；
2. 调用 Service 前移除消息两端无意义的空白字符。

业务层不需要理解 HTTP 请求体，也不负责返回 HTTP 状态码。

### 5.3 同步模型调用

Service 使用 `ChatClient` 的阻塞式调用：

```java
long start = System.nanoTime();

var springAiResponse = chatClient.prompt()
        .user(message)
        .call()
        .chatResponse();

long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
```

该接口会等待模型生成完成后一次性返回完整结果。它适合验证基本调用链和响应结构，但用户必须等待整个回答生成结束后才能看到内容。逐块返回属于后续流式端点的任务。

使用 `System.nanoTime()` 而不是 `System.currentTimeMillis()` 计算耗时，是因为前者适合测量单调递增的时间间隔，不受系统时间校准影响。

### 5.4 成功响应

自定义成功响应包含以下字段：

```java
public record ChatResponse(
        String model,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long elapsedMillis
) {
}
```

响应示例：

```json
{
  "model": "deepseek-v4-pro",
  "content": "I'm DeepSeek, an AI assistant...",
  "promptTokens": 11,
  "completionTokens": 100,
  "totalTokens": 111,
  "elapsedMillis": 3411
}
```

上述数值来自本次调试记录，只代表该次请求，不应当被当作固定性能数据。模型输出长度、网络状态、服务端负载和缓存命中情况都会影响 Token 数量与耗时。

### 5.5 空字段兼容

供应商兼容 OpenAI 协议，不代表每个响应字段都必然存在。当前 Service 对可空字段进行了降级处理：

| 字段 | 缺失时的处理 |
|---|---|
| 整个 `ChatResponse` | 抛出 `UpstreamResponseException` |
| 模型名称 | 返回 `"unknown"` |
| `Generation` 或回答文本 | 返回空字符串 |
| `Usage` | Token 数返回 `0` |

回答文本通过 `Optional` 安全提取：

```java
Optional.ofNullable(springAiResponse.getResult())
        .map(result -> result.getOutput())
        .map(output -> output.getText())
        .orElse("")
```

Token 数据则通过集中辅助方法读取，避免在构造响应时重复编写多层空值判断。

这种降级策略保证接口结构稳定，但也有代价：`0` 可能表示真实用量为零，也可能表示供应商没有返回 usage；空字符串也无法区分“模型生成了空内容”和“没有 Generation”。如果后续需要严格观测，应增加明确的可用性字段，而不是长期依赖默认值掩盖信息缺失。

## 6. 异常处理设计

### 6.1 统一错误结构

接口失败时返回统一的 `ErrorResponse`：

```java
public record ErrorResponse(
        String code,
        String message,
        long timestamp
) {
}
```

示例：

```json
{
  "code": "INVALID_REQUEST",
  "message": "message 不能为空",
  "timestamp": 1782291600000
}
```

统一结构使客户端不必解析 Spring Boot 默认错误页或 Java 异常文本，也避免将堆栈信息直接暴露给调用方。

### 6.2 异常映射

`GlobalExceptionHandler` 当前采用以下映射：

| 异常或场景 | HTTP 状态 | 错误码 | 对外信息 |
|---|---:|---|---|
| 消息为空 | `400` | `INVALID_REQUEST` | `message 不能为空` |
| JSON 无法解析 | `400` | `INVALID_REQUEST` | 请求体必须是合法的 JSON |
| 上游 Socket 超时 | `504` | `UPSTREAM_TIMEOUT` | 模型服务响应超时 |
| 其他瞬时 AI 异常 | `503` | `UPSTREAM_UNAVAILABLE` | 模型服务暂时不可用 |
| 非瞬时 AI 异常 | `502` | `UPSTREAM_ERROR` | 模型服务调用失败 |
| 上游返回空响应 | `502` | `UPSTREAM_ERROR` | 模型服务调用失败 |
| 未预期异常 | `500` | `INTERNAL_ERROR` | 服务器内部错误 |

其中，只有未预期异常记录完整错误堆栈；可预期的参数错误不需要作为服务器故障记录。

### 6.3 当前映射的边界

Spring AI 的 `TransientAiException` 和 `NonTransientAiException` 是框架层分类，并不等价于第一周自定义的 `LlmErrorType`。当前实现做的是面向 HTTP 客户端的粗粒度映射：

- 临时故障通常映射为 `503`；
- 明确的 Socket 超时映射为 `504`；
- 其他上游不可恢复异常统一映射为 `502`。

这种设计避免把供应商异常细节泄漏给客户端，但也损失了认证失败、模型不存在、配额耗尽等具体类别。Day10 已验证当前 HTTP 边界映射稳定；是否进一步细分错误码，仍需基于真实供应商错误与 Spring AI 异常类型的对应关系决定。

## 7. Day09 验收结论

### 7.1 完成项

- [x] 使用 Spring AI `ChatClient` 完成同步模型调用
- [x] `POST /api/chat` 可接收 JSON 消息并返回模型回答
- [x] 响应包含模型名称、回答内容、输入/输出/总 Token 和耗时
- [x] `ChatController` 只处理 HTTP 路由、请求读取和边界校验
- [x] `MyChatService` 封装模型调用和响应转换
- [x] 提供 `ChatRequest`、`ChatResponse` 和 `ErrorResponse`
- [x] 空消息、非法 JSON、上游异常和未知异常均有结构化错误响应
- [x] 已完成一次真实 DeepSeek API 端到端调用

### 7.2 本日关键结论

1. `ChatClient` 简化了 Prompt 构造、模型调用和响应对象转换，但没有消除对响应结构和可空字段的理解要求。
2. `call()` 只选择同步调用路径，`chatResponse()` 或 `content()` 才会执行并读取结果。
3. 同一个 `CallResponseSpec` 不应重复调用多个终结方法，否则当前 Spring AI 版本可能因 advisor 链已被消费而失败。
4. Spring AI 返回的是框架统一模型：全局元数据位于 `ChatResponseMetadata`，候选回答位于 `generations`。
5. 框架异常分类比第一周的 `LlmErrorType` 粗，需要通过受控测试验证 HTTP 边界映射是否稳定。
6. 同步接口适合建立正确性基线；交互体验仍受完整生成耗时限制，后续需要流式端点改善首字节等待时间。

### 7.3 遗留问题

- 空回答和缺失 usage 目前使用默认值降级，无法表达数据“缺失”与真实零值的区别；
- 已验证框架级异常到 HTTP 响应的映射，但尚未验证所有供应商原始错误与 Spring AI 异常类型之间的对应关系；
- 日志包名 `com.foxmimi.springaidemo` 与当前模块包名不一致，需要后续调整；
- `deepseek-v4-pro` 是否为供应商实际公开模型标识，应以接口返回和供应商文档为准，不能仅根据本地配置推断。

以上遗留项不影响 Day09 同步端点功能验收。

## 8. Day10：同步端点测试与异常映射验证

### 8.1 今日目标

为同步 `/api/chat` 端点补充可重复执行的 Mock/单元测试，隔离需要真实 API Key 和网络的测试，并验证统一异常处理器的主要 HTTP 映射。

### 8.2 测试分层

本日测试按边界分为三层：

| 层级 | 测试对象 | 外部依赖 | 主要验证内容 |
|---|---|---|---|
| Controller 单元测试 | `ChatController`、`GlobalExceptionHandler` | 无 | 请求校验、成功响应和异常到 HTTP 状态的映射 |
| Service 单元测试 | `MyChatService` | 无 | Spring AI 响应解析、默认值降级和空响应处理 |
| 集成测试 | Spring 容器、HTTP 端点、真实模型服务 | API Key、网络 | 真实同步调用和端到端响应结构 |

Controller 测试通过 Mockito 模拟 `MyChatService`，Service 测试通过 Mockito 模拟 `ChatClient`。因此默认测试不需要真实 API Key，也不会产生模型调用费用。

需要真实环境的 `OpenAIChatTest`、`SpringAiChatApplicationTests` 和 `ChatIntegrationTest` 使用 `@Tag("integration")` 标记；Maven Surefire 默认排除该标签，使日常测试只运行确定性较高的本地测试。

### 8.3 新增与补充的测试场景

Controller 层当前覆盖：

- 合法请求返回完整的 `ChatResponse`；
- 空白消息、非法 JSON 和空请求体返回 `400 INVALID_REQUEST`；
- 普通 `TransientAiException` 返回 `503 UPSTREAM_UNAVAILABLE`；
- 根因包含 `SocketTimeoutException` 的瞬时异常返回 `504 UPSTREAM_TIMEOUT`；
- `NonTransientAiException` 返回 `502 UPSTREAM_ERROR`；
- `UpstreamResponseException` 返回 `502 UPSTREAM_ERROR`；
- 未预期的运行时异常返回 `500 INTERNAL_ERROR`。

Service 层当前覆盖：

- 正常响应中的模型名称、回答内容和 Token 用量能够正确转换；
- Generation 或 Metadata 缺失时使用稳定默认值；
- Spring AI 返回 `null` 响应时抛出 `UpstreamResponseException`，避免继续解引用造成无语义的空指针异常。

### 8.4 自动化测试结果

执行命令：

```powershell
mvn clean test
```

执行结果：

```text
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

其中：

- `ChatControllerTest`：9 个测试；
- `MyChatServiceTest`：3 个测试；
- 默认测试未执行带有 `integration` 标签的真实 API 测试。

### 8.5 异常映射验证结论

| 测试输入 | Spring/业务异常 | HTTP 状态 | 错误码 | 结论 |
|---|---|---:|---|---|
| 空白、非法或缺失请求体 | MVC 参数解析或校验异常 | `400` | `INVALID_REQUEST` | 客户端输入错误不会进入模型调用 |
| 临时上游故障 | `TransientAiException` | `503` | `UPSTREAM_UNAVAILABLE` | 提示调用方稍后重试 |
| Socket 读取超时 | `TransientAiException` 包含 `SocketTimeoutException` | `504` | `UPSTREAM_TIMEOUT` | 超时与普通临时故障可以区分 |
| 不可恢复的 AI 调用错误 | `NonTransientAiException` | `502` | `UPSTREAM_ERROR` | 对外隐藏供应商内部错误细节 |
| 上游返回空响应 | `UpstreamResponseException` | `502` | `UPSTREAM_ERROR` | 将非法上游响应转为稳定网关错误 |
| 未分类程序异常 | `RuntimeException` | `500` | `INTERNAL_ERROR` | 避免向客户端暴露堆栈和内部实现 |

这些测试证明的是本项目异常处理器的边界行为，而不是供应商错误分类本身。认证失败、模型不存在和配额耗尽等供应商场景在 Spring AI 中最终落入何种异常类型，仍取决于框架版本及上游响应，不能仅根据手工构造的 `NonTransientAiException` 推断。

### 8.6 Day10 验收结论

- [x] 默认 Maven 测试只运行本地 Mock/单元测试
- [x] 默认测试不依赖真实 API Key 和网络
- [x] Controller 测试覆盖正常请求、输入错误、超时、瞬时异常、非瞬时异常和未知异常
- [x] Service 测试覆盖正常响应、缺失字段降级和空响应
- [x] 12 个自动化测试全部通过
- [x] 形成 Spring AI 异常到 HTTP 状态及业务错误码的对照表

### 8.7 本日关键结论

1. 测试的核心不是数量，而是切断不稳定的外部依赖，使默认测试能够低成本重复执行。
2. `TransientAiException` 只能说明框架认为错误可能恢复，不能直接推出限流、网络抖动或服务过载中的具体一种原因。
3. 超时需要沿异常因果链检查 `SocketTimeoutException`，只检查最外层异常类型会把 `504` 错误地归并到普通 `503`。
4. 空响应不是正常的“空回答”。前者属于上游协议或框架边界异常，应返回 `502`；后者可以按业务约定降级为空字符串。
5. HTTP 错误码应面向接口调用方保持稳定，不应直接复制供应商错误文本或暴露 Java 异常堆栈。

## 9. Day10 补充：流式响应问题排查

### 9.1 问题背景

在同步接口 `POST /api/chat` 已经能够正常返回模型回答的前提下，继续实现流式接口：

```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
Flux<String> chatStream(@RequestParam String message) {
    return chatService.chatStream(message);
}
```

Service 层使用 Spring AI 官方文档中的流式调用方式：

```java
chatClient.prompt()
        .user(message)
        .stream()
        .content();
```

同步接口可用只能说明普通 Chat Completions 调用链正常，不能证明同一个模型、同一个供应商兼容接口一定支持 SSE 流式响应。流式调用是长连接，涉及模型是否支持 streaming、供应商 OpenAI-compatible 协议实现、客户端 SDK 流读取、代理和浏览器消费方式等更多因素。

### 9.2 第一类问题：PowerShell 中 URL 未编码

最初直接执行：

```powershell
curl.exe -N -H "Accept: text/event-stream" "http://localhost:8080/api/chat/stream?message=请详细介绍Spring AI"
```

出现：

```text
curl: (3) URL rejected: Malformed input to a URL function
```

原因是 URL 查询参数中直接包含中文和空格。PowerShell 不会替 `curl.exe` 自动编码 query 参数。

正确方式：

```powershell
curl.exe -N -G `
  -H "Accept: text/event-stream" `
  --data-urlencode "message=请详细介绍 Spring AI" `
  "http://localhost:8080/api/chat/stream"
```

其中 `-N` 用于关闭 curl 输出缓冲，`-G` 和 `--data-urlencode` 用于把消息作为 URL query 参数并自动编码。

### 9.3 第二类问题：`deepseek-v4-pro` 流式响应可能不兼容

当同步 `/api/chat` 能成功，但流式 `/api/chat/stream` 报错时，后端日志出现过：

```text
org.springframework.ai.chat.model.MessageAggregator : Aggregation Error
com.openai.errors.OpenAIIoException: Request failed
Caused by: java.net.SocketException: 你的主机中的软件中止了一个已建立的连接
```

这个异常发生在 Spring AI 到 DeepSeek 上游之间，不是浏览器到本地 Spring Boot 之间。它说明上游流式连接被中断，可能原因包括：

- 本机代理、VPN、防火墙或 HTTPS 扫描软件中断长连接；
- DeepSeek 的 OpenAI-compatible streaming 与当前 Spring AI 客户端存在兼容问题；
- 当前配置的 `deepseek-v4-pro` 对流式响应支持不稳定，或不支持当前调用路径。

本次排查中的关键认识是：`deepseek-v4-pro` 同步调用可用，不代表它一定支持 Spring AI 这条 OpenAI-compatible SSE 流式路径。后续应优先用更保守的模型配置跑通流式链路，例如：

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_KEY}
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
          temperature: 0.0
```

等 `deepseek-chat` 的流式响应确认可用后，再单独测试 `deepseek-v4-pro`。不要同时修改模型、Spring AI 版本、前端 SSE 页面和异常处理依赖，否则无法判断真正原因。

### 9.4 第三类问题：误判为 Spring AI 版本问题

排查过程中曾怀疑 Spring AI `2.0.0-M6` 的流式能力或兼容性存在问题，因此将 Spring AI 降级/切换到 `2.0.0`。

切换后出现新的编译错误：

```text
package org.springframework.ai.retry does not exist
cannot find symbol: class TransientAiException
cannot find symbol: class NonTransientAiException
```

原因是：在当前项目依赖路径下，Spring AI `2.0.0-M6` 会让 `org.springframework.ai.retry.TransientAiException` 和 `org.springframework.ai.retry.NonTransientAiException` 可用于编译；但 Spring AI `2.0.0` 的 `spring-ai-starter-model-openai` 不再单独暴露这些 retry 异常类。

解决方式是在 `pom.xml` 中显式加入：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-retry</artifactId>
</dependency>
```

同时应保持版本声明一致：

```xml
<properties>
    <spring-ai.version>2.0.0</spring-ai.version>
</properties>
```

并让 BOM 使用同一个属性：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-bom</artifactId>
    <version>${spring-ai.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

修复后执行：

```powershell
mvn -q test
```

测试通过。测试输出中的 `RuntimeException: unexpected` 是现有 `ChatControllerTest` 故意触发兜底异常处理产生的日志，不是测试失败。

### 9.5 第四类问题：浏览器不能直接用地址栏实现聊天式流式显示

浏览器地址栏访问 SSE 接口只能看到原始响应，不能自动把每个 chunk 追加到页面中。要实现“内容一段一段出现”，需要前端使用 `EventSource`：

```html
<script>
  let eventSource = null;
  let receivedAnyData = false;

  function startStream() {
    const message = document.getElementById("message").value.trim();
    const output = document.getElementById("output");

    if (!message) {
      output.textContent = "请输入消息";
      return;
    }

    stopStream();
    receivedAnyData = false;
    output.textContent = "";

    const url =
      "http://localhost:8080/api/chat/stream?message=" +
      encodeURIComponent(message);

    eventSource = new EventSource(url);

    eventSource.onmessage = function (event) {
      receivedAnyData = true;
      output.textContent += event.data;
    };

    eventSource.onerror = function () {
      if (!receivedAnyData) {
        output.textContent += "\n\n[连接失败，请检查后端服务或浏览器控制台]";
      }
      stopStream();
    };
  }

  function stopStream() {
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
  }
</script>
```

如果 HTML 页面由 IntelliJ 等工具通过 `localhost:63342` 打开，而后端在 `localhost:8080`，则属于跨域请求。可选解决方式：

1. 将 HTML 放入 Spring Boot 的 `src/main/resources/static/`，通过 `http://localhost:8080/xxx.html` 访问；
2. 或在后端接口上增加允许 `http://localhost:63342` 的 CORS 配置。

### 9.6 第五类问题：`EventSource.onerror` 会在正常结束时触发

页面能显示完整回答后，末尾仍追加：

```text
[连接已结束或发生错误]
```

原因不是模型返回了这句话，而是前端在 `EventSource.onerror` 中手动追加了这句话。`EventSource` 没有标准的 `oncomplete` 回调，服务端 `Flux<String>` 正常发送完并关闭 SSE 连接时，也可能进入同一个错误/关闭处理路径。

正确处理方式是区分“完全没收到数据就失败”和“已经收到数据后流结束”：

```javascript
eventSource.onerror = function () {
  if (!receivedAnyData) {
    output.textContent += "\n\n[连接失败，请检查后端服务或浏览器控制台]";
  }
  stopStream();
};
```

也可以由后端追加一个约定的结束标记，例如 `[DONE]`，前端收到后主动关闭连接，但这需要明确把 `[DONE]` 作为控制消息而不是普通文本显示。

### 9.7 本次排查结论

1. 流式接口排查必须按层隔离：先验证供应商是否支持 streaming，再验证本地后端 SSE，再验证浏览器 `EventSource`。
2. 同步接口成功不等于流式接口成功，尤其不能据此推出 `deepseek-v4-pro` 一定支持当前 SSE 路径。
3. Spring AI 版本切换会改变依赖图。`2.0.0-M6` 下可用的 retry 异常类，在 `2.0.0` 下需要显式添加 `spring-ai-retry`。
4. PowerShell 调用含中文 query 的 GET 接口必须进行 URL 编码。
5. 浏览器端实现流式显示需要 `EventSource`，并处理跨域和正常结束进入 `onerror` 的问题。

## 10. Day12：流式 `ChatResponse` 与 Token 统计

### 10.1 修改目标

早期流式接口使用的是：

```java
chatClient.prompt()
        .user(message)
        .stream()
        .content();
```

该写法直接返回 `Flux<String>`，每个元素是模型生成的增量文本片段。优点是简单，Controller 可以直接把字符串写给前端；缺点也很明显：字符串里只有文本内容，拿不到 `ChatResponseMetadata`，因此无法在流式调用结束后读取模型名称、`Usage` 和 Token 消耗。

为了观察流式响应中的 Token 信息，Service 层改为返回完整响应对象：

```java
public Flux<org.springframework.ai.chat.model.ChatResponse> chatStream(String message) {
    return chatClient.prompt()
            .user(message)
            .stream()
            .chatResponse();
}
```

这里的关键变化是从 `.content()` 改为 `.chatResponse()`：

| 调用方式 | 返回类型 | 适合场景 | 限制 |
|---|---|---|---|
| `.stream().content()` | `Flux<String>` | 只关心前端逐字/逐块显示 | 丢失 metadata 和 usage |
| `.stream().chatResponse()` | `Flux<ChatResponse>` | 需要同时拿文本片段和响应元数据 | Controller 需要自行提取文本 |

因此，当前实现的分工变为：

```text
ChatService
  └── 返回 Flux<ChatResponse>，保留 Spring AI 的完整响应结构

ChatController
  ├── 从每个 ChatResponse 中提取文本片段返回前端
  └── 保存最后一个 ChatResponse，流结束时读取 metadata.usage 并打印日志
```

### 10.2 流式 Token 消耗如何获取

Spring AI 的 Token 信息位于 `ChatResponse` 的 metadata 中：

```java
var metadata = response.getMetadata();
var usage = metadata.getUsage();
```

`Usage` 中通常包含：

```java
usage.getPromptTokens();      // 输入 Token
usage.getCompletionTokens();  // 输出 Token
usage.getTotalTokens();       // 总 Token
```

当前流式实现没有在每个 chunk 到达时立即统计 Token，而是在流结束时读取最后一次收到的 `ChatResponse`：

```java
final AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();

return chatService.chatStream(trimmedMessage)
        .doOnNext(lastResponse::set)
        .concatWith(Mono.fromRunnable(() -> {
            var response = lastResponse.get();
            if (response != null) {
                var metadata = response.getMetadata();
                LOGGER.info("本次请求 ChatResponse: model={}, usage={}, elapsed={}ms",
                        metadata.getModel(),
                        metadata.getUsage(),
                        elapsedMillisSince(start));
            }
        }).thenMany(Flux.empty()));
```

这里使用“最后一个 `ChatResponse`”的原因是：流式响应是分块返回的，前面的 chunk 往往只包含当前增量文本，不一定包含完整 usage。很多 OpenAI-compatible 协议会在最后一个 chunk 或最终聚合响应中给出 usage；也有供应商在流式模式下根本不返回 usage。实际是否可用必须看 `metadata.getUsage()` 是否为 `null` 或是否包含有效 Token 数。

这点不能靠猜。同步接口能拿到 Token，不代表流式接口一定能拿到 Token。当前代码通过日志直接观察：

```text
model=...
usage=...
elapsed=...ms
```

如果日志中 `usage` 为 `null` 或字段为空，说明当前供应商、模型、Spring AI 版本或调用路径没有暴露流式 usage。此时不能在报告里写“支持流式 Token 统计”，只能写“当前实现保留了 `ChatResponse`，但该调用链是否返回 usage 取决于上游响应”。

### 10.3 Controller 流式代码逐行解析

当前 Controller 核心代码如下：

```java
return chatService.chatStream(trimmedMessage)
        .timeout(STREAM_TIMEOUT)
        .doOnNext(lastResponse::set)
        .map(response -> {
            var result = response.getResult();
            if (result == null) {
                return "";
            }
            return Optional.ofNullable(result.getOutput().getText()).orElse("");
        })
        .concatWith(Mono.fromRunnable(() -> {
            var response = lastResponse.get();
            if (response != null) {
                var metadata = response.getMetadata();
                LOGGER.info("本次请求 ChatResponse: model={}, usage={}, elapsed={}ms",
                        metadata.getModel(),
                        metadata.getUsage(),
                        elapsedMillisSince(start));
            } else {
                LOGGER.info("本次请求结束，但未获得 ChatResponse");
            }
        }).thenMany(Flux.empty()))
        .onErrorResume(exception -> {
            LOGGER.warn("流式聊天请求异常终止：{}", exception.toString());
            return Flux.empty();
        })
        .doOnCancel(() -> LOGGER.info("客户端取消流式聊天请求"));
```

#### 10.3.1 `chatService.chatStream(trimmedMessage)`

调用 Service 层的流式方法，返回类型是：

```java
Flux<org.springframework.ai.chat.model.ChatResponse>
```

`Flux` 表示 0 到 N 个异步元素。对流式大模型响应来说，每个元素可以理解为一个响应片段。这里不是完整对话结果，而是一段一段到达的 `ChatResponse`。

#### 10.3.2 `.timeout(STREAM_TIMEOUT)`

作用：给整条响应流设置超时保护。

如果上游模型长时间不返回任何新元素，Reactor 会触发超时异常，避免 HTTP 连接无限悬挂。这里解决的是流式接口中常见的“浏览器一直转圈、后端线程或连接不释放”问题。

需要注意：`timeout` 不是模型生成参数，不会限制模型最大输出 Token；它只是 Reactor 层面对响应流等待时间的保护。

#### 10.3.3 `.doOnNext(lastResponse::set)`

作用：每收到一个 `ChatResponse`，就把它保存为“最近一次响应”。

`doOnNext` 是副作用操作，不改变流里的元素。也就是说，流中继续向后传递的仍然是原来的 `ChatResponse`。

使用 `AtomicReference` 的原因是 lambda 中需要保存可变状态，并且 Reactor 流可能跨线程执行。这里保存最后一个响应，是为了在流正常结束后读取其 metadata：

```java
var response = lastResponse.get();
var metadata = response.getMetadata();
var usage = metadata.getUsage();
```

#### 10.3.4 `.map(response -> ...)`

作用：把 `ChatResponse` 转换成前端真正需要的字符串片段。

`map` 是 Reactor 中最常用的同步转换算子。它的基本形式是：

```java
Flux<A> source = ...;
Flux<B> target = source.map(a -> convertToB(a));
```

含义是：上游每发出一个元素 `A`，`map` 就立即调用一次转换函数，并把返回值作为新的元素 `B` 继续向下游发送。它不会改变元素数量：

```text
上游：ChatResponse1, ChatResponse2, ChatResponse3
map：提取 getText()
下游："你", "好", "。"
```

因此，当前代码中流的类型在 `map` 前后发生了变化：

```java
Flux<ChatResponse>   // map 之前
Flux<String>         // map 之后
```

这正好符合 Controller 的职责：Service 保留完整 `ChatResponse` 以便读取 metadata，Controller 再把每个响应片段投影成前端需要显示的文本。

Spring AI 的文本内容不直接位于 `ChatResponse` 顶层，而是：

```text
ChatResponse
  └── getResult()
        └── getOutput()
              └── getText()
```

因此当前代码做了两层保护：

```java
var result = response.getResult();
if (result == null) {
    return "";
}
return Optional.ofNullable(result.getOutput().getText()).orElse("");
```

这可以避免某个中间 chunk 没有生成文本时直接抛出空指针异常。前端收到空字符串通常不会产生可见输出，但连接仍然保持正常。

严格来说，这里还可以继续防御 `result.getOutput()` 为 `null` 的情况。当前代码假设只要 `result != null`，`output` 就存在；这个假设在多数 Spring AI 文本响应中成立，但如果要做生产级防御，可以改为完整 Optional 链。

`map` 的几个边界需要记住：

1. `map` 适合做同步、快速、无阻塞的转换，例如字段提取、DTO 转换、字符串拼接；
2. `map` 中不要做耗时 I/O，例如数据库查询、HTTP 请求、文件写入，否则会阻塞响应流；
3. `map` 函数抛出的异常会变成流错误，后续会被当前代码里的 `onErrorResume` 捕获；
4. `map` 不能返回 `null`。Reactor 不允许流中出现 `null` 元素。如果转换结果可能为空，应返回空字符串、Optional 包装后的默认值，或改用 `handle` / `flatMap` 做更精细控制。

所以当前代码返回 `""` 而不是 `null` 是必要的。如果返回 `null`，流会抛出空值相关异常，前端连接会被异常路径终止。

#### 10.3.5 `.concatWith(Mono.fromRunnable(...).thenMany(Flux.empty()))`

作用：在原始文本流正常完成后，追加一个“只执行日志、不向前端发送内容”的收尾动作。

拆开看：

```java
Mono.fromRunnable(() -> {
    // 打印日志
})
```

表示创建一个只执行副作用、不产生数据的 `Mono`。

`Mono` 表示最多发出 1 个元素的异步序列；`Flux` 表示 0 到 N 个元素的异步序列。`Mono.fromRunnable` 的特殊之处是：它包装的是一个 `Runnable`，而 `Runnable#run()` 没有返回值。因此它的语义不是“生成一个数据”，而是“订阅时执行一段动作，然后完成”。

可以把它理解成：

```text
订阅发生
  -> 执行 Runnable 里的日志代码
  -> 不发送任何 onNext 数据
  -> 发送 onComplete
```

这很适合做收尾副作用，例如：

```java
Mono.fromRunnable(() -> LOGGER.info("stream finished"));
```

它与 `Mono.just(...)` 不同：

```java
Mono.just(value)          // 立即持有一个要发送的数据
Mono.fromRunnable(task)   // 订阅时执行 task，但不发送数据
```

也与 `doOnComplete(...)` 有区别。`doOnComplete` 只能挂在原流完成事件上做副作用；`Mono.fromRunnable(...).thenMany(...)` 可以构造一个明确的“后续阶段”，再通过 `concatWith` 接到原流后面。当前代码选择这种写法，是为了把“流结束后的日志动作”作为一个附加的 Publisher 组合进链路中。

```java
.thenMany(Flux.empty())
```

表示 Runnable 执行完后，接一个空的 `Flux`。因此它不会向前端额外发送字符串，只会完成日志记录。

`thenMany` 的语义是：

```java
Mono<Void> first = ...;
Flux<T> second = ...;

Flux<T> result = first.thenMany(second);
```

也就是：先等待前面的 `Mono` 正常完成，忽略它的元素，然后订阅并返回后面的 `Flux`。

在当前代码中：

```java
Mono.fromRunnable(...).thenMany(Flux.empty())
```

实际效果是：

```text
先执行日志 Runnable
然后接一个空 Flux
最终不产生任何额外字符串
```

为什么要接 `Flux.empty()`？因为主链路在 `.map(...)` 之后已经是 `Flux<String>`。`concatWith(...)` 需要拼接另一个能产生同类型元素的 Publisher。我们只想执行日志，不想给前端多发一个字符串，所以用 `Flux.empty()` 表示“类型上是 Flux<String>，但实际没有任何元素”。

如果这里写成下面这样：

```java
.concatWith(Mono.fromRunnable(() -> LOGGER.info("done")))
```

在某些泛型推断下会遇到类型不匹配或语义不清的问题，因为 `Mono.fromRunnable` 本质是 `Mono<Void>`，而前面的流是 `Flux<String>`。`.thenMany(Flux.empty())` 把收尾动作转换成了一个“不发数据的 Flux<String>”，与前面的文本流更容易拼接。

```java
.concatWith(...)
```

表示等前面的文本流正常结束后，再执行后面的收尾流。这个顺序很重要：只有正常结束时，才说明可以读取“最后一次响应”作为本次调用的最终观测结果。

如果上游中途异常，流程会进入 `onErrorResume`，这段正常结束日志不会执行。

因此这整段组合的执行顺序可以写成：

```text
1. 前端订阅 SSE 接口
2. chatService.chatStream 开始从模型上游接收 ChatResponse
3. 每个 ChatResponse 经过 map 转成 String，持续发给前端
4. 上游正常完成
5. concatWith 开始执行追加阶段
6. Mono.fromRunnable 读取 lastResponse，打印 model / usage / elapsed
7. thenMany(Flux.empty()) 不发送任何额外内容
8. 整个 SSE 流正常完成
```

这也解释了为什么 Token 统计放在 `concatWith` 中，而不是放在 `map` 中：`map` 每个 chunk 都执行，适合提取文本；Token usage 通常要等最终响应才完整，适合在流正常结束后统一记录。

#### 10.3.6 `.onErrorResume(exception -> ...)`

作用：捕获流式过程中的异常，记录告警日志，然后返回空流结束响应。

当前策略是：

```java
LOGGER.warn("流式聊天请求异常终止：{}", exception.toString());
return Flux.empty();
```

这意味着：一旦上游超时、模型错误或流式协议中断，服务端不再向前端抛出异常响应，而是静默结束 SSE 流，同时在后端日志中保留原因。

这个设计适合学习阶段先保证页面不崩，但也有代价：前端无法区分“模型正常说完了”和“中途异常结束”。如果后续要做更严谨的产品接口，应考虑返回结构化 SSE 错误事件，例如：

```text
event: error
data: {"code":"UPSTREAM_TIMEOUT","message":"模型服务响应超时"}
```

否则异常只存在于后端日志中，前端体验和可观测性都不完整。

#### 10.3.7 `.doOnCancel(...)`

作用：客户端主动断开连接时记录日志。

典型触发场景包括：

- 浏览器页面关闭；
- 前端调用 `eventSource.close()`；
- 用户点击“停止”按钮；
- 网络中断；
- 测试中主动 cancel 订阅。

`doOnCancel` 的价值在于区分“上游模型失败”和“客户端不想继续接收”。这两类事件在成本分析上不同：如果客户端已经断开，服务端应尽量取消上游生成，避免继续消耗 Token。

### 10.4 当前实现的优点与边界

当前实现相比 `Flux<String>` 版本有两个明确优势：

1. 保留了 `ChatResponse`，可以在流结束时读取 `metadata.getModel()` 和 `metadata.getUsage()`；
2. 前端仍然只接收文本片段，不需要理解 Spring AI 的复杂响应结构。

但它也有几个边界：

1. Token 统计只打印到控制台，没有作为最终事件返回前端；
2. `onErrorResume` 会把异常转换为空流，前端无法知道是否异常结束；
3. 如果最后一个 `ChatResponse` 不包含 usage，日志也无法得到 Token；
4. 如果中途取消，`concatWith` 的正常收尾日志不会执行，因此取消场景下通常拿不到完整 Token 统计；
5. 当前接口仍是 `GET + EventSource`，复杂请求体、系统提示词和多轮上下文会受 query 参数表达能力限制。

因此，当前结论应写成：

> 已经从 `Flux<String>` 改为 `Flux<ChatResponse>`，具备从流式响应中读取 metadata 和 usage 的能力；实际 Token 是否可用取决于最后收到的 `ChatResponse` 是否包含上游返回的 `Usage`。

不能写成：

> 流式接口一定可以统计 Token。

后者过度推断了框架能力。Spring AI 暴露了读取路径，不等于所有供应商、所有模型、所有流式模式都会返回 usage。

## 11. 第二周收尾结论

截至 2026-06-29，第二周内容到流式响应和流式 Token 观测为止，不再继续推进剩余测试、完整对比分析和全量验收。

本周保留的有效产出包括：

1. 同步 `/api/chat` 端点：能够返回模型回答、模型名称、Token 用量和耗时；
2. 流式 `/api/chat/stream` 端点：能够通过 SSE 逐块返回文本内容；
3. 流式 Token 观测：Service 层从 `.stream().content()` 改为 `.stream().chatResponse()`，Controller 保存最后一次 `ChatResponse`，流结束后从 `metadata.getUsage()` 读取并打印 Token 信息；
4. 流式排查记录：记录了 URL 编码、模型兼容性、Spring AI 版本差异、浏览器 `EventSource` 行为和正常结束误判等问题；
5. 前端演示页面：用于手动观察流式输出效果。

不再继续的内容包括：

1. 使用 `WebTestClient` 或类似工具补充流式自动化测试；
2. 对流式取消、超时、模型错误做更完整的测试矩阵；
3. 原生客户端与 Spring AI 的完整八维对比分析；
4. Day14 的全量构建验收、第三周衔接和技术债务清单。

当前结论应保持克制：Spring AI 简化了同步和流式调用的接入，但它没有消除供应商兼容性、Token 观测差异、异常分类粗粒度和浏览器 SSE 行为差异。尤其是流式 Token 统计，只能说当前代码保留了读取 `Usage` 的路径，不能保证所有模型和供应商都会返回该数据。
