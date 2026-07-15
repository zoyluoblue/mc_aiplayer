package io.github.zoyluo.aibot.gift;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GiftBridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String token;
    private final String defaultBot;
    private final long cooldownMs;
    private final long dedupMs;
    private final boolean broadcastThanks;
    private final String thanksTemplate;
    private final int countScaleCap;
    private final int[] tierUpAt;
    private final boolean hourlyThanks;
    private final boolean overlayEnabled;
    // —— 直播互动扩展(弹幕/关注/空闲),全部热加载 ——
    private final boolean danmakuEnabled;
    private final int danmakuMinIntervalSec;
    private final int danmakuBatchMax;
    private final int danmakuNamedCooldownSec;
    private final boolean followThanksEnabled;
    private final String followThanksTemplate;
    private final boolean idleEnabled;
    private final int idleAfterSec;
    private final int idleChatterIntervalSec;
    private final Map<String, GiftRule> gifts;
    private final Map<String, List<GiftAction>> pools;

    public GiftBridgeConfig(
            boolean enabled,
            String host,
            int port,
            String token,
            String defaultBot,
            long cooldownMs,
            long dedupMs,
            boolean broadcastThanks,
            String thanksTemplate,
            int countScaleCap,
            int[] tierUpAt,
            boolean hourlyThanks,
            boolean overlayEnabled,
            boolean danmakuEnabled,
            int danmakuMinIntervalSec,
            int danmakuBatchMax,
            int danmakuNamedCooldownSec,
            boolean followThanksEnabled,
            String followThanksTemplate,
            boolean idleEnabled,
            int idleAfterSec,
            int idleChatterIntervalSec,
            Map<String, GiftRule> gifts,
            Map<String, List<GiftAction>> pools) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.token = token;
        this.defaultBot = defaultBot;
        this.cooldownMs = cooldownMs;
        this.dedupMs = dedupMs;
        this.broadcastThanks = broadcastThanks;
        this.thanksTemplate = thanksTemplate;
        this.countScaleCap = countScaleCap;
        this.tierUpAt = tierUpAt;
        this.hourlyThanks = hourlyThanks;
        this.overlayEnabled = overlayEnabled;
        this.danmakuEnabled = danmakuEnabled;
        this.danmakuMinIntervalSec = danmakuMinIntervalSec;
        this.danmakuBatchMax = danmakuBatchMax;
        this.danmakuNamedCooldownSec = danmakuNamedCooldownSec;
        this.followThanksEnabled = followThanksEnabled;
        this.followThanksTemplate = followThanksTemplate;
        this.idleEnabled = idleEnabled;
        this.idleAfterSec = idleAfterSec;
        this.idleChatterIntervalSec = idleChatterIntervalSec;
        this.gifts = gifts;
        this.pools = pools;
    }

    public boolean enabled() {
        return enabled;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    public String defaultBot() {
        return defaultBot;
    }

    /** 同名礼物两次执行的最小间隔;0 = 不限频。冷却是软门控:只压任务型动作,感谢/庆祝照给。 */
    public long cooldownMs() {
        return cooldownMs;
    }

    /** 同一 (user, gift) 视为重放的窗口;0 = 不去重。须小于 watcher 的连击聚合静默窗(默认2s),否则误杀合法批次。 */
    public long dedupMs() {
        return dedupMs;
    }

    /** 收礼时是否向全服公屏广播感谢(观众在 OBS 画面里看得到,面板/TTS 通道不受影响)。 */
    public boolean broadcastThanks() {
        return broadcastThanks;
    }

    public String thanksTemplate() {
        return thanksTemplate;
    }

    /** gather/mine/goal 数量乘 count 之后的上限,防大额连击把配额打到离谱。 */
    public int countScaleCap() {
        return countScaleCap;
    }

    /** 连击提档阈值:count≥[0] 提 1 档,count≥[1] 提 2 档(档位封顶 4)。 */
    public int[] tierUpAt() {
        return tierUpAt;
    }

    /** 是否整点公屏+语音感谢今日送礼榜。 */
    public boolean hourlyThanks() {
        return hourlyThanks;
    }

    /** 是否开放 GET /status 与 /overlay(OBS 浏览器源数据出口)。 */
    public boolean overlayEnabled() {
        return overlayEnabled;
    }

    /** 弹幕互动总开关:关=收到的 chat/点名弹幕直接丢弃(/danmaku 端点仍在,关注感谢独立开关)。 */
    public boolean danmakuEnabled() {
        return danmakuEnabled;
    }

    /** 空闲合批回弹幕的最小间隔(秒)。 */
    public int danmakuMinIntervalSec() {
        return danmakuMinIntervalSec;
    }

    /** 每批合批弹幕的最多条数。 */
    public int danmakuBatchMax() {
        return danmakuBatchMax;
    }

    /** 同一观众两次点名回复的最小间隔(秒),冷却内的点名直接丢弃保鲜。 */
    public int danmakuNamedCooldownSec() {
        return danmakuNamedCooldownSec;
    }

    /** 关注感谢(TTS 念名字,同一观众一场只谢一次)。 */
    public boolean followThanksEnabled() {
        return followThanksEnabled;
    }

    /** 关注感谢模板,{user}=合并后的"A、B、C"。 */
    public String followThanksTemplate() {
        return followThanksTemplate;
    }

    /** 空闲自主找事(确定性抽 pools["idle"])。 */
    public boolean idleEnabled() {
        return idleEnabled;
    }

    /** 连续完全空闲多少秒后开始找事(也是两次抽池的间隔)。 */
    public int idleAfterSec() {
        return idleAfterSec;
    }

    /** 空闲大脑唠嗑间隔(秒),0=关(纯确定性,零 token)。 */
    public int idleChatterIntervalSec() {
        return idleChatterIntervalSec;
    }

    public Map<String, GiftRule> gifts() {
        return gifts;
    }

    public Map<String, List<GiftAction>> pools() {
        return pools;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("aibot_gifts.json");
    }

    /** 管理面板 GET /config:返回磁盘原文;文件缺失时先落默认模板再读。 */
    public static String rawText() throws IOException {
        Path path = path();
        if (!Files.exists(path)) {
            writeTemplate(path, defaults());
        }
        return Files.readString(path);
    }

    /**
     * 管理面板 POST /config:校验后原子写盘,调用方随后 GiftDispatcher.reload() 热加载。
     * 校验宽松(解析层本就带默认值兜底),只拦两类致命错:不是合法 JSON 对象、gifts 存在但一条都解析不出
     * (那样 load() 会静默回落内置默认表,面板上看起来"保存了但没生效")。
     */
    public static void saveRaw(String json) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception exception) {
            throw new IllegalArgumentException("bad_json");
        }
        if (root.has("gifts") && parseGifts(root.get("gifts")).isEmpty()) {
            throw new IllegalArgumentException("gifts_empty_or_invalid");
        }
        Path path = path();
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.createDirectories(path.getParent());
        Files.writeString(temp, GSON.toJson(root));
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static GiftBridgeConfig load() {
        Path path = path();
        GiftBridgeConfig defaults = defaults();
        if (!Files.exists(path)) {
            writeTemplate(path, defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            boolean enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
            String host = text(root, "host", defaults.host());
            int port = root.has("port") ? root.get("port").getAsInt() : defaults.port();
            String token = text(root, "token", "");
            String defaultBot = text(root, "defaultBot", defaults.defaultBot());
            long cooldownMs = root.has("cooldownMs") ? Math.max(0L, root.get("cooldownMs").getAsLong()) : defaults.cooldownMs();
            long dedupMs = root.has("dedupMs") ? Math.max(0L, root.get("dedupMs").getAsLong()) : defaults.dedupMs();
            boolean broadcastThanks = bool(root, "broadcastThanks", defaults.broadcastThanks());
            String thanksTemplate = text(root, "thanksTemplate", defaults.thanksTemplate());
            int countScaleCap = root.has("countScaleCap") ? Math.max(1, root.get("countScaleCap").getAsInt()) : defaults.countScaleCap();
            int[] tierUpAt = parseTierUpAt(root.get("tierUpAt"), defaults.tierUpAt());
            boolean hourlyThanks = bool(root, "hourlyThanks", defaults.hourlyThanks());
            boolean overlayEnabled = bool(root, "overlayEnabled", defaults.overlayEnabled());
            boolean danmakuEnabled = bool(root, "danmakuEnabled", defaults.danmakuEnabled());
            int danmakuMinIntervalSec = intVal(root, "danmakuMinIntervalSec", defaults.danmakuMinIntervalSec());
            int danmakuBatchMax = intVal(root, "danmakuBatchMax", defaults.danmakuBatchMax());
            int danmakuNamedCooldownSec = intVal(root, "danmakuNamedCooldownSec", defaults.danmakuNamedCooldownSec());
            boolean followThanksEnabled = bool(root, "followThanksEnabled", defaults.followThanksEnabled());
            String followThanksTemplate = text(root, "followThanksTemplate", defaults.followThanksTemplate());
            boolean idleEnabled = bool(root, "idleEnabled", defaults.idleEnabled());
            int idleAfterSec = intVal(root, "idleAfterSec", defaults.idleAfterSec());
            int idleChatterIntervalSec = root.has("idleChatterIntervalSec")
                    ? Math.max(0, root.get("idleChatterIntervalSec").getAsInt())
                    : defaults.idleChatterIntervalSec(); // 0 合法=关唠嗑,不能走 positive 兜底
            Map<String, GiftRule> gifts = parseGifts(root.get("gifts"));
            Map<String, List<GiftAction>> pools = parseActionsMap(root.get("pools"));
            if (gifts.isEmpty()) {
                gifts = defaults.gifts();
            }
            if (pools.isEmpty()) {
                pools = defaults.pools();
            }
            if (!pools.containsKey("idle")) {
                // 老配置无 idle 池:补内置默认(否则空闲找事永远抽不到东西,像没生效)。
                Map<String, List<GiftAction>> patched = new LinkedHashMap<>(pools);
                patched.put("idle", defaults.pools().get("idle"));
                pools = patched;
            }
            return new GiftBridgeConfig(enabled, host, port, token, defaultBot, cooldownMs, dedupMs,
                    broadcastThanks, thanksTemplate, countScaleCap, tierUpAt, hourlyThanks, overlayEnabled,
                    danmakuEnabled, danmakuMinIntervalSec, danmakuBatchMax, danmakuNamedCooldownSec,
                    followThanksEnabled, followThanksTemplate, idleEnabled, idleAfterSec, idleChatterIntervalSec,
                    gifts, pools);
        } catch (Exception exception) {
            return defaults;
        }
    }

    private static void writeTemplate(Path path, GiftBridgeConfig config) {
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", config.enabled());
            root.addProperty("host", config.host());
            root.addProperty("port", config.port());
            root.addProperty("token", config.token());
            root.addProperty("defaultBot", config.defaultBot());
            root.addProperty("cooldownMs", config.cooldownMs());
            root.addProperty("dedupMs", config.dedupMs());
            root.addProperty("broadcastThanks", config.broadcastThanks());
            root.addProperty("thanksTemplate", config.thanksTemplate());
            root.addProperty("countScaleCap", config.countScaleCap());
            JsonArray tierUp = new JsonArray();
            for (int threshold : config.tierUpAt()) {
                tierUp.add(threshold);
            }
            root.add("tierUpAt", tierUp);
            root.addProperty("hourlyThanks", config.hourlyThanks());
            root.addProperty("overlayEnabled", config.overlayEnabled());
            root.addProperty("danmakuEnabled", config.danmakuEnabled());
            root.addProperty("danmakuMinIntervalSec", config.danmakuMinIntervalSec());
            root.addProperty("danmakuBatchMax", config.danmakuBatchMax());
            root.addProperty("danmakuNamedCooldownSec", config.danmakuNamedCooldownSec());
            root.addProperty("followThanksEnabled", config.followThanksEnabled());
            root.addProperty("followThanksTemplate", config.followThanksTemplate());
            root.addProperty("idleEnabled", config.idleEnabled());
            root.addProperty("idleAfterSec", config.idleAfterSec());
            root.addProperty("idleChatterIntervalSec", config.idleChatterIntervalSec());
            root.add("gifts", giftsToJson(config.gifts()));
            root.add("pools", actionsMapToJson(config.pools()));
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ignored) {
        }
    }

    private static GiftBridgeConfig defaults() {
        // 档位对应烟花庆祝规格:1=仅粒子 2=单发 3=三连发 4=环绕齐射+转圈致谢。
        Map<String, GiftRule> gifts = new LinkedHashMap<>();
        gifts.put("小心心", new GiftRule(1, List.of(
                GiftAction.say("谢谢 {user} 的小心心！"),
                GiftAction.of("come_here"))));
        gifts.put("玫瑰", new GiftRule(2, List.of(
                GiftAction.say("感谢 {user} 送的玫瑰，我去砍点木头！"),
                GiftAction.gather("minecraft:oak_log", 4))));
        gifts.put("抖音", new GiftRule(2, List.of(
                GiftAction.say("收到 {user} 的抖音礼物，我去做一个工作台！"),
                GiftAction.goal("minecraft:crafting_table"))));
        gifts.put("跑车", new GiftRule(3, List.of(
                GiftAction.say("哇，{user} 送了跑车！我去盖个小房子！"),
                GiftAction.of("build_house"))));
        gifts.put("嘉年华", new GiftRule(4, List.of(
                GiftAction.say("超级感谢 {user} 的嘉年华！我随机挑战一个任务！"),
                GiftAction.random("big"))));
        gifts.put("粉丝灯牌", new GiftRule(2, List.of(
                GiftAction.say("收到 {user} 的灯牌，我会一直跟着你！"),
                GiftAction.of("follow"))));

        Map<String, List<GiftAction>> pools = new LinkedHashMap<>();
        pools.put("big", List.of(
                GiftAction.goal("minecraft:crafting_table"),
                GiftAction.gather("minecraft:oak_log", 8),
                GiftAction.mine("minecraft:stone", 16),
                GiftAction.of("build_house"),
                GiftAction.goal("minecraft:iron_pickaxe")));
        // 空闲池:IdleScheduler 抽取(白名单 say/gather/mine/wander/fish/come_here;夜间只留 say)。
        // 3 条 say 是零 token 的嘴上互动,保证夜间过滤后池子不空。
        pools.put("idle", List.of(
                GiftAction.say("家人们弹幕聊起来,想看我干什么,送个小心心点播！"),
                GiftAction.say("我先在附近转转,看看有什么好东西。"),
                GiftAction.say("礼物能点播我干活:玫瑰砍树,跑车盖房,嘉年华随机整活！"),
                GiftAction.of("wander"),
                GiftAction.gather("minecraft:oak_log", 4),
                GiftAction.mine("minecraft:stone", 8),
                new GiftAction("fish", "", "", "", 2, "")));
        return new GiftBridgeConfig(true, "127.0.0.1", 8787, "", "Bob", 5_000L, 1_500L,
                true, "感谢 {user} 送出 {gift} ×{count}！", 64, new int[]{10, 30}, true, true,
                true, 30, 5, 60,
                true, "谢谢 {user} 的关注！",
                true, 60, 300,
                gifts, pools);
    }

    /**
     * gifts 兼容两种形态:
     *  - 旧(JsonArray): "玫瑰": [ {action}, ... ] → tier 默认 1
     *  - 新(JsonObject): "跑车": { "tier": 3, "actions": [ {action}, ... ] }
     */
    private static Map<String, GiftRule> parseGifts(JsonElement element) {
        Map<String, GiftRule> map = new LinkedHashMap<>();
        if (element == null || !element.isJsonObject()) {
            return map;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            int tier = 1;
            JsonElement actionsElement = value;
            if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                tier = object.has("tier") ? clampTier(object.get("tier").getAsInt()) : 1;
                actionsElement = object.get("actions");
            }
            List<GiftAction> actions = parseActions(actionsElement);
            if (!actions.isEmpty()) {
                map.put(entry.getKey(), new GiftRule(tier, actions));
            }
        }
        return map;
    }

    private static Map<String, List<GiftAction>> parseActionsMap(JsonElement element) {
        Map<String, List<GiftAction>> map = new LinkedHashMap<>();
        if (element == null || !element.isJsonObject()) {
            return map;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            List<GiftAction> actions = parseActions(entry.getValue());
            if (!actions.isEmpty()) {
                map.put(entry.getKey(), actions);
            }
        }
        return map;
    }

    private static List<GiftAction> parseActions(JsonElement element) {
        List<GiftAction> actions = new ArrayList<>();
        if (element == null || !element.isJsonArray()) {
            return actions;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            GiftAction action = GiftAction.fromJson(item);
            if (action != null) {
                actions.add(action);
            }
        }
        return List.copyOf(actions);
    }

    private static int[] parseTierUpAt(JsonElement element, int[] fallback) {
        if (element == null || !element.isJsonArray()) {
            return fallback;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() < 2) {
            return fallback;
        }
        try {
            int first = Math.max(2, array.get(0).getAsInt());
            int second = Math.max(first + 1, array.get(1).getAsInt());
            return new int[]{first, second};
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static int clampTier(int tier) {
        return Math.max(1, Math.min(4, tier));
    }

    private static JsonObject giftsToJson(Map<String, GiftRule> map) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, GiftRule> entry : map.entrySet()) {
            JsonObject rule = new JsonObject();
            rule.addProperty("tier", entry.getValue().tier());
            JsonArray array = new JsonArray();
            for (GiftAction action : entry.getValue().actions()) {
                array.add(action.toJson());
            }
            rule.add("actions", array);
            object.add(entry.getKey(), rule);
        }
        return object;
    }

    private static JsonObject actionsMapToJson(Map<String, List<GiftAction>> map) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, List<GiftAction>> entry : map.entrySet()) {
            JsonArray array = new JsonArray();
            for (GiftAction action : entry.getValue()) {
                array.add(action.toJson());
            }
            object.add(entry.getKey(), array);
        }
        return object;
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString()
                : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsBoolean()
                : fallback;
    }

    private static int intVal(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? Math.max(1, object.get(key).getAsInt()) : fallback;
        } catch (Exception exception) {
            return fallback;
        }
    }

    /** 一个礼物的完整规则:价值档位(1..4,决定庆祝规格) + 动作序列。 */
    public record GiftRule(int tier, List<GiftAction> actions) {
    }

    public record GiftAction(String type, String text, String item, String block, int count, String pool) {
        public static GiftAction of(String type) {
            return new GiftAction(type, "", "", "", 1, "");
        }

        public static GiftAction say(String text) {
            return new GiftAction("say", text, "", "", 1, "");
        }

        public static GiftAction gather(String item, int count) {
            return new GiftAction("gather", "", item, "", count, "");
        }

        public static GiftAction mine(String block, int count) {
            return new GiftAction("mine", "", "", block, count, "");
        }

        public static GiftAction goal(String item) {
            return new GiftAction("goal", "", item, "", 1, "");
        }

        public static GiftAction random(String pool) {
            return new GiftAction("random", "", "", "", 1, pool);
        }

        public static GiftAction fromJson(JsonElement element) {
            if (element == null) {
                return null;
            }
            if (element.isJsonPrimitive()) {
                return parseCompact(element.getAsString());
            }
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject object = element.getAsJsonObject();
            String type = object.has("type") ? object.get("type").getAsString() : "";
            if (type.isBlank()) {
                return null;
            }
            return new GiftAction(
                    type,
                    object.has("text") ? object.get("text").getAsString() : "",
                    object.has("item") ? object.get("item").getAsString() : "",
                    object.has("block") ? object.get("block").getAsString() : "",
                    object.has("count") ? Math.max(1, object.get("count").getAsInt()) : 1,
                    object.has("pool") ? object.get("pool").getAsString() : "");
        }

        private static GiftAction parseCompact(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] parts = raw.split(":", 4);
            return switch (parts[0]) {
                case "say" -> say(parts.length > 1 ? parts[1] : "");
                case "gather" -> gather(parts.length > 1 ? parts[1] : "minecraft:oak_log",
                        parts.length > 2 ? Integer.parseInt(parts[2]) : 4);
                case "mine" -> mine(parts.length > 1 ? parts[1] : "minecraft:stone",
                        parts.length > 2 ? Integer.parseInt(parts[2]) : 8);
                case "goal" -> goal(parts.length > 1 ? parts[1] : "minecraft:crafting_table");
                case "random" -> random(parts.length > 1 ? parts[1] : "big");
                case "brain" -> new GiftAction("brain", parts.length > 1 ? parts[1] : "", "", "", 1, "");
                case "fish" -> new GiftAction("fish", "", "", "",
                        parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1])) : 2, "");
                default -> of(parts[0]);
            };
        }

        public JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("type", type);
            if (text != null && !text.isBlank()) {
                object.addProperty("text", text);
            }
            if (item != null && !item.isBlank()) {
                object.addProperty("item", item);
            }
            if (block != null && !block.isBlank()) {
                object.addProperty("block", block);
            }
            if (count > 1) {
                object.addProperty("count", count);
            }
            if (pool != null && !pool.isBlank()) {
                object.addProperty("pool", pool);
            }
            return object;
        }
    }
}
