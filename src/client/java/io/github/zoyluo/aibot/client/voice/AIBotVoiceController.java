package io.github.zoyluo.aibot.client.voice;

import io.github.zoyluo.aibot.client.BotClientState;
import io.github.zoyluo.aibot.client.BotCommandBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

public final class AIBotVoiceController {
    public static final AIBotVoiceController INSTANCE = new AIBotVoiceController();

    private static final int TTS_QUEUE_MAX = 4;
    private static final int TTS_CACHE_MAX_ENTRIES = 32;
    private static final int TTS_CACHE_MAX_TEXT_LEN = 60;

    private final VoiceRecorder recorder = new VoiceRecorder();
    private volatile AIBotVoiceConfig config = AIBotVoiceConfig.load();
    private volatile StepFunVoiceClient client = new StepFunVoiceClient(config);
    private boolean keyWasDown;
    private volatile boolean busy;
    private volatile boolean speaking;

    // TTS 队列:说话期间到达的新句子排队而不是丢弃;满了丢最旧的,保住最新信息
    private final LinkedBlockingDeque<String> ttsQueue = new LinkedBlockingDeque<>();
    private Thread ttsWorker;
    // 短句 WAV 缓存("好的"这类高频句不重复请求 StepFun);LRU,access-order
    private final Map<String, byte[]> ttsCache = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > TTS_CACHE_MAX_ENTRIES;
        }
    };

    private AIBotVoiceController() {
    }

    public void tick(MinecraftClient minecraftClient, boolean keyDown) {
        if (!config.enabled() || config.apiKey().isBlank()) {
            keyWasDown = keyDown;
            return;
        }
        if (minecraftClient.player == null || minecraftClient.world == null || minecraftClient.currentScreen != null) {
            if (recorder.recording()) {
                recorder.stop();
            }
            keyWasDown = keyDown;
            return;
        }
        if (keyDown && !keyWasDown && !busy) {
            if (recorder.start(config.vadRmsThreshold())) {
                status(minecraftClient, "麦克风：正在录音，松开 V 发送");
            } else {
                status(minecraftClient, "麦克风：无法打开，请检查系统权限");
            }
        }
        if (keyDown && recorder.recording() && config.vadAutoSendOn()
                && recorder.hasSpeech(config.vadMinSpeechMs())
                && recorder.silenceMillis() >= config.vadSilenceMs()) {
            byte[] audio = recorder.stop();
            status(minecraftClient, "麦克风：检测到停顿，自动发送");
            submitAsr(audio);
            // keyWasDown 保持 true:松开再按才会开始下一段,避免同一次按住反复触发
        }
        if (!keyDown && keyWasDown && recorder.recording()) {
            byte[] audio = recorder.stop();
            status(minecraftClient, "麦克风：录音结束，正在识别...");
            submitAsr(audio);
        }
        keyWasDown = keyDown;
    }

    public void onBotChat(String botName, String role, String text) {
        if (!config.enabled() || config.apiKey().isBlank()) {
            return;
        }
        if (!"bot".equals(role) || text == null || text.isBlank()) {
            return;
        }
        if (!targetBot().equalsIgnoreCase(botName == null ? "" : botName.trim())) {
            return;
        }
        while (ttsQueue.size() >= TTS_QUEUE_MAX) {
            ttsQueue.pollFirst();
        }
        ttsQueue.offerLast(text);
        ensureTtsWorker();
    }

    /** /aibotvoice reload:重读 aibot_voice.json,不用重启游戏。返回给命令行的反馈文本。 */
    public String reload() {
        config = AIBotVoiceConfig.load();
        client = new StepFunVoiceClient(config);
        synchronized (ttsCache) {
            ttsCache.clear();
        }
        ttsQueue.clear();
        return "语音配置已重载：enabled=" + config.enabled()
                + " target=" + config.targetBot()
                + " voice=" + config.voice()
                + " vad=" + (config.vadAutoSendOn() ? config.vadSilenceMs() + "ms" : "off");
    }

    public String statusLine() {
        return "enabled=" + config.enabled()
                + " target=" + config.targetBot()
                + " speaking=" + speaking
                + " queue=" + ttsQueue.size()
                + " busy=" + busy;
    }

    private synchronized void ensureTtsWorker() {
        if (ttsWorker != null && ttsWorker.isAlive()) {
            return;
        }
        ttsWorker = new Thread(this::ttsLoop, "aibot-voice-tts");
        ttsWorker.setDaemon(true);
        ttsWorker.start();
    }

    private void ttsLoop() {
        while (true) {
            String text;
            try {
                text = ttsQueue.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            speaking = true;
            try {
                status(MinecraftClient.getInstance(), "Bob：正在生成语音...");
                byte[] audio = synthesizeCached(text);
                status(MinecraftClient.getInstance(), "Bob：正在说话");
                StepFunVoiceClient.playBlocking(audio);
            } catch (Exception exception) {
                status(MinecraftClient.getInstance(), "Bob：语音失败 " + shortError(exception));
            } finally {
                speaking = false;
            }
        }
    }

    private byte[] synthesizeCached(String text) throws Exception {
        String key = text.trim();
        boolean cacheable = key.length() <= TTS_CACHE_MAX_TEXT_LEN;
        if (cacheable) {
            synchronized (ttsCache) {
                byte[] hit = ttsCache.get(key);
                if (hit != null) {
                    return hit;
                }
            }
        }
        byte[] audio = client.synthesize(text);
        if (cacheable && audio.length > 0) {
            synchronized (ttsCache) {
                ttsCache.put(key, audio);
            }
        }
        return audio;
    }

    private void submitAsr(byte[] audio) {
        if (audio.length < 3200) {
            BotClientState.INSTANCE.addTranscript("system", "录音太短");
            return;
        }
        busy = true;
        status(MinecraftClient.getInstance(), "语音：正在识别...");
        Thread thread = new Thread(() -> {
            try {
                String text = client.transcribe(audio);
                if (text.isBlank()) {
                    status(MinecraftClient.getInstance(), "语音：没有识别到内容");
                    return;
                }
                status(MinecraftClient.getInstance(), "语音：识别为“" + text + "”");
                MinecraftClient.getInstance().execute(() -> BotCommandBridge.chat(targetBot(), text));
            } catch (Exception exception) {
                status(MinecraftClient.getInstance(), "语音：识别失败 " + shortError(exception));
            } finally {
                busy = false;
            }
        }, "aibot-voice-asr");
        thread.setDaemon(true);
        thread.start();
    }

    private String targetBot() {
        String target = BotClientState.INSTANCE.targetBot();
        return target == null || target.isBlank() ? config.targetBot() : target;
    }

    private static void status(MinecraftClient client, String message) {
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(message), true);
            }
            BotClientState.INSTANCE.addTranscript("system", message);
        });
    }

    private static String shortError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 80 ? message.substring(0, 80) : message;
    }
}
