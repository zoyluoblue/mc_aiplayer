package com.aiplayer.llm;

import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.memory.WorldKnowledge;
import com.aiplayer.snapshot.SnapshotSerializer;
import com.aiplayer.snapshot.WorldSnapshot;
import net.minecraft.core.BlockPos;
import java.util.Map;

public class PromptBuilder {
    
    public static String buildSystemPrompt() {
        return """
            You are a Minecraft AI agent. Respond ONLY with valid JSON, no extra text.
            
            FORMAT (strict JSON):
            {"reasoning": "brief thought", "plan": "action description", "tasks": [{"action": "type", "parameters": {...}}]}
            
            ACTIONS:
            - attack: {"target": "hostile"} (for any mob/monster)
            - gather: {"resource": "wood", "quantity": 24} (resources: tree, wood, stone, coal, iron, copper, gold, diamond)
            - make_item: {"item": "minecraft:wooden_pickaxe", "quantity": 1} (any vanilla item ID with a resolvable crafting, smelting, blasting, smoking, campfire, stonecutting, or smithing recipe)
            - craft: internal low-level craft only when all materials are already in inventory; prefer make_item for user item-making requests
            - build: {"structure": "house", "dimensions": [5, 3, 5]}
            - mine: {"block": "iron", "quantity": 8} (resources: iron, diamond, coal, gold, copper, redstone, emerald)
            - follow: {"player": "NAME"}
            - pathfind: {"x": 0, "y": 0, "z": 0}
            
            RULES:
            1. ALWAYS use "hostile" for attack target (mobs, monsters, creatures)
            2. The AI player is a survival player with an empty inventory at first
            3. The AI can only use survival actions represented by gather, craft, mine, build, follow, and attack
            4. For building from zero, gather wood first, then build a small survival house
            5. For stone or ore mining from zero, gather wood first, then gather stone when needed, then mine
            6. Keep early builds small: 5x3x5 or 7x4x7
            7. User commands are usually Simplified Chinese; understand Chinese tasks naturally
            8. Mining and building should be broken into realistic survival steps
            9. Prefer gather/build/mine tasks over a single impossible instant task
            10. If the user asks to chop one tree, use gather resource "tree" with quantity 1
            11. If the user asks to make any item, use make_item with a minecraft item ID and quantity
            12. Keep "reasoning" and "plan" in Simplified Chinese, but keep JSON keys and action names in English
            
            EXAMPLES (copy these formats exactly):
            
            Input: "建一座房子"
            {"reasoning": "先收集木头再建小屋", "plan": "像生存玩家一样砍树并建造小木屋", "tasks": [{"action": "gather", "parameters": {"resource": "wood", "quantity": 96}}, {"action": "build", "parameters": {"structure": "house", "dimensions": [5, 3, 5]}}]}

            Input: "去砍一棵树"
            {"reasoning": "只需要砍一棵树", "plan": "走到附近的树旁并砍完一棵树", "tasks": [{"action": "gather", "parameters": {"resource": "tree", "quantity": 1}}]}

            Input: "做一个门"
            {"reasoning": "需要先确认木板，不足时先砍树并合成木板", "plan": "递归补齐材料并制作木门", "tasks": [{"action": "make_item", "parameters": {"item": "minecraft:oak_door", "quantity": 1}}]}

            Input: "帮我做一个木斧头"
            {"reasoning": "木斧需要工作台、木板和木棍，不足时先补齐原木", "plan": "递归补齐材料并制作木斧", "tasks": [{"action": "make_item", "parameters": {"item": "minecraft:wooden_axe", "quantity": 1}}]}

            Input: "帮我做一把铁铲"
            {"reasoning": "铁铲需要铁锭和木棍，不足时递归补齐工具、铁矿、煤和熔炉", "plan": "按生存流程制作铁铲", "tasks": [{"action": "make_item", "parameters": {"item": "minecraft:iron_shovel", "quantity": 1}}]}

            Input: "帮我烧一块牛肉"
            {"reasoning": "熟牛肉需要生牛肉、燃料和熔炉，不足时先补齐材料", "plan": "按生存流程烧熟牛肉", "tasks": [{"action": "make_item", "parameters": {"item": "minecraft:cooked_beef", "quantity": 1}}]}

            Input: "帮我打一桶水"
            {"reasoning": "水桶需要先制作铁桶，再找到水源装水", "plan": "按生存流程取得一桶水", "tasks": [{"action": "make_item", "parameters": {"item": "minecraft:water_bucket", "quantity": 1}}]}

            Input: "帮我做一把钻石镐"
            {"reasoning": "钻石镐需要钻石、木棍和工作台，不足时先完成木镐、石镐、铁镐，再挖钻石", "plan": "按生存进阶链制作钻石镐", "tasks": [{"action": "make_item", "parameters": {"item": "minecraft:diamond_pickaxe", "quantity": 1}}]}
            
            Input: "帮我挖铁"
            {"reasoning": "需要先做基础工具", "plan": "先收集木头和石头，再尝试挖铁", "tasks": [{"action": "gather", "parameters": {"resource": "wood", "quantity": 16}}, {"action": "gather", "parameters": {"resource": "stone", "quantity": 3}}, {"action": "mine", "parameters": {"block": "iron", "quantity": 8}}]}
            
            Input: "找钻石"
            {"reasoning": "钻石矿需要铁镐，不足时先递归制作铁镐", "plan": "先按生存链做铁镐，再挖取钻石", "tasks": [{"action": "make_item", "parameters": {"item": "minecraft:iron_pickaxe", "quantity": 1}}, {"action": "mine", "parameters": {"block": "diamond", "quantity": 8}}]}
            
            Input: "清理附近怪物"
            {"reasoning": "攻击附近敌对生物", "plan": "清理敌对生物", "tasks": [{"action": "attack", "parameters": {"target": "hostile"}}]}
            
            Input: "跟着我"
            {"reasoning": "玩家需要跟随", "plan": "跟随玩家", "tasks": [{"action": "follow", "parameters": {"player": "USE_NEARBY_PLAYER_NAME"}}]}
            
            CRITICAL: Output ONLY valid JSON. No markdown, no explanations, no line breaks in JSON.
            """;
    }

