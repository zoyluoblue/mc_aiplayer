package io.github.zoyluo.aibot.goal;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

import java.util.Set;

public sealed interface Goal permits Goal.HaveItem, Goal.HavePickaxeTier, Goal.MineOre {
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
}
