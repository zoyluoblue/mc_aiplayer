package com.aiplayer.plugin;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.actions.*;
import com.aiplayer.di.ServiceContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoreActionsPlugin implements ActionPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoreActionsPlugin.class);

    private static final String PLUGIN_ID = "core-actions";
    private static final String VERSION = "1.0.0";

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void onLoad(ActionRegistry registry, ServiceContainer container) {
        AiPlayerMod.info("plugin", "Loading CoreActionsPlugin v{}", VERSION);
        int priority = getPriority();
        registry.register("pathfind",
            (aiPlayer, task, ctx) -> new PathfindAction(aiPlayer, task),
            priority, PLUGIN_ID);
        registry.register("mine",
            (aiPlayer, task, ctx) -> new MineBlockAction(aiPlayer, task),
            priority, PLUGIN_ID);

        registry.register("gather",
            (aiPlayer, task, ctx) -> new GatherResourceAction(aiPlayer, task),
            priority, PLUGIN_ID);
        registry.register("place",
            (aiPlayer, task, ctx) -> new PlaceBlockAction(aiPlayer, task),
            priority, PLUGIN_ID);

        registry.register("build",
            (aiPlayer, task, ctx) -> new BuildStructureAction(aiPlayer, task),
            priority, PLUGIN_ID);
        registry.register("craft",
            (aiPlayer, task, ctx) -> new CraftItemAction(aiPlayer, task),
            priority, PLUGIN_ID);
        registry.register("make_item",
            (aiPlayer, task, ctx) -> new MakeItemAction(aiPlayer, task),
            priority, PLUGIN_ID);
        registry.register("attack",
            (aiPlayer, task, ctx) -> new CombatAction(aiPlayer, task),
            priority, PLUGIN_ID);
        registry.register("follow",
            (aiPlayer, task, ctx) -> new FollowPlayerAction(aiPlayer, task),
            priority, PLUGIN_ID);

        AiPlayerMod.info("plugin", "CoreActionsPlugin loaded {} actions", registry.getActionCount());
    }

    @Override
    public void onUnload() {
        AiPlayerMod.info("plugin", "CoreActionsPlugin unloading");
    }

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public String[] getDependencies() {
        return new String[0];
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public String getDescription() {
        return "Core AiPlayer AI actions: mining, building, combat, pathfinding, and more";
    }
}