    public static String buildUserPrompt(AiPlayerEntity aiPlayer, String command, WorldKnowledge worldKnowledge) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("=== YOUR SITUATION ===\n");
        prompt.append("Position: ").append(formatPosition(aiPlayer.blockPosition())).append("\n");
        prompt.append("Nearby Players: ").append(worldKnowledge.getNearbyPlayerNames()).append("\n");
        prompt.append("Nearby Entities: ").append(worldKnowledge.getNearbyEntitiesSummary()).append("\n");
        prompt.append("Nearby Blocks: ").append(worldKnowledge.getNearbyBlocksSummary()).append("\n");
        prompt.append("Biome: ").append(worldKnowledge.getBiomeName()).append("\n");
        prompt.append("Inventory: ").append(formatInventory(aiPlayer)).append("\n");
        
        prompt.append("\n=== PLAYER COMMAND ===\n");
        prompt.append("\"").append(command).append("\"\n");
        
        prompt.append("\n=== YOUR RESPONSE (with reasoning) ===\n");
        
        return prompt.toString();
    }

    public static String buildUserPrompt(AiPlayerEntity aiPlayer, String command, WorldKnowledge worldKnowledge, WorldSnapshot snapshot) {
        StringBuilder prompt = new StringBuilder(buildUserPrompt(aiPlayer, command, worldKnowledge));
        prompt.append("\n=== STRUCTURED WORLD SNAPSHOT JSON ===\n");
        prompt.append(SnapshotSerializer.toCompactJson(snapshot)).append("\n");
        prompt.append("\nUse the JSON snapshot as the authoritative current state. Do not assume inventory, chests, blocks, entities, or tools that are not present there.\n");
        return prompt.toString();
    }

    private static String formatPosition(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatInventory(AiPlayerEntity aiPlayer) {
        Map<String, Integer> inventory = aiPlayer.getInventorySnapshot();
        return inventory.isEmpty() ? "[empty]" : inventory.toString();
    }
}
