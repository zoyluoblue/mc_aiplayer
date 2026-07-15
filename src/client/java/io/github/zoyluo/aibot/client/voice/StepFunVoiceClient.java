package io.github.zoyluo.aibot.client.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

public final class StepFunVoiceClient {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final AIBotVoiceConfig config;

    public StepFunVoiceClient(AIBotVoiceConfig config) {
        this.config = config;
    }

    public String transcribe(byte[] pcm16) throws IOException, InterruptedException {
        if (pcm16 == null || pcm16.length < 3200) {
            return "";
        }
        JsonObject transcription = new JsonObject();
        transcription.addProperty("model", config.asrModel());
        transcription.addProperty("language", config.language());
        transcription.addProperty("enable_itn", true);

        JsonObject format = new JsonObject();
        format.addProperty("type", "pcm");
        format.addProperty("codec", "pcm_s16le");
        format.addProperty("rate", 16_000);
        format.addProperty("bits", 16);
        format.addProperty("channel", 1);

        JsonObject input = new JsonObject();
        input.add("transcription", transcription);
        input.add("format", format);

        JsonObject audio = new JsonObject();
        audio.addProperty("data", Base64.getEncoder().encodeToString(pcm16));
        audio.add("input", input);

        JsonObject root = new JsonObject();
        root.add("audio", audio);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.asrUrl()))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("asr_http_" + response.statusCode());
        }
        return extractTranscript(response.body()).trim();
    }

    public byte[] synthesize(String text) throws IOException, InterruptedException {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", config.ttsModel());
        body.addProperty("input", text.trim());
        body.addProperty("voice", config.voice());
        body.addProperty("instruction", config.ttsInstruction());
        body.addProperty("response_format", "wav");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.ttsUrl()))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("tts_http_" + response.statusCode());
        }
        return response.body();
    }

    public static void play(byte[] wavBytes) {
        Thread thread = new Thread(() -> {
            try {
                playBlocking(wavBytes);
            } catch (Exception ignored) {
            }
        }, "aibot-voice-play");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 阻塞到整段 WAV 播完再返回,TTS 队列靠它保证一句说完再说下一句。
     * 不能用 Clip.isRunning() 轮询:start() 异步启动,线程未跑起来时 isRunning()=false,
     * 循环秒退接着 close() 直接掐死播放(实测 2.96s 音频 4ms 就退出,全程无声)。
     * SourceDataLine 的 write 写满缓冲自然阻塞,drain() 等待播完,无竞态。
     * 异常向上抛,由调用方提示"语音失败",不再静默吞掉。
     */
    public static void playBlocking(byte[] wavBytes) throws Exception {
        if (wavBytes == null || wavBytes.length == 0) {
            return;
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes))) {
            AudioFormat base = in.getFormat();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(), 16, base.getChannels(),
                    base.getChannels() * 2, base.getSampleRate(), false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in);
                 SourceDataLine lineOut = (SourceDataLine) AudioSystem.getLine(
                         new DataLine.Info(SourceDataLine.class, target))) {
                lineOut.open(target);
                lineOut.start();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = pcm.read(buffer, 0, buffer.length)) > 0) {
                    lineOut.write(buffer, 0, read);
                }
                lineOut.drain();
            }
        }
    }

    private static String extractTranscript(String sse) {
        String best = "";
        for (String line : sse.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring(5).trim();
            if (payload.isBlank() || "[DONE]".equals(payload)) {
                continue;
            }
            Optional<String> text = textFromJson(payload);
            if (text.isPresent() && !text.get().isBlank()) {
                best = text.get();
            }
        }
        return best;
    }

    private static Optional<String> textFromJson(String payload) {
        try {
            JsonObject object = JsonParser.parseString(payload).getAsJsonObject();
            Optional<String> direct = firstString(object, "text", "transcript", "result", "content");
            if (direct.isPresent()) {
                return direct;
            }
            if (object.has("data") && object.get("data").isJsonObject()) {
                JsonObject data = object.getAsJsonObject("data");
                Optional<String> nested = firstString(data, "text", "transcript", "result", "content");
                if (nested.isPresent()) {
                    return nested;
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static Optional<String> firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                return Optional.of(object.get(key).getAsString());
            }
        }
        return Optional.empty();
    }
}
