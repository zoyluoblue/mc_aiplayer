package com.aiplayer.agent;

import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentIntentParserTest {
    private final AgentIntentParser parser = new AgentIntentParser(new RecipeResolver());
    private final WorldSnapshot snapshot = WorldSnapshot.empty("");

    @Test
    void normalizesAcquisitionAndCraftingRequests() {
        assertIntent("/ai say 我要一把铁镐", AgentIntentType.MAKE_ITEM, "minecraft:iron_pickaxe");
        assertIntent("/ai say 帮我做个铁镐", AgentIntentType.MAKE_ITEM, "minecraft:iron_pickaxe");
        assertIntent("/ai say 弄把铁镐给我", AgentIntentType.MAKE_ITEM, "minecraft:iron_pickaxe");
        assertIntent("/ai say 帮我挖两块金锭", AgentIntentType.MAKE_ITEM, "minecraft:gold_ingot");
        assertIntent("/ai say 去挖黑曜石", AgentIntentType.MAKE_ITEM, "minecraft:obsidian");
        assertIntent("/ai say 找绿宝石", AgentIntentType.MAKE_ITEM, "minecraft:emerald");
        assertIntent("/ai say 自动挖矿", AgentIntentType.MAKE_ITEM, "minecraft:raw_iron");
        assertIntent("/ai say 挖一些矿", AgentIntentType.MAKE_ITEM, "minecraft:raw_iron");
    }

    @Test
    void distinguishesUtilityControlAndChat() {
        assertIntent("/ai say 帮我烧一块牛肉", AgentIntentType.SMELT_ITEM, "minecraft:cooked_beef");
        assertIntent("/ai say 帮我打一桶水", AgentIntentType.FILL_WATER, "minecraft:water_bucket");
        assertIntent("/ai recall", AgentIntentType.RECALL, null);
        assertIntent("/ai stop", AgentIntentType.STOP, null);
        assertIntent("/ai say 你好", AgentIntentType.CHAT, null);
    }

    private void assertIntent(String command, AgentIntentType expectedType, String expectedItem) {
        AgentIntent intent = parser.parse(command, snapshot);
        assertEquals(expectedType, intent.intentType(), command);
        assertEquals(expectedItem, intent.targetItem(), command);
    }
}
