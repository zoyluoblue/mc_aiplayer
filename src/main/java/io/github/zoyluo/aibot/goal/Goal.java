package io.github.zoyluo.aibot.goal;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

import java.util.Set;

public sealed interface Goal permits Goal.HaveItem, Goal.HavePickaxeTier, Goal.MineOre, Goal.HarvestCrop, Goal.Armor, Goal.Workstation, Goal.Stockpile, Goal.Food {
    record HaveItem(Item item, int count) implements Goal {
        public HaveItem {
            count = Math.max(1, count);
        }
    }

    record HavePickaxeTier(int tier) implements Goal {
        public HavePickaxeTier {
            tier = Math.max(0, tier);
        }
    }

    record MineOre(Set<Block> ores, int count) implements Goal {
        public MineOre {
            ores = ores == null ? Set.of() : Set.copyOf(ores);
            count = Math.max(1, count);
        }
    }

    /** P3:收获 N 个作物(小麦/胡萝卜/土豆)。倒推:有锄头(+种子)→ 开垦/播种/等熟/收割。 */
    record HarvestCrop(Block crop, Item seed, Item produce, int count) implements Goal {
        public HarvestCrop {
            count = Math.max(1, count);
        }
    }

    /** Phase1:武装起来——成套护甲 + 剑(目前为铁质,复用 GoalPlanner.ensureArmor 倒推)。 */
    record Armor() implements Goal {
    }

    /** Phase2:基建——备齐并摆好工作台/熔炉/箱子三件套(生产+存储据点)。 */
    record Workstation() implements Goal {
    }

    /** Phase3:囤货——获取 count 个 item,并(尽力)存进附近箱子。 */
    record Stockpile(Item item, int count) implements Goal {
        public Stockpile {
            count = Math.max(1, count);
        }
    }

    /** 第4层 备粮:猎肉并烤成 cookedCount 个熟食(走 GoalPlanner 的猎→烤闭环)。
     *  供"去打猎/去搞点吃的/弄点肉"等口语入口(provision_food 工具)。 */
    record Food(int cookedCount) implements Goal {
        public Food {
            cookedCount = Math.max(1, cookedCount);
        }
    }
}
