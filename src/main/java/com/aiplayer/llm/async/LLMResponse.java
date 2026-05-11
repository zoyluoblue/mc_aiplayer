package com.aiplayer.llm.async;

import java.util.Objects;

public class LLMResponse {

    private final String content;
    private final String model;
    private final int tokensUsed;
    private final long latencyMs;
    private final String providerId;
    private final boolean fromCache;

    private LLMResponse(Builder builder) {
        this.content = Objects.requireNonNull(builder.content, "content cannot be null");
        this.model = Objects.requireNonNull(builder.model, "model cannot be null");
        this.providerId = Objects.requireNonNull(builder.providerId, "providerId cannot be null");
        this.tokensUsed = builder.tokensUsed;
        this.latencyMs = builder.latencyMs;
        this.fromCache = builder.fromCache;
    }

        public String getContent() {
        return content;
    }

        public String getModel() {
        return model;
    }

        public int getTokensUsed() {
        return tokensUsed;
    }

        public long getLatencyMs() {
        return latencyMs;
    }

        public String getProviderId() {
        return providerId;
    }

        public boolean isFromCache() {
        return fromCache;
    }

        public LLMResponse withCacheFlag(boolean cacheFlag) {
        return new Builder()
            .content(this.content)
            .model(this.model)
            .providerId(this.providerId)
            .tokensUsed(this.tokensUsed)
            .latencyMs(this.latencyMs)
            .fromCache(cacheFlag)
            .build();
    }

        public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "LLMResponse{" +
            "providerId='" + providerId + '\'' +
            ", model='" + model + '\'' +
            ", tokensUsed=" + tokensUsed +
            ", latencyMs=" + latencyMs +
            ", fromCache=" + fromCache +
            ", contentLength=" + (content != null ? content.length() : 0) +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LLMResponse that = (LLMResponse) o;
        return tokensUsed == that.tokensUsed &&
            latencyMs == that.latencyMs &&
            fromCache == that.fromCache &&
            Objects.equals(content, that.content) &&
            Objects.equals(model, that.model) &&
            Objects.equals(providerId, that.providerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, model, tokensUsed, latencyMs, providerId, fromCache);
    }

        public static class Builder {
        private String content;
        private String model;
        private int tokensUsed;
        private long latencyMs;
        private String providerId;
        private boolean fromCache;

                public Builder content(String content) {
            this.content = content;
            return this;
        }

                public Builder model(String model) {
            this.model = model;
            return this;
        }

                public Builder tokensUsed(int tokensUsed) {
            this.tokensUsed = tokensUsed;
            return this;
        }

                public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

                public Builder providerId(String providerId) {
            this.providerId = providerId;
            return this;
        }

                public Builder fromCache(boolean fromCache) {
            this.fromCache = fromCache;
            return this;
        }

                public LLMResponse build() {
            return new LLMResponse(this);
        }
    }
}
