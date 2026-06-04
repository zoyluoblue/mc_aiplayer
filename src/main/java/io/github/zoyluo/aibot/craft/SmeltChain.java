package io.github.zoyluo.aibot.craft;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S5:冶炼链单一数据源——输入物 → 冶炼产物(矿锭/熟肉/玻璃/木炭/烤土豆),以及燃料燃烧时长。
 *
 * 此前冶炼映射散落在 `GoalPlanner.smeltRecipeFor`(仅矿锭/石/木炭),食物链(熟肉)无从倒推。本表为
 * 材料链(A)与食物链(B)共用:`GoalPlanner` 倒推冶炼产物、`SmeltTask` 校验"可冶炼"都读这里。
 */
public final class SmeltChain {

    // 输入 → 产物。顺序仅影响 rawFor 反查的优先级(各产物唯一,无歧义)。
    private static final Map<Item, Item> SMELT = new LinkedHashMap<>();
    // 燃料 → 可烧物品数(vanilla:煤/炭 8、原木/木板 1.5、木棍 0.5;此处用"可烧个数"近似,规划层按需向上取整)。
    private static final Map<Item, Double> FUEL = new LinkedHashMap<>();

    static {
        // 矿物冶炼
        SMELT.put(Items.RAW_IRON, Items.IRON_INGOT);
        SMELT.put(Items.RAW_COPPER, Items.COPPER_INGOT);
        SMELT.put(Items.RAW_GOLD, Items.GOLD_INGOT);
        SMELT.put(Items.COBBLESTONE, Items.STONE);
        SMELT.put(Items.OAK_LOG, Items.CHARCOAL); // 任意原木皆可,规划默认橡木
        // 食物冶炼(B 模块用:熟肉饱食/饱和远高于生肉)
        SMELT.put(Items.BEEF, Items.COOKED_BEEF);
        SMELT.put(Items.PORKCHOP, Items.COOKED_PORKCHOP);
        SMELT.put(Items.CHICKEN, Items.COOKED_CHICKEN);
        SMELT.put(Items.MUTTON, Items.COOKED_MUTTON);
        SMELT.put(Items.RABBIT, Items.COOKED_RABBIT);
        SMELT.put(Items.COD, Items.COOKED_COD);
        SMELT.put(Items.SALMON, Items.COOKED_SALMON);
        SMELT.put(Items.POTATO, Items.BAKED_POTATO);
        // 其它
        SMELT.put(Items.SAND, Items.GLASS);

        FUEL.put(Items.COAL, 8.0);
        FUEL.put(Items.CHARCOAL, 8.0);
        FUEL.put(Items.OAK_LOG, 1.5);
        FUEL.put(Items.OAK_PLANKS, 1.5);
        FUEL.put(Items.STICK, 0.5);
    }

    private SmeltChain() {
    }

    /** 输入物的冶炼产物;不可冶炼返回 null。 */
    public static Item smeltOf(Item input) {
        return SMELT.get(input);
    }

    /** 该输入物是否可冶炼(SmeltTask 入参校验用)。 */
    public static boolean isSmeltable(Item input) {
        return SMELT.containsKey(input);
    }

    /** 反查:要得到 output 这个冶炼产物,需要的输入物;无则 null(供 GoalPlanner 倒推)。 */
    public static Item rawFor(Item output) {
        for (Map.Entry<Item, Item> e : SMELT.entrySet()) {
            if (e.getValue() == output) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 该燃料能烧多少个物品(0=非燃料)。 */
    public static double burnYield(Item fuel) {
        return FUEL.getOrDefault(fuel, 0.0);
    }

    public static boolean isFuel(Item item) {
        return FUEL.containsKey(item);
    }
}
