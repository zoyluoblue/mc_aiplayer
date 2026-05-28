package io.github.zoyluo.aibot.brain;

public class DeepSeekApiException extends Exception {
    public DeepSeekApiException(String message) {
        super(message);
    }

    public DeepSeekApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
