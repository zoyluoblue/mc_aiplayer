package io.github.zoyluo.aibot.gift;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.network.payload.AudienceSnapshotS2C;
import io.github.zoyluo.aibot.task.LongRunningIntentManager;
import io.github.zoyluo.aibot.task.TaskManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Session-only active-viewer roster and the single manually bound audience bot. Main-thread only. */
public final class AudienceControlService {
    public static final AudienceControlService INSTANCE = new AudienceControlService();

    private static final int MAX_VIEWERS = 500;
    private static final long COMMAND_GAP_MS = 2_500L;
    private static final String BOT_NAME_BASE = "FanAI";

    public record Viewer(String key, String displayName, boolean reliableIdentity,
                         String lastKind, long lastSeenMillis) {
    }

    private record Binding(UUID ownerUuid, String viewerKey, String viewerName,
                           boolean reliableIdentity, UUID botUuid, String botName) {
    }

    private final Map<String, Viewer> viewers = new LinkedHashMap<>();
    private Binding binding;
    private Binding pendingRespawnBinding;
    private long lastCommandMillis;
    private boolean ownerOverrideActive;
    private String status = "等待直播观众事件";

    private AudienceControlService() {
    }

    /** Returns true when a bound viewer chat was consumed as an audience-bot command. */
    public boolean accept(MinecraftServer server, String kind, String user, String text,
                          String viewerId, boolean reliableIdentity) {
        String safeKind = sanitize(kind, 16).toLowerCase(Locale.ROOT);
        String safeUser = sanitize(user, 40);
        if (safeKind.isBlank() || safeUser.isBlank()) {
            return false;
        }
        String key = viewerKey(safeUser, viewerId, reliableIdentity);
        boolean reliable = reliableIdentity && !sanitize(viewerId, 128).isBlank();
        if (reliable) {
            viewers.remove(nameKey(safeUser));
        }
        viewers.remove(key);
        viewers.put(key, new Viewer(key, safeUser, reliable, safeKind, System.currentTimeMillis()));
        trimRoster();

        Binding current = currentBinding();
        if (!"chat".equals(safeKind) || text == null || text.isBlank()
                || !matchesBinding(current, key, safeUser)) {
            return false;
        }
        AIPlayerEntity bot = boundBot().orElse(null);
        if (bot == null) {
            if (pendingRespawnBinding != null && pendingRespawnBinding.equals(current)) {
                status = "观众 AI 正在重生，弹幕已暂缓";
                return true;
            }
            status = "观众 AI 已离线，请重新绑定";
            binding = null;
            return false;
        }
        if (ownerOverrideActive) {
            boolean ownerWorkActive = BrainCoordinator.INSTANCE.status(bot).busy()
                    || GoalExecutor.INSTANCE.hasActivePlan(bot)
                    || TaskManager.INSTANCE.getActive(bot).isPresent();
            if (ownerWorkActive) {
                status = "主播正在接管 " + bot.getGameProfile().getName() + "，观众指令暂缓";
                return true;
            }
            ownerOverrideActive = false;
        }
        long now = System.currentTimeMillis();
        if (now - lastCommandMillis < COMMAND_GAP_MS) {
            status = "已忽略过快弹幕：" + safeUser;
            return true;
        }
        lastCommandMillis = now;
        String command = sanitize(text, 160);
        if (command.isBlank()) {
            return true;
        }

        // A new controller comment replaces the previous audience task. Streamer messages still use
        // BrainCoordinator's owner hard-preemption and therefore remain the highest authority.
        BrainCoordinator.INSTANCE.abort(bot);
        GoalExecutor.INSTANCE.clear(bot);
        LongRunningIntentManager.INSTANCE.clear(bot);
        TaskManager.INSTANCE.resetToIdle(bot);
        bot.getActionPack().stopAll();
        BrainCoordinator.INSTANCE.sendPanelChat(bot, "user", safeUser + "（弹幕）: " + command);
        String prompt = "你当前由直播观众「" + safeUser + "」临时控制。TA 的新指令是：" + command
                + "\n把它当作正常游戏指令执行，可直接调用合适工具完成，不要要求二次调用。"
                + "禁止 run_command；主播本人的任何新命令优先级更高。";
        boolean queued = BrainCoordinator.INSTANCE.handleMessage(bot, "audience:" + key, prompt);
        status = queued ? "已转发 " + safeUser + " 的弹幕" : "观众 AI 大脑忙，指令未进入";
        BotLog.comm(bot, "audience_command", "viewer", safeUser, "key", key, "queued", queued);
        return true;
    }

