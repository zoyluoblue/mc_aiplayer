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
            // SAFE-1:环境安全网最先跑;若正在自救(溺水/岩浆)则本 tick 接管,跳过其它检查。
            if (NavSafetyNet.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            StuckWatcher.INSTANCE.tickBot(server, bot);
            boolean handled = runDanger && DangerWatcher.INSTANCE.scanBot(server, bot);
            if (!handled && GoalExecutor.INSTANCE.tickBot(server, bot)) {
                continue;
            }
            if (!handled && runBackground) {
                IdleCoordinator.INSTANCE.tickBot(bot);
            }
        }
    }
}
