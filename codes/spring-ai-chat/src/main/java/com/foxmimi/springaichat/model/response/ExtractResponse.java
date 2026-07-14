package com.foxmimi.springaichat.model.response;

import com.foxmimi.springaichat.model.domain.ExtractResult;

public record ExtractResponse(String model, ExtractResult data,
                              Integer promptTokens,
                              Integer completionTokens,
                              Integer totalTokens,
                              Long elapsedMillis
) {

}
