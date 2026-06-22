package com.foxmimi.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeGenerationParametersWithApiFieldNames() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(
                createRequest(0.0, 512)
        ));

        assertAll(
                () -> assertTrue(json.get("temperature").isNumber()),
                () -> assertEquals(0.0, json.get("temperature").asDouble()),
                () -> assertEquals(512, json.get("max_tokens").asInt()),
                () -> assertFalse(json.has("maxTokens"))
        );
    }

    @Test
    void shouldOmitOptionalNullParameters() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(
                createRequest(null, null)
        ));

        assertAll(
                () -> assertFalse(json.has("temperature")),
                () -> assertFalse(json.has("max_tokens"))
        );
    }

    @Test
    void shouldRejectInvalidGenerationParameters() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> createRequest(-0.1, 512)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> createRequest(2.1, 512)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> createRequest(Double.NaN, 512)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> createRequest(0.7, 0))
        );
    }

    private static ChatRequest createRequest(Double temperature, Integer maxTokens) {
        return new ChatRequest(
                "deepseek-v4-pro",
                List.of(new ChatMessage("user", "Hello")),
                temperature,
                false,
                maxTokens,
                "high",
                new ChatRequest.Thinking("enabled")
        );
    }
}