    public String bind(MinecraftServer server, ServerPlayerEntity owner, String viewerKey) {
        Viewer viewer = viewers.get(viewerKey == null ? "" : viewerKey.trim());
        if (viewer == null) {
            status = "所选观众已不在列表，请刷新";
            return status;
        }
        Binding occupied = currentBinding();
        if (occupied != null && !occupied.ownerUuid().equals(owner.getUuid())) {
            status = "已有其他主播绑定观众 AI";
            return status;
        }
        Binding pending = pendingRespawnBinding;
        if (pending != null && pending.ownerUuid().equals(owner.getUuid())) {
            AIPlayerManager.INSTANCE.cancelPendingRespawn(pending.botName(), pending.ownerUuid());
            pendingRespawnBinding = null;
        }
        AIPlayerEntity bot = reusableAudienceBot(owner.getUuid()).orElse(null);
        if (bot == null) {
            String botName = availableBotName(server);
            bot = AIPlayerManager.INSTANCE.spawnAdditional(
                            server,
                            botName,
                            owner.getServerWorld(),
                            owner.getPos().add(1.5D, 0.0D, 0.0D),
                            owner.getYaw(),
                            owner.getPitch(),
                            GameMode.SURVIVAL,
                            owner.getUuid())
                    .orElse(null);
            if (bot == null) {
                status = "无法生成观众 AI，请检查名称或出生点";
                return status;
            }
        }
        BrainCoordinator.INSTANCE.reset(bot);
        GoalExecutor.INSTANCE.clear(bot);
        LongRunningIntentManager.INSTANCE.clear(bot);
        TaskManager.INSTANCE.resetToIdle(bot);
        bot.getActionPack().stopAll();
        AIPlayerManager.INSTANCE.setRole(bot, "audience");
        binding = new Binding(owner.getUuid(), viewer.key(), viewer.displayName(),
                viewer.reliableIdentity(), bot.getUuid(), bot.getGameProfile().getName());
        lastCommandMillis = 0L;
        ownerOverrideActive = false;
        status = "已绑定 " + viewer.displayName() + " -> " + binding.botName();
        BrainCoordinator.INSTANCE.triggerSpeech(bot, viewer.displayName() + "，这局你来指挥我！");
        BotLog.lifecycle(bot, "audience_bound", "viewer", viewer.displayName(),
                "key", viewer.key(), "reliable", viewer.reliableIdentity());
        return status;
    }

    public String unbind(MinecraftServer server, ServerPlayerEntity owner) {
        Binding current = binding != null ? binding : pendingRespawnBinding;
        if (current == null) {
            status = "当前没有绑定观众";
            return status;
        }
        if (!current.ownerUuid().equals(owner.getUuid())) {
            status = "只有绑定该 AI 的主播可以解绑";
            return status;
        }
        AIPlayerManager.INSTANCE.cancelPendingRespawn(current.botName(), current.ownerUuid());
        AIPlayerManager.INSTANCE.getByUuid(current.botUuid())
                .ifPresent(bot -> AIPlayerManager.INSTANCE.despawn(server, bot.getGameProfile().getName()));
        binding = null;
        pendingRespawnBinding = null;
        lastCommandMillis = 0L;
        ownerOverrideActive = false;
        status = "已解绑并移除观众 AI";
        return status;
    }

    public AudienceSnapshotS2C snapshot() {
        List<AudienceSnapshotS2C.ViewerEntry> entries = viewers.values().stream()
                .sorted(Comparator.comparingLong(Viewer::lastSeenMillis).reversed())
                .map(viewer -> new AudienceSnapshotS2C.ViewerEntry(
                        viewer.key(), viewer.displayName(), viewer.reliableIdentity(),
                        viewer.lastKind(), viewer.lastSeenMillis()))
                .toList();
        Binding current = currentBinding();
        return new AudienceSnapshotS2C(
                entries,
                current == null ? "" : current.viewerKey(),
                current == null ? "" : current.viewerName(),
                current == null ? "" : current.botName(),
                status);
    }

    public Optional<String> audienceBotName(UUID ownerUuid) {
        return binding != null && binding.ownerUuid().equals(ownerUuid)
                ? Optional.of(binding.botName()) : Optional.empty();
    }

    public boolean isAudienceBot(AIPlayerEntity bot) {
        return "audience".equals(AIPlayerManager.INSTANCE.role(bot));
    }

