package com.aiplayer.llm.resilience;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.llm.async.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;

public class LLMFallbackHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMFallbackHandler.class);
    private static final Map<Pattern, String> PATTERN_RESPONSES = Map.of(
        Pattern.compile("(?i).*(mine|dig|collect|gather|ore|diamond|iron|coal|stone).*"),
        "{\"thoughts\":\"[Fallback] Mining action detected\",\"tasks\":[{\"action\":\"mine\",\"target\":\"iron_ore\",\"quantity\":10}]}",
        Pattern.compile("(?i).*(build|construct|create|make).*(house|home|shelter|structure|base).*"),
        "{\"thoughts\":\"[Fallback] Building action detected\",\"tasks\":[{\"action\":\"build\",\"structure\":\"house\",\"size\":\"small\"}]}",
        Pattern.compile("(?i).*(attack|fight|kill|destroy|hostile|monster|zombie|skeleton|creeper).*"),
        "{\"thoughts\":\"[Fallback] Combat action detected\",\"tasks\":[{\"action\":\"attack\",\"target\":\"nearest_hostile\"}]}",
        Pattern.compile("(?i).*(follow|come|here|with me|accompany).*"),
        "{\"thoughts\":\"[Fallback] Follow action detected\",\"tasks\":[{\"action\":\"follow\",\"target\":\"player\"}]}",
        Pattern.compile("(?i).*(go to|move to|walk to|travel|path|navigate).*"),
        "{\"thoughts\":\"[Fallback] Movement action detected\",\"tasks\":[{\"action\":\"pathfind\",\"target\":\"player\"}]}",
        Pattern.compile("(?i).*(place|put|set).*(block|torch|door).*"),
        "{\"thoughts\":\"[Fallback] Placement action detected\",\"tasks\":[{\"action\":\"place_block\",\"block\":\"torch\",\"position\":\"here\"}]}",
        Pattern.compile("(?i).*(stop|halt|cancel|wait|pause|stay).*"),
        "{\"thoughts\":\"[Fallback] Stop action detected\",\"tasks\":[{\"action\":\"wait\",\"duration\":5}]}"
    );
    private static final String DEFAULT_RESPONSE =
        "{\"thoughts\":\"[Fallback] No pattern matched, waiting\",\"tasks\":[{\"action\":\"wait\",\"duration\":5}]}";

        public LLMResponse generateFallback(String prompt, Throwable error) {
        LOGGER.warn("Generating fallback response for prompt: '{}' (error: {})",
            truncatePrompt(prompt, 50),
            error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : "unknown");
        String responseContent = matchPattern(prompt);
        String matchedPattern = responseContent.equals(DEFAULT_RESPONSE) ? "default" : "pattern-match";

        AiPlayerMod.info("llm", "Fallback response generated (matched: {})", matchedPattern);

        return LLMResponse.builder()
            .content(responseContent)
            .model("fallback-pattern-matcher")
            .providerId("fallback")
            .latencyMs(0)
            .tokensUsed(0)
            .fromCache(false)
            .build();
    }

        private String matchPattern(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return DEFAULT_RESPONSE;
        }

        String lowerPrompt = prompt.toLowerCase();

        for (Map.Entry<Pattern, String> entry : PATTERN_RESPONSES.entrySet()) {
            if (entry.getKey().matcher(lowerPrompt).matches()) {
                LOGGER.debug("Matched pattern: {}", entry.getKey().pattern());
                return entry.getValue();
            }
        }

        LOGGER.debug("No pattern matched, using default response");
        return DEFAULT_RESPONSE;
    }

        private String truncatePrompt(String prompt, int maxLength) {
        if (prompt == null) {
            return "[null]";
        }
        if (prompt.length() <= maxLength) {
            return prompt;
        }
        return prompt.substring(0, maxLength) + "...";
    }

        public boolean wouldMatchPattern(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return false;
        }

        String lowerPrompt = prompt.toLowerCase();
        return PATTERN_RESPONSES.keySet().stream()
            .anyMatch(pattern -> pattern.matcher(lowerPrompt).matches());
    }

        public int getPatternCount() {
        return PATTERN_RESPONSES.size();
    }
}
