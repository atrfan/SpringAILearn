# llm-basics

## 项目概述

这是一个使用 Java 21 原生 `HttpClient` 调用 DeepSeek Chat Completions API 的最小示例。项目没有使用 Spring AI 或厂商 SDK，目的是直接理解一次大模型 API 调用的完整过程：

```text
Java 请求对象
    -> Jackson 序列化为 JSON
    -> Java HttpClient 发送 HTTPS POST
    -> DeepSeek 返回 HTTP 响应和 JSON
    -> Jackson 反序列化为 Java 响应对象
    -> 程序读取回答、Token 用量、HTTP 状态和耗时
```

当前项目已经实现：

- 从环境变量读取 API Key；
- 构造 system 和 user 消息；
- 启用推理模式并设置推理强度；
- 使用 Bearer Token 完成身份认证；
- 解析普通回答和 `reasoning_content`；
- 记录模型、HTTP 状态码、调用耗时、输入/输出/总 Token 和成功状态；
- 通过统一异常分类认证、限流、服务端、超时、网络和响应格式错误；
- 注入 API URI、`HttpClient`、`ObjectMapper` 和请求超时配置；
- 使用 JDK 本地 HTTP Server 完成正常响应及典型失败场景测试。

当前项目尚未实现：

- 自动重试与退避策略；
- 完整的 WireMock 故障模拟；
- 流式响应；
- 结构化的请求和响应脱敏日志；
- 对响应关键字段缺失的严格校验；
- 多轮对话状态管理。

因此，它目前是一个教学用最小客户端，不是可直接用于生产环境的 SDK。

## 环境要求

- JDK 21
- Maven 3.9 或兼容版本
- 可用的 DeepSeek API Key
- 能访问 `https://api.deepseek.com`

检查本机环境：

```powershell
java -version
mvn -version
```

## 项目结构

```text
llm-basics/
├── pom.xml
├── README.md
├── src/main/java/com/foxmimi/
│   ├── App.java
│   ├── client/
│   │   └── DeepSeekClient.java
│   ├── exception/
│   │   ├── LlmClientException.java
│   │   └── LlmErrorType.java
│   └── model/
│       ├── ChatMessage.java
│       ├── ChatRequest.java
│       ├── AssistantMessage.java
│       └── ChatResponse.java
└── src/test/java/com/foxmimi/client/
    └── DeepSeekClientTest.java
```

各类的职责如下：

| 类 | 职责 |
|---|---|
| `App` | 程序入口，读取配置、构造请求、调用客户端并输出结果 |
| `DeepSeekClient` | JSON 转换、HTTP 请求、状态判断、耗时统计和响应解析 |
| `LlmClientException` | 统一封装错误类别、状态码、响应体和底层异常 |
| `LlmErrorType` | 定义稳定故障类别及其默认可重试属性 |
| `ChatMessage` | 描述发送给模型的单条请求消息 |
| `ChatRequest` | 描述完整的请求 JSON |
| `AssistantMessage` | 描述模型返回的 assistant 消息，包括推理内容 |
| `ChatResponse` | 描述完整的响应 JSON，并提供安全读取回答的方法 |

## 配置与运行

### 1. 设置 API Key

当前代码读取的环境变量名是 `DEEPSEEK_KEY`：

```powershell
$env:DEEPSEEK_KEY="你的 API Key"
```

该命令只对当前 PowerShell 会话生效。不要把真实密钥写进源码、`pom.xml`、README 或 Git 提交。

程序只输出：

```text
DEEPSEEK_KEY 已读取
```

这只能证明变量存在，不会泄露密钥内容。

### 2. 编译

在 `llm-basics` 目录执行：

```powershell
mvn compile
```

### 3. 运行

当前 `pom.xml` 没有配置可执行 JAR 或 Maven Exec 插件，最直接的运行方式是在 IDE 中执行 `App.main()`。

程序成功后会输出：

```text
回答：...
模型：deepseek-v4-pro
HTTP 状态：200
耗时：... ms
输入 Token：...
输出 Token：...
总 Token：...
调用成功：true
```

### 4. 测试

测试使用本机随机端口，不调用真实 DeepSeek API，也不需要真实 API Key：

```powershell
mvn test
```

2026-06-22 验证结果：5 个测试全部通过，无失败、错误或跳过项。

## 请求对象与 JSON

### Java record

项目使用 Java `record` 定义 DTO（Data Transfer Object）：

```java
public record ChatMessage(
        String role,
        String content
) {}
```

