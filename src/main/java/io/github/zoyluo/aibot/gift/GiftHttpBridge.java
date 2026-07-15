package io.github.zoyluo.aibot.gift;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.overlay.OverlayService;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class GiftHttpBridge {
    public static final GiftHttpBridge INSTANCE = new GiftHttpBridge();

    private HttpServer server;
    private volatile MinecraftServer minecraftServer;

    private GiftHttpBridge() {
    }

    public synchronized void start(MinecraftServer minecraftServer) {
        GiftBridgeConfig config = GiftDispatcher.INSTANCE.config();
        if (!config.enabled()) {
            BotLog.config("gift_bridge_disabled");
            return;
        }
        stop();
        this.minecraftServer = minecraftServer;
        try {
            server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
            server.createContext("/gift", this::handleGift);
            server.createContext("/danmaku", this::handleDanmaku);
            server.createContext("/health", exchange -> write(exchange, 200, "{\"ok\":true}"));
            // 管理面板:浏览器改礼物→动作/DeepSeek 指令映射,保存即热加载。GET/POST /config 受 token 保护(若配了)。
            // 安全前提 = host 保持 127.0.0.1;改 0.0.0.0 等于把改配置的权力开放给局域网。
            String adminHtml = loadResource("/assets/aibot/admin.html");
            server.createContext("/admin", exchange -> {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    write(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                    return;
                }
                writeHtml(exchange, adminHtml);
            });
            server.createContext("/config", this::handleConfig);
            if (config.overlayEnabled()) {
                // /status /overlay 不校验 token:OBS 浏览器源带不了 header。
                String overlayHtml = loadResource("/assets/aibot/overlay.html");
                server.createContext("/status", exchange -> {
                    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        write(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                        return;
                    }
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    write(exchange, 200, OverlayService.INSTANCE.statusJson());
                });
                server.createContext("/overlay", exchange -> {
                    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        write(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
                        return;
                    }
                    writeHtml(exchange, overlayHtml);
                });
            }
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "aibot-gift-bridge");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            BotLog.config("gift_bridge_started", "host", config.host(), "port", config.port());
        } catch (IOException exception) {
            BotLog.error("gift_bridge_start_failed", exception);
            server = null;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            BotLog.config("gift_bridge_stopped");
        }
        minecraftServer = null;
    }

    private void handleGift(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        if (!authorized(exchange)) {
            write(exchange, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        String body = readBody(exchange.getRequestBody());
        JsonObject json;
        try {
            json = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception exception) {
            write(exchange, 400, "{\"ok\":false,\"error\":\"bad_json\"}");
            return;
        }
        String user = text(json, "user");
        String gift = text(json, "gift");
        String bot = text(json, "bot");
        int count = json.has("count") ? Math.max(1, json.get("count").getAsInt()) : 1;
        if (gift.isBlank()) {
            write(exchange, 400, "{\"ok\":false,\"error\":\"gift_required\"}");
            return;
        }
        MinecraftServer mc = minecraftServer;
        if (mc == null) {
            write(exchange, 503, "{\"ok\":false,\"error\":\"server_not_ready\"}");
            return;
        }
        GiftDispatcher.GiftEvent event = new GiftDispatcher.GiftEvent(user, gift, count, bot);
        var rejected = GiftDispatcher.INSTANCE.admissionCheck(event);
        if (rejected.isPresent()) {
            BotLog.config("gift_rejected", "reason", rejected.get(), "gift", gift, "user", user);
            write(exchange, 429, "{\"ok\":false,\"error\":\"" + rejected.get() + "\"}");
            return;
        }
        mc.execute(() -> {
            try {
                GiftDispatcher.INSTANCE.handle(mc, event);
            } catch (Exception exception) {
                BotLog.error("gift_handle_failed", exception, "gift", gift, "user", user);
            }
        });
        write(exchange, 200, "{\"ok\":true,\"queued\":true,\"gift\":\"" + escape(gift)
                + "\",\"user\":\"" + escape(user)
                + "\",\"count\":" + count + "}");
    }

    /**
     * 弹幕/关注事件入口(watcher 每 ~2s 批量 POST):{"items":[{"kind":"chat|follow","user":"..","text":".."},…]}。
     * 无 429 语义(弹幕不需要拒绝原因),消毒与缓冲都在 DanmakuService 主线程侧;单请求 cap 20 条防灌爆。
     */
    private void handleDanmaku(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        if (!authorized(exchange)) {
            write(exchange, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        String body = readBody(exchange.getRequestBody());
        JsonObject json;
        try {
            json = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception exception) {
            write(exchange, 400, "{\"ok\":false,\"error\":\"bad_json\"}");
            return;
        }
        MinecraftServer mc = minecraftServer;
        if (mc == null) {
            write(exchange, 503, "{\"ok\":false,\"error\":\"server_not_ready\"}");
            return;
        }
        int accepted = 0;
        if (json.has("items") && json.get("items").isJsonArray()) {
            for (var element : json.get("items").getAsJsonArray()) {
                if (accepted >= 20 || !element.isJsonObject()) {
                    break;
                }
                JsonObject item = element.getAsJsonObject();
                String kind = text(item, "kind");
                String user = text(item, "user");
                String content = text(item, "text");
                if (kind.isBlank() || user.isBlank()) {
                    continue;
                }
                accepted++;
                mc.execute(() -> DanmakuService.INSTANCE.accept(mc, kind, user, content));
            }
        }
        write(exchange, 200, "{\"ok\":true,\"accepted\":" + accepted + "}");
    }

    /** 管理面板配置读写:GET 返回磁盘原文,POST 校验+原子写盘后热加载(reload 只换 volatile config,任意线程安全)。 */
    private void handleConfig(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            write(exchange, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                write(exchange, 200, GiftBridgeConfig.rawText());
            } catch (IOException exception) {
                BotLog.error("gift_config_read_failed", exception);
                write(exchange, 500, "{\"ok\":false,\"error\":\"read_failed\"}");
            }
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            write(exchange, 405, "{\"ok\":false,\"error\":\"method_not_allowed\"}");
            return;
        }
        String body = readBody(exchange.getRequestBody());
        try {
            GiftBridgeConfig.saveRaw(body);
        } catch (IllegalArgumentException exception) {
            write(exchange, 400, "{\"ok\":false,\"error\":\"" + escape(exception.getMessage()) + "\"}");
            return;
        } catch (IOException exception) {
            BotLog.error("gift_config_save_failed", exception);
            write(exchange, 500, "{\"ok\":false,\"error\":\"save_failed\"}");
            return;
        }
        GiftDispatcher.INSTANCE.reload();
        int gifts = GiftDispatcher.INSTANCE.config().gifts().size();
        BotLog.config("gift_config_saved_via_admin", "gifts", gifts);
        write(exchange, 200, "{\"ok\":true,\"gifts\":" + gifts
                + ",\"note\":\"enabled/host/port/overlayEnabled 改动需重启服务端,其余已热生效\"}");
    }

    /** token 校验:配置里 token 为空 = 不校验(本机回环)。/gift 与 /config 共用。 */
    private static boolean authorized(HttpExchange exchange) {
        GiftBridgeConfig config = GiftDispatcher.INSTANCE.config();
        if (config.token().isBlank()) {
            return true;
        }
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        return auth != null && auth.equals("Bearer " + config.token());
    }

    /** 启动时把页面从 mod 资源读进内存,HTTP 线程直接吐,不碰磁盘。 */
    private static String loadResource(String path) {
        try (InputStream input = GiftHttpBridge.class.getResourceAsStream(path)) {
            if (input == null) {
                return "<!DOCTYPE html><html><body>" + path + " missing from mod resources</body></html>";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            BotLog.error("bridge_resource_load_failed", exception, "path", path);
            return "<!DOCTYPE html><html><body>" + path + " load failed</body></html>";
        }
    }

    private static void writeHtml(HttpExchange exchange, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String readBody(InputStream input) throws IOException {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString()
                : "";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
