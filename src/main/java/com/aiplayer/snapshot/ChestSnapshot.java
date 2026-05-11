package com.aiplayer.snapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class ChestSnapshot {
    private final int[] position;
    private final boolean reachable;
    private final List<InventorySnapshot> items;

    private ChestSnapshot(BlockPos pos, boolean reachable, List<InventorySnapshot> items) {
        this.position = new int[] {pos.getX(), pos.getY(), pos.getZ()};
        this.reachable = reachable;
        this.items = List.copyOf(items);
    }

    public static ChestSnapshot fromContainer(BlockPos pos, Container container, boolean reachable) {
        List<InventorySnapshot> items = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                items.add(InventorySnapshot.fromStack(slot, stack));
            }
        }
        return new ChestSnapshot(pos, reachable, items);
    }

    public int[] getPosition() {
        return position;
    }

    public boolean isReachable() {
        return reachable;
    }

    public List<InventorySnapshot> getItems() {
        return items;
    }
}
