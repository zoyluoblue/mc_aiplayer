package io.github.zoyluo.aibot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the V4 thinking contract. Reasoning output shares the {@code max_tokens} budget, so an
 * unset or silently-changed effort level directly costs the bot tool calls.
 */
class DeepSeekThinkingConfigTest {
    private static AIBotConfig.DeepSeek deepSeek(Boolean thinking, String effort) {
        return new AIBotConfig.DeepSeek(
                "key", "https://api.deepseek.com", "deepseek-v4-flash",
                8192, 0.3D, 60, 3, 500, thinking, effort);
    }

    @Test
    void defaultsTargetTheCurrentFlashModelWithABudgetReasoningCanNotStarve() {
        AIBotConfig.DeepSeek defaults = AIBotConfig.defaults().deepseek();

        assertEquals("deepseek-v4-flash", defaults.model());
        assertEquals(Boolean.TRUE, defaults.thinking());
        assertEquals("low", defaults.reasoningEffort());
        assertTrue(defaults.maxTokens() >= 8192,
                "reasoning shares max_tokens; 2048 truncates tool calls");
    }

    @Test
    void unsetOverridesFallBackToDefaultsInsteadOfSilentlyDisablingThinking() {
        AIBotConfig.DeepSeek defaults = deepSeek(Boolean.TRUE, "low");
        AIBotConfig.DeepSeek merged = deepSeek(null, null).withDefaults(defaults);

        assertEquals(Boolean.TRUE, merged.thinking());
        assertEquals("low", merged.reasoningEffort());
    }

    @Test
    void explicitOverridesWin() {
        AIBotConfig.DeepSeek defaults = deepSeek(Boolean.TRUE, "low");

        assertEquals(Boolean.FALSE, deepSeek(Boolean.FALSE, "low")
                .withDefaults(defaults).thinking());
        assertEquals("max", deepSeek(Boolean.TRUE, "max")
                .withDefaults(defaults).reasoningEffort());
    }

    @Test
    void unsupportedEffortFallsBackRatherThanReachingTheApi() {
        AIBotConfig.DeepSeek defaults = deepSeek(Boolean.TRUE, "low");

        assertEquals("low", deepSeek(Boolean.TRUE, "medium")
                .withDefaults(defaults).reasoningEffort());
        assertEquals("low", deepSeek(Boolean.TRUE, "")
                .withDefaults(defaults).reasoningEffort());
    }

    @Test
    void apiKeyRebindKeepsTheThinkingContract() {
        AIBotConfig config = AIBotConfig.defaults();
        AIBotConfig.DeepSeek rebound = config.deepseek().withDefaults(config.deepseek());

        assertNotNull(rebound.reasoningEffort());
        assertEquals(config.deepseek().thinking(), rebound.thinking());
        assertEquals(config.deepseek().reasoningEffort(), rebound.reasoningEffort());
    }
}
