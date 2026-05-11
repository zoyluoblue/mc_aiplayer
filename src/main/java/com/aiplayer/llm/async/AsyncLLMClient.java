package com.aiplayer.llm.async;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface AsyncLLMClient {

        CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params);

        String getProviderId();

        boolean isHealthy();
}
