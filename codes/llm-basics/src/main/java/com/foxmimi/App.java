package com.foxmimi;


import com.foxmimi.client.DeepSeekClient;
import com.foxmimi.model.ChatMessage;
import com.foxmimi.model.ChatRequest;

import java.io.IOException;
import java.util.List;

public class App {

    public static void main(String[] args) throws IOException, InterruptedException {
        String apiKey = System.getenv("DEEPSEEK_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 DEEPSEEK_KEY"
            );
        }

        // 只能验证是否读取成功，禁止打印完整密钥
        System.out.println("DEEPSEEK_KEY 已读取");

        ChatRequest request = new ChatRequest(
                "deepseek-v4-pro",
                List.of(
                        new ChatMessage(
                                "system",
                                "You are a helpful assistant"
                        ),
                        new ChatMessage(
                                "user",
                                "Hello"
                        )
                ),
                false,
                "high",
                new ChatRequest.Thinking("enabled")
        );
        DeepSeekClient client = new DeepSeekClient(apiKey);

        DeepSeekClient.CallResult result = client.chat(request);

        System.out.println("回答：" + result.response().answer());
        System.out.println("HTTP 状态：" + result.statusCode());
        System.out.println("耗时：" + result.elapsedMillis() + " ms");
        System.out.println("总 Token：" +
                result.response().usage().totalTokens());
    }
}