`record` 适合表示 API 数据，因为它会自动生成：

- 全参数构造器；
- 与字段同名的访问方法，如 `role()` 和 `content()`；
- `equals()`、`hashCode()` 和 `toString()`。

DTO 的主要职责是表达数据结构，不应承担网络请求、重试或业务流程。

### ChatRequest

当前请求对象包含：

| Java 字段 | JSON 字段 | 含义 |
|---|---|---|
| `model` | `model` | 要调用的模型标识 |
| `messages` | `messages` | system、user 等对话消息 |
| `stream` | `stream` | 是否使用流式响应 |
| `reasoningEffort` | `reasoning_effort` | 推理强度 |
| `thinking` | `thinking` | 是否启用思考模式 |

Java 使用驼峰命名，而 API 使用下划线命名，因此需要：

```java
@JsonProperty("reasoning_effort")
String reasoningEffort
```

当前 `App` 构造的请求大致会被序列化为：

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

Python SDK 示例中的 `extra_body` 是 SDK 用于追加请求字段的参数，不是最终 HTTP JSON 的字段。原生 Java 请求应直接发送顶层 `thinking`，不能发送：

```json
{
  "extra_body": {
    "thinking": {
      "type": "enabled"
    }
  }
}
```

## 请求消息与响应消息为什么分离

请求中的消息结构通常只有：

```json
{
  "role": "user",
  "content": "Hello"
}
```

启用思考模式后，响应中的 assistant 消息还可能包含：

```json
{
  "role": "assistant",
  "content": "最终回答",
  "reasoning_content": "推理内容"
}
```

因此项目分别使用：

- `ChatMessage`：请求消息；
- `AssistantMessage`：响应消息。

如果两者强行共用一个类型，请求对象就会包含没有意义的 `reasoningContent`，甚至可能向服务端发送 `reasoning_content: null`。分离 DTO 能使数据模型与真实协议保持一致，也便于以后处理 `tool_calls` 等响应专属字段。

`reasoning_content` 同样通过 `@JsonProperty` 映射：

```java
@JsonProperty("reasoning_content")
String reasoningContent
```

## Jackson JSON 映射

项目通过 `ObjectMapper` 完成两个方向的转换。

Java 对象转换为请求 JSON：

```java
String requestBody = objectMapper.writeValueAsString(chatRequest);
```

响应 JSON 转换为 Java 对象：

```java
ChatResponse response = objectMapper.readValue(
        httpResponse.body(),
        ChatResponse.class
);
```

### 未知字段异常

曾经出现过：

```text
UnrecognizedPropertyException: Unrecognized field "reasoning_content"
```

这不是网络错误。它表示：

1. HTTP 请求已经成功；
2. 服务端已经返回 JSON；
3. Jackson 在把 JSON 转成 Java 对象时发现 DTO 缺少对应字段。

项目采用两种措施处理：

1. 对有业务价值的 `reasoning_content` 显式建模；
2. 对当前不关心的扩展字段使用：

```java
@JsonIgnoreProperties(ignoreUnknown = true)
```

不能把所有未知字段都简单忽略。重要字段应显式定义，否则接口已经发生变化而程序却可能悄悄丢失数据。

## Java HttpClient 调用过程

### HttpClient

客户端通过 Java 21 自带的 `java.net.http.HttpClient` 创建，不需要额外 HTTP 依赖：

```java
HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
```

连接超时表示建立 TCP/TLS 连接最多等待 10 秒。

### HttpRequest

请求包含四个关键部分：

```java
HttpRequest.newBuilder()
        .uri(API_URI)
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build();
```

- URI：完整接口地址；
- 请求超时：整个请求允许等待的时间；
- `Authorization`：Bearer Token 身份认证；
- `Content-Type`：声明请求体是 JSON；
- POST Body：Jackson 生成的 JSON 字符串。

### base URL 与完整 URL

厂商 SDK 示例通常配置：

```text
base_url = https://api.deepseek.com
```

SDK 在执行 `chat.completions.create()` 时会自动追加 `/chat/completions`。Java 原生 `HttpClient` 不会自动拼接接口路径，因此代码使用完整地址：

```text
https://api.deepseek.com/chat/completions
```

这两个写法并不冲突：前者是 SDK 基础地址，后者是最终 HTTP 请求地址。

## 响应状态和异常

HTTP 状态码在 200 到 299 之间时进入响应解析；其他状态由 `LlmErrorType` 分类：

