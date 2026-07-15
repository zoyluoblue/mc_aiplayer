package io.github.zoyluo.aibot.camera;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 摄像机（旁观者玩家）↔ AI bot 的持久绑定管理器。
 *
 * 解决两个问题：
 * 1. 旁观者第一人称看不到手——客户端 GameRendererSpectatorHandMixin 绕过闸门后，
 *    渲染的是摄像机玩家自己的手，所以这里每 tick 把 bot 的主手/副手物品镜像进
 *    摄像机玩家的选中槽/副手槽（旁观者物品无任何世界副作用），挥手动作同步转发。
 * 2. bot 死亡重生是 despawn+respawn 全新实体，原版会把摄像机视角重置回自身——
 *    这里检测到脱靶后自动重绑，直播镜头不断。
 *
 * 绑定按摄像机 UUID 记忆，摄像机客户端崩溃重连后自动恢复视角；
 * 仅在显式 release 或摄像机切出旁观模式时解除（防止往生存玩家背包里镜像物品刷物品）。
 */
public final class CameraMirror {
    public static final CameraMirror INSTANCE = new CameraMirror();

    private static final class Binding {
        final String botName;
        boolean prevSwinging;
        int prevTicks;

        Binding(String botName) {
            this.botName = botName;
        }
    }

    private final Map<UUID, Binding> bindings = new HashMap<>();

    private CameraMirror() {
    }

    public void bind(ServerPlayerEntity camera, String botName) {
        bindings.put(camera.getUuid(), new Binding(botName));
    }

    public void release(ServerPlayerEntity camera) {
        if (bindings.remove(camera.getUuid()) != null) {
            clearMirrored(camera);
        }
    }

    public Optional<String> boundBot(ServerPlayerEntity camera) {
        Binding b = bindings.get(camera.getUuid());
        return b == null ? Optional.empty() : Optional.of(b.botName);
    }

    public void clear() {
        bindings.clear();
    }

    public void tick(MinecraftServer server) {
        if (bindings.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Binding>> it = bindings.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Binding> entry = it.next();
            ServerPlayerEntity camera = server.getPlayerManager().getPlayer(entry.getKey());
            if (camera == null) {
                continue; // 摄像机离线：保留绑定，重连后自动恢复视角
            }
            if (camera.interactionManager.getGameMode() != GameMode.SPECTATOR) {
                clearMirrored(camera);
                it.remove(); // 切出旁观模式：立即解绑，绝不向可交互背包镜像物品
                continue;
            }
            Binding binding = entry.getValue();
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.getByName(binding.botName).orElse(null);
            if (bot == null || bot.isRemoved()) {
                continue; // bot 死亡重生间隙：保留绑定等新实体
            }
            if (camera.getCameraEntity() != bot) {
                if (camera.getServerWorld() != bot.getServerWorld()) {
                    camera.teleport(bot.getServerWorld(), bot.getX(), bot.getY(), bot.getZ(),
                            Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
                }
                camera.setCameraEntity(bot);
            }
            mirrorHands(camera, bot);
            mirrorSwing(camera, bot, binding);
        }
    }

    private static void mirrorHands(ServerPlayerEntity camera, AIPlayerEntity bot) {
        PlayerInventory inv = camera.getInventory();
        mirrorSlot(inv, inv.selectedSlot, bot.getMainHandStack());
        mirrorSlot(inv, PlayerInventory.OFF_HAND_SLOT, bot.getOffHandStack());
    }

    private static void mirrorSlot(PlayerInventory inv, int slot, ItemStack want) {
        if (!ItemStack.areEqual(inv.getStack(slot), want)) {
            inv.setStack(slot, want.copy());
            inv.markDirty();
        }
    }

    private static void mirrorSwing(ServerPlayerEntity camera, AIPlayerEntity bot, Binding binding) {
        boolean swinging = bot.handSwinging;
        int ticks = bot.handSwingTicks;
        boolean newSwing = swinging && (!binding.prevSwinging || ticks < binding.prevTicks);
        binding.prevSwinging = swinging;
        binding.prevTicks = ticks;
        if (newSwing) {
            Hand hand = bot.preferredHand == null ? Hand.MAIN_HAND : bot.preferredHand;
            camera.swingHand(hand, true); // true = 动画包也发给摄像机自己，触发第一人称挥臂
        }
    }

    private static void clearMirrored(ServerPlayerEntity camera) {
        PlayerInventory inv = camera.getInventory();
        inv.setStack(inv.selectedSlot, ItemStack.EMPTY);
        inv.setStack(PlayerInventory.OFF_HAND_SLOT, ItemStack.EMPTY);
        inv.markDirty();
    }
}
