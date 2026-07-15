package io.github.zoyluo.aibot.client.voice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public record AIBotVoiceConfig(
        boolean enabled,
        String apiKey,
        String targetBot,
        String asrUrl,
        String ttsUrl,
        String asrModel,
        String ttsModel,
        String language,
        String voice,
        String ttsInstruction,
        Boolean vadAutoSend,      // 按住 V 期间检测到停顿自动发送;旧配置缺字段(null)按 true 处理
        int vadSilenceMs,         // 判定"说完了"的静音时长;0 = 用默认 1200
        int vadMinSpeechMs,       // 至少累积多少毫秒的人声才允许自动发送;0 = 用默认 400
        int vadRmsThreshold       // 16-bit PCM 的 RMS 人声门限;0 = 用默认 300
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static AIBotVoiceConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("aibot_voice.json");
        AIBotVoiceConfig defaults = defaults();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                AIBotVoiceConfig parsed = GSON.fromJson(reader, AIBotVoiceConfig.class);
                return parsed == null ? defaults : parsed.withDefaults(defaults);
            } catch (IOException ignored) {
                return defaults;
            }
        }
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(defaults, writer);
            }
        } catch (IOException ignored) {
        }
        return defaults;
    }

    private static AIBotVoiceConfig defaults() {
        return new AIBotVoiceConfig(
                false,
                "",
                "Bob",
                "https://api.stepfun.com/step_plan/v1/audio/asr/sse",
                "https://api.stepfun.com/step_plan/v1/audio/speech",
                "stepaudio-2.5-asr",
                "stepaudio-2.5-tts",
                "zh",
                "cixingnansheng",
                "像 Minecraft 队友一样自然、简短、有精神地说话",
                Boolean.TRUE,
                1200,
                400,
                300);
    }

    private AIBotVoiceConfig withDefaults(AIBotVoiceConfig defaults) {
        return new AIBotVoiceConfig(
                enabled,
                apiKey == null ? defaults.apiKey : apiKey,
                blankToDefault(targetBot, defaults.targetBot),
                blankToDefault(asrUrl, defaults.asrUrl),
                blankToDefault(ttsUrl, defaults.ttsUrl),
                blankToDefault(asrModel, defaults.asrModel),
                blankToDefault(ttsModel, defaults.ttsModel),
                blankToDefault(language, defaults.language),
                blankToDefault(voice, defaults.voice),
                blankToDefault(ttsInstruction, defaults.ttsInstruction),
                vadAutoSend == null ? defaults.vadAutoSend : vadAutoSend,
                vadSilenceMs <= 0 ? defaults.vadSilenceMs : vadSilenceMs,
                vadMinSpeechMs <= 0 ? defaults.vadMinSpeechMs : vadMinSpeechMs,
                vadRmsThreshold <= 0 ? defaults.vadRmsThreshold : vadRmsThreshold);
    }

    /** VAD 自动发送是否开启(容忍旧配置文件缺字段)。 */
    public boolean vadAutoSendOn() {
        return vadAutoSend == null || vadAutoSend;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