```java
case 401, 403 -> AUTHENTICATION;
case 408 -> REQUEST_TIMEOUT;
case 429 -> RATE_LIMIT;
default -> statusCode >= 500 && statusCode <= 599
        ? SERVER_ERROR
        : CLIENT_ERROR;
```

常见失败类型包括：

| 类型 | 常见表现 | 当前处理情况 |
|---|---|---|
| 认证失败 | HTTP 401、403 | `AUTHENTICATION`，不可重试 |
| 限流 | HTTP 429 | `RATE_LIMIT`，可考虑有限重试 |
| 服务端故障 | HTTP 5xx | `SERVER_ERROR`，可考虑有限重试 |
| 请求超时 | HTTP 408、`HttpTimeoutException` | `REQUEST_TIMEOUT` |
| 网络或读取失败 | `IOException` | `NETWORK_ERROR` |
| JSON 结构不匹配 | Jackson 映射异常 | `RESPONSE_FORMAT`，不可重试 |

所有类别统一封装为 `LlmClientException`。`retryable()` 只提供策略信息，客户端目前不会自动重试。异常中的原始响应体可能包含敏感数据，不能未经脱敏直接记录。

## 耗时统计

项目使用：

```java
long start = System.nanoTime();
long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
```

`System.nanoTime()` 适合测量持续时间，因为它是单调时间源，不受系统时钟校准影响。`System.currentTimeMillis()` 更适合记录时间点，不适合精确测量一段代码的执行耗时。

当前耗时范围包含：

- 网络连接；
- 服务端排队与模型生成；
- 响应体传输。

它不包含响应 JSON 的反序列化，因为计时在 `readValue()` 之前结束。后续做性能实验时必须明确测量边界，否则不同实验的数据不可直接比较。

## Token 用量

响应中的 `usage` 包含：

| 字段 | 含义 |
|---|---|
| `prompt_tokens` | 请求消息消耗的 Token |
| `completion_tokens` | 模型输出消耗的 Token |
| `total_tokens` | 总 Token，通常是前两者之和 |

Token 不是字符数，也不等同于单词数。模型会先通过 tokenizer 把文本切分成 Token。中文字符、英文单词、数字和标点的切分方式不同，因此不能用字符串长度准确推算 Token 数。

当前 `CallResult` 已分别提供 `promptTokens()`、`completionTokens()` 和 `totalTokens()`。做实验时应分别记录三者，否则无法判断成本变化来自提示词还是模型回答。

## ChatResponse 的安全读取

服务端可能返回空 `choices`、空 `message` 或空 `content`。直接执行：

```java
choices.getFirst().message().content()
```

可能产生 `NoSuchElementException` 或 `NullPointerException`。因此 `ChatResponse` 提供：

```java
response.answer();
response.reasoningContent();
```

这两个方法会进行必要的空值检查，并在内容不存在时返回空字符串。

返回空字符串虽然能避免程序崩溃，但也可能掩盖异常响应。后续更严格的客户端应区分“模型确实返回空内容”和“响应结构缺失”两种情况。

## 复习时应掌握的问题

完成本项目后，应能不查代码回答以下问题：

1. Java 对象如何转换成 JSON，JSON 又如何转换回 Java 对象？
2. `@JsonProperty` 和 `@JsonIgnoreProperties` 分别解决什么问题？
3. 为什么请求消息和响应消息不应强行共用同一个 DTO？
4. SDK 的 base URL 为什么与原生 HTTP 客户端的完整 URL 不同？
5. `Authorization: Bearer ...` 的作用是什么？
6. 连接超时和请求超时有什么区别？
7. HTTP 调用成功后为什么仍可能出现 Jackson 异常？
8. 为什么测量耗时应使用 `System.nanoTime()`？
9. 输入 Token、输出 Token 和总 Token 有什么区别？
10. 哪些错误可以重试，哪些错误不应盲目重试？

## 后续改进顺序

建议按以下顺序继续扩展：

1. 严格校验 `choices`、`message`、`content` 和 `usage` 等关键响应字段；
2. 使用 WireMock 扩展超时、缺字段和更多 HTTP 状态测试；
3. 只对 429、部分 5xx 和暂时性网络错误实现有限重试；
4. 加入指数退避、随机抖动并处理 `Retry-After`；
5. 增加结构化、脱敏日志，禁止记录 API Key 和未脱敏响应；
6. 增加参数对照实验，并把每次结果保存为 CSV。

这些改进完成后，客户端将从教学原型进一步接近可用于真实工程的可靠客户端。
