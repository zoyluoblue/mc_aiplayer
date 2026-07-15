package io.github.zoyluo.aibot.gift;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.task.FollowTask;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.LongRunningIntentManager;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.TaskManager;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class GiftDispatcher {
    public static final GiftDispatcher INSTANCE = new GiftDispatcher();

    private volatile GiftBridgeConfig config = GiftBridgeConfig.load();
    // 冷却/去重时间戳。dedup 按 user|gift 拦截重放,cooldown 按礼物名限频,防止刷礼物打爆任务队列。
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastByUserGift = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastByGift = new java.util.concurrent.ConcurrentHashMap<>();

    private GiftDispatcher() {
    }

    public void reload() {
        config = GiftBridgeConfig.load();
    }

    /**
     * HTTP 桥入队前的同步闸门。返回空 = 放行;非空 = 拒绝原因(仅 duplicate)。
     * 去重窗必须小于 watcher 连击聚合的静默窗(AGG_IDLE 默认 2s):合法批次间隔天然大于去重窗,
     * 永不误杀;同批次的网络重发/页面重渲染落在窗内被正确拦截。
     * 冷却不再整体拒绝,改为 handle 内软门控(只压任务动作,感谢/庆祝照给)。
     */
    public Optional<String> admissionCheck(GiftEvent event) {
        long now = System.currentTimeMillis();
        String gift = event.gift() == null ? "" : event.gift().trim();
        String user = event.user() == null ? "" : event.user().trim();
        long dedupMs = config.dedupMs();
        if (dedupMs > 0) {
            String key = user + "|" + gift;
            Long last = lastByUserGift.get(key);
            if (last != null && now - last < dedupMs) {
                return Optional.of("duplicate");
            }
            lastByUserGift.put(key, now);
        }
        pruneStamps(now);
        return Optional.empty();
    }

    /** 冷却软门控:冷却期内返回 true(压任务动作);未冷却则记下时间戳放行。主线程调用。 */
    private boolean cooldownGate(String gift, long now) {
        long cooldownMs = config.cooldownMs();
        if (cooldownMs <= 0) {
            return false;
        }
        Long last = lastByGift.get(gift);
        if (last != null && now - last < cooldownMs) {
            return true;
        }
        lastByGift.put(gift, now);
        return false;
    }

    /** 连击提档:count 达阈值时在礼物基础档上加档,封顶 4。10 个玫瑰 ≈ 一次跑车的观感。 */
    private int effectiveTier(int baseTier, int count) {
        int[] up = config.tierUpAt();
        int bump = count >= up[1] ? 2 : count >= up[0] ? 1 : 0;
        return Math.min(4, Math.max(1, baseTier) + bump);
    }

    /** 任务配额 = 动作基数 × 送礼数量,封顶 countScaleCap:10 个玫瑰砍 40 木,连击有实感但不失控。 */
    private int scaledCount(int base, int giftCount) {
        long scaled = (long) Math.max(1, base) * Math.max(1, giftCount);
        return (int) Math.min(scaled, config.countScaleCap());
    }

    /** 任务型动作 = 会占用任务槽/goal 计划的动作,冷却期内跳过;say/brain/stop 始终放行。 */
    private static boolean isTaskAction(String type) {
        if (type == null) {
            return false;
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "come_here", "follow", "gather", "mine", "goal", "build_house", "random", "wander", "fish" -> true;
            default -> false;
        };
    }

    /**
     * 空闲池入口(IdleScheduler 调):合成事件渲染 {user}="观众们";动作白名单由调用方把关。
     * 与礼物 handle 的区别:不走去重/冷却/广播/烟花/榜单,纯执行一个动作。
     */
    public String executeIdleAction(MinecraftServer server, AIPlayerEntity bot, GiftBridgeConfig.GiftAction action) {
        return execute(server, bot, new GiftEvent("观众们", "", 1, bot.getGameProfile().getName()), action, 0);
    }

    private void pruneStamps(long now) {
        if (lastByUserGift.size() > 512) {
            lastByUserGift.entrySet().removeIf(entry -> now - entry.getValue() > 600_000L);
        }
        if (lastByGift.size() > 512) {
            lastByGift.entrySet().removeIf(entry -> now - entry.getValue() > 600_000L);
        }
    }

    public GiftBridgeConfig config() {
        return config;
    }

    public String handle(MinecraftServer server, GiftEvent event) {
        String botName = event.bot() == null || event.bot().isBlank() ? config.defaultBot() : event.bot().trim();
        Optional<AIPlayerEntity> botOpt = AIPlayerManager.INSTANCE.getByName(botName);
        if (botOpt.isEmpty()) {
            // 兜底:配置里的名字对不上(改名/换 bot)时,世界里只有一只就用它,礼物链路不断
            var all = AIPlayerManager.INSTANCE.all();
            if (all.size() == 1) {
                botOpt = Optional.of(all.iterator().next());
            } else {
                return "bot_not_found:" + botName;
            }
        }
        AIPlayerEntity bot = botOpt.get();
        String giftName = event.gift() == null ? "" : event.gift().trim();
        GiftBridgeConfig.GiftRule rule = resolveRule(giftName);
        if (rule == null) {
            rule = new GiftBridgeConfig.GiftRule(1, List.of(
                    GiftBridgeConfig.GiftAction.say("谢谢 {user} 送的" + giftName + "！"),
                    GiftBridgeConfig.GiftAction.of("come_here")));
        }
        int tier = effectiveTier(rule.tier(), event.count());
        // 冷却期内感谢/庆祝照给(观感上礼物永远有回应),只压任务型动作,防刷礼物打爆任务队列。
        boolean cooled = cooldownGate(giftName, System.currentTimeMillis());
        if (config.broadcastThanks()) {
            String thanks = render(config.thanksTemplate(), event);
            if (!thanks.isBlank()) {
                // 公屏走原版系统消息,观众在 OBS 采集的画面里看得到;TTS 只听 BotChatS2C,不会念两遍。
                server.getPlayerManager().broadcast(Text.literal(thanks).formatted(Formatting.GOLD), false);
            }
        }
        // 冷却期内庆祝档位钳到 ≤2,防连击刷烟花;正常期按提档后的档位放。
        GiftCelebrator.INSTANCE.celebrate(bot, cooled ? Math.min(tier, 2) : tier, event);

        List<String> executed = new ArrayList<>();
        for (GiftBridgeConfig.GiftAction action : rule.actions()) {
            if (cooled && isTaskAction(action.type())) {
                executed.add("cooldown_skip:" + action.type());
                continue;
            }
            executed.add(execute(server, bot, event, action, 0));
        }
        // 记账用礼物基础档位:count 已乘进加权值,再用提档后的档位会重复计价。
        GiftLedger.INSTANCE.record(event.user(), giftName, event.count(), rule.tier());
        io.github.zoyluo.aibot.overlay.OverlayService.INSTANCE.recordGift(event, tier);
        BotLog.task(bot, cooled ? "gift_cooldown_soft" : "gift_handled",
                "user", event.user(),
                "gift", giftName,
                "count", event.count(),
                "tier", tier,
                "actions", String.join("|", executed));
        return "ok:" + String.join(",", executed);
    }

    private GiftBridgeConfig.GiftRule resolveRule(String giftName) {
        if (giftName == null || giftName.isBlank()) {
            return null;
        }
        GiftBridgeConfig.GiftRule exact = config.gifts().get(giftName);
        if (exact != null && !exact.actions().isEmpty()) {
            return exact;
        }
        for (var entry : config.gifts().entrySet()) {
            if (giftName.contains(entry.getKey()) || entry.getKey().contains(giftName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String execute(MinecraftServer server,
                           AIPlayerEntity bot,
                           GiftEvent event,
                           GiftBridgeConfig.GiftAction action,
                           int depth) {
        if (depth > 3 || action == null || action.type() == null) {
            return "skip";
        }
        String type = action.type().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "say" -> {
                String text = render(action.text(), event);
                if (text.isBlank()) {
                    text = "谢谢 " + safeUser(event) + "！";
                }
                // sendPanelChat 内部就是 sendBotChat,这里只发一次,避免客户端 TTS 队列念两遍
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", text);
                yield "say";
            }
            case "brain" -> {
                String text = render(action.text(), event);
                if (text.isBlank()) {
                    text = "感谢 " + safeUser(event) + " 的礼物";
                }
                boolean queued = BrainCoordinator.INSTANCE.handleMessage(bot, "gift:" + safeUser(event), text);
                yield queued ? "brain" : "brain_busy";
            }
            case "come_here" -> {
                ServerPlayerEntity owner = ownerPlayer(server, bot).orElse(null);
                if (owner == null) {
                    yield "come_here_no_owner";
                }
                TaskManager.INSTANCE.assign(bot, new MoveTask(bot, owner.getBlockPos()));
                yield "come_here";
            }
            case "follow" -> {
                LongRunningIntentManager.INSTANCE.setFollow(bot, "");
                TaskManager.INSTANCE.assign(bot, new FollowTask(""));
                yield "follow";
            }
            case "stop" -> {
                LongRunningIntentManager.INSTANCE.clear(bot);
                GoalExecutor.INSTANCE.clear(bot);
                TaskManager.INSTANCE.resetToIdle(bot);
                bot.getActionPack().stopAll();
                yield "stop";
            }
            case "gather" -> {
                Item item = item(action.item(), "minecraft:oak_log");
                TaskManager.INSTANCE.assign(bot, new GatherQuotaTask(item, scaledCount(action.count(), event.count())));
                yield "gather";
            }
            case "mine" -> {
                Block block = block(action.block(), "minecraft:stone");
                TaskManager.INSTANCE.assign(bot, new MineTask(block, scaledCount(action.count(), event.count())));
                yield "mine";
            }
            case "goal" -> {
                Item item = item(action.item(), "minecraft:crafting_table");
                boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(item, scaledCount(action.count(), event.count())));
                yield started ? "goal" : "goal_failed";
            }
            case "build_house" -> {
                boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Build("small_hut"));
                yield started ? "build_house" : "build_house_failed";
            }
            case "random" -> {
                String poolName = action.pool() == null || action.pool().isBlank() ? "big" : action.pool();
                List<GiftBridgeConfig.GiftAction> pool = config.pools().getOrDefault(poolName, List.of());
                if (pool.isEmpty()) {
                    yield "random_empty";
                }
                GiftBridgeConfig.GiftAction picked = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                yield "random:" + execute(server, bot, event, picked, depth + 1);
            }
            case "wander" -> {
                // 闲逛:随机方向前出 8~16 格取地表可站点走过去,空闲池的"活人感"核心动作。
                BlockPos target = pickWanderTarget(bot);
                if (target == null) {
                    yield "wander_no_target";
                }
                TaskManager.INSTANCE.assign(bot, new MoveTask(bot, target));
                yield "wander";
            }
            case "fish" -> {
                TaskManager.INSTANCE.assign(bot, new io.github.zoyluo.aibot.task.FishTask(Math.max(1, action.count())));
                yield "fish";
            }
            default -> "unknown:" + type;
        };
    }

    /** 随机水平偏移 8~16 格 → 地表 y(穿树冠) → 可站校验,≤8 次尝试。 */
    private static BlockPos pickWanderTarget(AIPlayerEntity bot) {
        var world = bot.getServerWorld();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0D);
            double dist = 8.0D + ThreadLocalRandom.current().nextDouble(8.0D);
            int x = (int) Math.floor(bot.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(bot.getZ() + Math.sin(angle) * dist);
            int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(world, candidate)
                    && world.getFluidState(candidate.down()).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private static Optional<ServerPlayerEntity> ownerPlayer(MinecraftServer server, AIPlayerEntity bot) {
        return AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> server.getPlayerManager().getPlayer(uuid));
    }

    private static Item item(String id, String fallback) {
        String value = id == null || id.isBlank() ? fallback : id;
        return Registries.ITEM.get(Identifier.of(value));
    }

    private static Block block(String id, String fallback) {
        String value = id == null || id.isBlank() ? fallback : id;
        return Registries.BLOCK.get(Identifier.of(value));
    }

    private static String render(String template, GiftEvent event) {
        if (template == null) {
            return "";
        }
        return template
                .replace("{user}", safeUser(event))
                .replace("{gift}", event.gift() == null ? "" : event.gift())
                .replace("{count}", String.valueOf(Math.max(1, event.count())));
    }

    private static String safeUser(GiftEvent event) {
        return event.user() == null || event.user().isBlank() ? "观众" : event.user().trim();
    }

    public record GiftEvent(String user, String gift, int count, String bot) {
    }
}
