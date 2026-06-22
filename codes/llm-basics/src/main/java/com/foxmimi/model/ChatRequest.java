package com.foxmimi.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        boolean stream,

        @JsonProperty("reasoning_effort")
        String reasoningEffort,

        Thinking thinking
) {
    public record Thinking(String type) {}
}