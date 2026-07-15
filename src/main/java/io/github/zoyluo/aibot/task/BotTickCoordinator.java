package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.coordination.IdleCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.observe.TpsGuard;
import net.minecraft.server.MinecraftServer;

public final class BotTickCoordinator {
    public static final BotTickCoordinator INSTANCE = new BotTickCoordinator();

    private BotTickCoordinator() {
    }

    public void tick(MinecraftServer server) {
        int tick = server.getTicks();
        TpsGuard guard = TpsGuard.INSTANCE;
        boolean runDanger = tick % guard.dangerScanInterval() == 0;
        boolean runBackground = tick % guard.scanInterval() == 0;
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            // 死亡快速路径:每 tick 检查,当 tick 就销毁尸体——不等危险扫描间隔,
            // 客户端死亡特效(红色抽搐)窗口压缩到 ≤1 tick。
            if (bot.getHealth() <= 0.0F || !bot.isAlive()) {
                AIPlayerManager.INSTANCE.handleDeath(server, bot);
                continue;
            }
            // SAFE-1:环境安全网最先跑;若正在自救(溺水/岩浆)则本 tick 接管,跳过其它检查。
            if (NavSafetyNet.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            StuckWatcher.INSTANCE.tickBot(server, bot);
            boolean handled = runDanger && DangerWatcher.INSTANCE.scanBot(server, bot);
            if (!handled && GoalExecutor.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            // P0/P1:follow 意图的空闲恢复(详见 LongRunningIntentManager.tickIdleRestore)。
            // 位置讲究:在 goal 检查之后(goal 进行中绝不抢座),在 IdleCoordinator 之前(主人意图优先于协作任务板)。
            if (!handled) {
                LongRunningIntentManager.INSTANCE.tickIdleRestore(server, bot);
            }
            if (!handled && runBackground) {
                io.github.zoyluo.aibot.action.EquipAction.equipBestArmor(bot); // 第3层:平时也自动穿上背包里更好的护甲
                IdleCoordinator.INSTANCE.tickBot(bot);
            }
            // 活人观感:空闲注视主人(内部自查"无任务才生效",每 tick 极轻)。
            io.github.zoyluo.aibot.action.IdleLookController.INSTANCE.tick(bot);
        }
    }
}
