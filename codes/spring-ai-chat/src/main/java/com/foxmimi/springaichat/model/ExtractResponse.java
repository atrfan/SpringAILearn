package com.foxmimi.springaichat.model;

public record ExtractResponse(String model, ExtractResult data,
                              Integer promptTokens,
                              Integer completionTokens,
                              Integer totalTokens,
                              Long elapsedMillis
) {

}
