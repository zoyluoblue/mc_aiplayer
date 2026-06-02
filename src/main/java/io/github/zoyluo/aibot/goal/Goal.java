package io.github.zoyluo.aibot.goal;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

import java.util.Set;

public sealed interface Goal permits Goal.HaveItem, Goal.HavePickaxeTier, Goal.MineOre, Goal.HarvestCrop, Goal.Armor {
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
}
