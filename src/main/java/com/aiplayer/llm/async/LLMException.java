package com.aiplayer.llm.async;

public class LLMException extends RuntimeException {

        public enum ErrorType {
                RATE_LIMIT(true),

                TIMEOUT(true),

                CIRCUIT_OPEN(false),

                INVALID_RESPONSE(false),

                NETWORK_ERROR(true),

                AUTH_ERROR(false),

                SERVER_ERROR(true),

                CLIENT_ERROR(false);

        private final boolean retryable;

        ErrorType(boolean retryable) {
            this.retryable = retryable;
        }

                public boolean isRetryable() {
            return retryable;
        }
    }

    private final ErrorType errorType;
    private final String providerId;
    private final boolean retryable;

        public LLMException(String message, ErrorType errorType, String providerId, boolean retryable) {
        super(message);
        this.errorType = errorType;
        this.providerId = providerId;
        this.retryable = retryable;
    }

        public LLMException(String message, ErrorType errorType, String providerId, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.providerId = providerId;
        this.retryable = retryable;
    }

        public ErrorType getErrorType() {
        return errorType;
    }

        public String getProviderId() {
        return providerId;
    }

        public boolean isRetryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return "LLMException{" +
            "errorType=" + errorType +
            ", providerId='" + providerId + '\'' +
            ", retryable=" + retryable +
            ", message='" + getMessage() + '\'' +
            '}';
    }
}
