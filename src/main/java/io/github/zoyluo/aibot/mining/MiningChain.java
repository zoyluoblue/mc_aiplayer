package io.github.zoyluo.aibot.mining;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * S2:矿物开采链单一数据源——把"矿方块 → 掉落物 → 冶炼产物 → 所需镐级 → 推荐 Y 层"收敛到一处。
 *
 * 此前这些知识散落:`GoalPlanner.bestMiningY` 硬编码 Y 层、`ToolTier` 算镐级、`HarvestCore.expectedDropsFor` 算掉落、
 * 冶炼映射在各处。本表作为单一可信源:Y 层与冶炼产物由本类提供;镐级委托既有 {@link ToolTier}(保持镐级单一源)。
 */
public final class MiningChain {

    /** 矿物链条目。bestY=推荐下挖到的目标 Y(峰值层);bestY=Integer.MAX_VALUE 表示浅层广布、无需强制下挖。 */
    public record OreEntry(Block ore, Block deepslate, Item rawDrop, Item smelted, int pickaxeTier, int bestY) {
    }

    private static final OreEntry[] TABLE = {
            //          普通矿石                深板岩变体                         掉落物            冶炼产物          镐级           峰值Y
            new OreEntry(Blocks.COAL_ORE,     Blocks.DEEPSLATE_COAL_ORE,     Items.COAL,         Items.COAL,        ToolTier.WOOD,  Integer.MAX_VALUE),
            new OreEntry(Blocks.COPPER_ORE,   Blocks.DEEPSLATE_COPPER_ORE,   Items.RAW_COPPER,   Items.COPPER_INGOT, ToolTier.STONE, 48),
            new OreEntry(Blocks.IRON_ORE,     Blocks.DEEPSLATE_IRON_ORE,     Items.RAW_IRON,     Items.IRON_INGOT,  ToolTier.STONE, 16),
            new OreEntry(Blocks.LAPIS_ORE,    Blocks.DEEPSLATE_LAPIS_ORE,    Items.LAPIS_LAZULI, Items.LAPIS_LAZULI, ToolTier.STONE, Integer.MAX_VALUE),
            new OreEntry(Blocks.GOLD_ORE,     Blocks.DEEPSLATE_GOLD_ORE,     Items.RAW_GOLD,     Items.GOLD_INGOT,  ToolTier.IRON,  -16),
            new OreEntry(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Items.REDSTONE,     Items.REDSTONE,    ToolTier.IRON,  -59),
            new OreEntry(Blocks.EMERALD_ORE,  Blocks.DEEPSLATE_EMERALD_ORE,  Items.EMERALD,      Items.EMERALD,     ToolTier.IRON,  -16),
            new OreEntry(Blocks.DIAMOND_ORE,  Blocks.DEEPSLATE_DIAMOND_ORE,  Items.DIAMOND,      Items.DIAMOND,     ToolTier.IRON,  -59),
    };

    private static final Map<Block, OreEntry> BY_BLOCK = new HashMap<>();
    private static final Map<Item, Item> SMELT_BY_RAW = new HashMap<>();

    static {
        for (OreEntry e : TABLE) {
            BY_BLOCK.put(e.ore(), e);
            BY_BLOCK.put(e.deepslate(), e);
            SMELT_BY_RAW.put(e.rawDrop(), e.smelted());
        }
    }

    private MiningChain() {
    }

    /** 该矿方块(普通或深板岩)的链条目;非已知矿返回 null。 */
    public static OreEntry forOre(Block block) {
        return BY_BLOCK.get(block);
    }

    /**
     * 这组矿物推荐下挖到的目标 Y:取所有已知矿中**最深**(最小 bestY)的层,以便一次下到位。
     * 全是"浅层广布矿"(如煤)或无已知矿 → 返回 Integer.MAX_VALUE(调用方据此不强制下挖)。
     */
    public static int bestY(Set<Block> ores) {
        int best = Integer.MAX_VALUE;
        for (Block ore : ores) {
            OreEntry e = BY_BLOCK.get(ore);
            if (e != null) {
                best = Math.min(best, e.bestY());
            }
        }
        return best;
    }

    /** 这组矿物所需镐级(委托既有 ToolTier,保持镐级单一源)。 */
    public static int pickaxeTier(Set<Block> ores) {
        return ToolTier.requiredPickaxeTier(ores);
    }

    /** 生矿掉落物 → 冶炼产物(raw_iron→iron_ingot 等);非矿物掉落返回 null,由 SmeltChain(S5)兜其它冶炼。 */
    public static Item smeltOutput(Item rawDrop) {
        return SMELT_BY_RAW.get(rawDrop);
    }
}