    public void onOwnerCommand(AIPlayerEntity bot) {
        if (binding != null && binding.botUuid().equals(bot.getUuid())) {
            ownerOverrideActive = true;
            status = "主播已接管 " + binding.botName();
        }
    }

    public void onBotRespawn(AIPlayerEntity bot) {
        Binding source = binding != null ? binding : pendingRespawnBinding;
        if (source != null && source.botName().equalsIgnoreCase(bot.getGameProfile().getName())) {
            binding = new Binding(source.ownerUuid(), source.viewerKey(), source.viewerName(),
                    source.reliableIdentity(), bot.getUuid(), bot.getGameProfile().getName());
            pendingRespawnBinding = null;
            status = "观众 AI 已重生并保持绑定";
        }
    }

    public void onBotRespawnFailed(String botName) {
        if (pendingRespawnBinding != null
                && pendingRespawnBinding.botName().equalsIgnoreCase(botName)) {
            pendingRespawnBinding = null;
            ownerOverrideActive = false;
            status = "观众 AI 重生失败，请重新绑定";
        }
    }

    public void onBotDeath(AIPlayerEntity bot) {
        if (binding != null && binding.botUuid().equals(bot.getUuid())) {
            pendingRespawnBinding = binding;
        }
    }

    public void onBotDespawn(AIPlayerEntity bot) {
        if (binding != null && binding.botUuid().equals(bot.getUuid())) {
            binding = null;
            if (pendingRespawnBinding != null
                    && pendingRespawnBinding.botUuid().equals(bot.getUuid())) {
                status = "观众 AI 正在重生";
            } else {
                lastCommandMillis = 0L;
                ownerOverrideActive = false;
                status = "观众 AI 已移除";
            }
        }
    }

    public void clear() {
        viewers.clear();
        binding = null;
        pendingRespawnBinding = null;
        lastCommandMillis = 0L;
        ownerOverrideActive = false;
        status = "等待直播观众事件";
    }

    private Optional<AIPlayerEntity> boundBot() {
        return binding == null ? Optional.empty() : AIPlayerManager.INSTANCE.getByUuid(binding.botUuid());
    }

    private Binding currentBinding() {
        return binding != null ? binding : pendingRespawnBinding;
    }

    private Optional<AIPlayerEntity> reusableAudienceBot(UUID ownerUuid) {
        Optional<AIPlayerEntity> bound = boundBot();
        if (bound.isPresent() && AIPlayerManager.INSTANCE.ownerOf(bound.get()).filter(ownerUuid::equals).isPresent()) {
            return bound;
        }
        return AIPlayerManager.INSTANCE.all().stream()
                .filter(this::isAudienceBot)
                .filter(bot -> AIPlayerManager.INSTANCE.ownerOf(bot).filter(ownerUuid::equals).isPresent())
                .findFirst();
    }

    private static boolean matchesBinding(Binding current, String eventKey, String displayName) {
        if (current == null) {
            return false;
        }
        if (current.reliableIdentity()) {
            return current.viewerKey().equals(eventKey);
        }
        return current.viewerKey().equals(nameKey(displayName));
    }

    private static String availableBotName(MinecraftServer server) {
        for (int suffix = 0; suffix < 100; suffix++) {
            String name = suffix == 0 ? BOT_NAME_BASE : BOT_NAME_BASE + suffix;
            if (AIPlayerManager.INSTANCE.getByName(name).isEmpty()
                    && server.getPlayerManager().getPlayer(name) == null) {
                return name;
            }
        }
        return BOT_NAME_BASE + System.currentTimeMillis() % 10_000L;
    }

    private void trimRoster() {
        while (viewers.size() > MAX_VIEWERS) {
            String oldest = viewers.keySet().iterator().next();
            if (binding != null && binding.viewerKey().equals(oldest)) {
                Viewer keep = viewers.remove(oldest);
                viewers.put(oldest, keep);
                continue;
            }
            viewers.remove(oldest);
        }
    }

    private static String viewerKey(String displayName, String viewerId, boolean reliable) {
        String id = sanitize(viewerId, 128);
        return reliable && !id.isBlank() ? "id:" + id : nameKey(displayName);
    }

    private static String nameKey(String displayName) {
        return "name:" + sanitize(displayName, 40).toLowerCase(Locale.ROOT);
    }

    private static String sanitize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\p{Cntrl}\\r\\n\\t]", " ").trim().replaceAll("\\s+", " ");
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }
}
