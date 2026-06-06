package io.github.zoyluo.aibot.craft;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.Map;

/**
 * 物品 / 方块 → 中文名 对照表。供任务链条({@code GoalStep.describe} / {@code GoalExecutor} 目标标题)在
 * **服务端就翻译好**中文,面板与日志直接显示,不依赖客户端语言文件(实测客户端本地化未必生效)。
 * 未收录的物品回退到英文 id 的 path(如 {@code oak_log}),比完整 {@code minecraft:oak_log} 简洁。
 */
public final class ItemNames {
    private static final Map<Item, String> ITEMS = new HashMap<>();
    private static final Map<Block, String> BLOCKS = new HashMap<>();

    private ItemNames() {
    }

    public static String cn(Item item) {
        if (item == null) {
            return "?";
        }
        String name = ITEMS.get(item);
        return name != null ? name : Registries.ITEM.getId(item).getPath();
    }

    public static String cn(Block block) {
        if (block == null) {
            return "?";
        }
        String name = BLOCKS.get(block);
        return name != null ? name : Registries.BLOCK.getId(block).getPath();
    }

    private static void i(Item item, String cn) {
        ITEMS.put(item, cn);
    }

    private static void b(Block block, String cn) {
        BLOCKS.put(block, cn);
    }

    static {
        // ── 木材 / 基础 ──
        i(Items.OAK_LOG, "橡木");
        i(Items.BIRCH_LOG, "白桦木");
        i(Items.SPRUCE_LOG, "云杉木");
        i(Items.JUNGLE_LOG, "丛林木");
        i(Items.ACACIA_LOG, "金合欢木");
        i(Items.DARK_OAK_LOG, "深色橡木");
        i(Items.MANGROVE_LOG, "红树木");
        i(Items.CHERRY_LOG, "樱花木");
        i(Items.OAK_PLANKS, "橡木板");
        i(Items.BIRCH_PLANKS, "白桦木板");
        i(Items.SPRUCE_PLANKS, "云杉木板");
        i(Items.JUNGLE_PLANKS, "丛林木板");
        i(Items.ACACIA_PLANKS, "金合欢木板");
        i(Items.DARK_OAK_PLANKS, "深色橡木板");
        i(Items.MANGROVE_PLANKS, "红树木板");
        i(Items.CHERRY_PLANKS, "樱花木板");
        i(Items.STICK, "木棍");
        i(Items.CRAFTING_TABLE, "工作台");
        i(Items.FURNACE, "熔炉");
        i(Items.CHEST, "箱子");
        i(Items.TORCH, "火把");
        i(Items.DIRT, "泥土");

        // ── 工具 / 武器 / 防具 ──
        i(Items.WOODEN_PICKAXE, "木镐");
        i(Items.STONE_PICKAXE, "石镐");
        i(Items.IRON_PICKAXE, "铁镐");
        i(Items.GOLDEN_PICKAXE, "金镐");
        i(Items.DIAMOND_PICKAXE, "钻石镐");
        i(Items.NETHERITE_PICKAXE, "下界合金镐");
        i(Items.WOODEN_SWORD, "木剑");
        i(Items.STONE_SWORD, "石剑");
        i(Items.IRON_SWORD, "铁剑");
        i(Items.DIAMOND_SWORD, "钻石剑");
        i(Items.WOODEN_AXE, "木斧");
        i(Items.STONE_AXE, "石斧");
        i(Items.IRON_AXE, "铁斧");
        i(Items.WOODEN_SHOVEL, "木锹");
        i(Items.STONE_SHOVEL, "石锹");
        i(Items.IRON_SHOVEL, "铁锹");
        i(Items.WOODEN_HOE, "木锄");
        i(Items.STONE_HOE, "石锄");
        i(Items.IRON_HOE, "铁锄");
        i(Items.SHIELD, "盾牌");
        i(Items.IRON_HELMET, "铁头盔");
        i(Items.IRON_CHESTPLATE, "铁胸甲");
        i(Items.IRON_LEGGINGS, "铁护腿");
        i(Items.IRON_BOOTS, "铁靴");

        // ── 石 / 矿 / 锭 ──
        i(Items.STONE, "石头");
        i(Items.COBBLESTONE, "圆石");
        i(Items.COBBLED_DEEPSLATE, "深板岩圆石");
        i(Items.BLACKSTONE, "黑石");
        i(Items.COAL, "煤炭");
        i(Items.CHARCOAL, "木炭");
        i(Items.RAW_IRON, "粗铁");
        i(Items.IRON_INGOT, "铁锭");
        i(Items.RAW_GOLD, "粗金");
        i(Items.GOLD_INGOT, "金锭");
        i(Items.RAW_COPPER, "粗铜");
        i(Items.COPPER_INGOT, "铜锭");
        i(Items.DIAMOND, "钻石");
        i(Items.REDSTONE, "红石");
        i(Items.LAPIS_LAZULI, "青金石");

        // ── 食物 ──
        i(Items.SWEET_BERRIES, "甜浆果");
        i(Items.GLOW_BERRIES, "发光浆果");
        i(Items.MELON_SLICE, "西瓜片");
        i(Items.APPLE, "苹果");
        i(Items.WHEAT, "小麦");
        i(Items.WHEAT_SEEDS, "小麦种子");
        i(Items.BREAD, "面包");
        i(Items.HAY_BLOCK, "干草块");
        i(Items.CARROT, "胡萝卜");
        i(Items.POTATO, "土豆");
        i(Items.BAKED_POTATO, "烤土豆");
        i(Items.BEETROOT, "甜菜根");
        i(Items.BEEF, "生牛肉");
        i(Items.COOKED_BEEF, "牛排");
        i(Items.PORKCHOP, "生猪排");
        i(Items.COOKED_PORKCHOP, "熟猪排");
        i(Items.MUTTON, "生羊肉");
        i(Items.COOKED_MUTTON, "熟羊肉");
        i(Items.CHICKEN, "生鸡肉");
        i(Items.COOKED_CHICKEN, "熟鸡肉");
        i(Items.RABBIT, "生兔肉");
        i(Items.COOKED_RABBIT, "熟兔肉");
        i(Items.COD, "生鳕鱼");
        i(Items.COOKED_COD, "熟鳕鱼");
        i(Items.SALMON, "生鲑鱼");
        i(Items.COOKED_SALMON, "熟鲑鱼");

        // ── 方块(MINE / FARM 步用 Block) ──
        b(Blocks.STONE, "石头");
        b(Blocks.DEEPSLATE, "深板岩");
        b(Blocks.COAL_ORE, "煤矿石");
        b(Blocks.DEEPSLATE_COAL_ORE, "深层煤矿石");
        b(Blocks.IRON_ORE, "铁矿石");
        b(Blocks.DEEPSLATE_IRON_ORE, "深层铁矿石");
        b(Blocks.COPPER_ORE, "铜矿石");
        b(Blocks.DEEPSLATE_COPPER_ORE, "深层铜矿石");
        b(Blocks.GOLD_ORE, "金矿石");
        b(Blocks.DEEPSLATE_GOLD_ORE, "深层金矿石");
        b(Blocks.DIAMOND_ORE, "钻石矿石");
        b(Blocks.DEEPSLATE_DIAMOND_ORE, "深层钻石矿石");
        b(Blocks.REDSTONE_ORE, "红石矿石");
        b(Blocks.DEEPSLATE_REDSTONE_ORE, "深层红石矿石");
        b(Blocks.WHEAT, "小麦");
        b(Blocks.CARROTS, "胡萝卜");
        b(Blocks.POTATOES, "土豆");
        b(Blocks.SWEET_BERRY_BUSH, "甜浆果丛");
        b(Blocks.MELON, "西瓜");
    }
}
