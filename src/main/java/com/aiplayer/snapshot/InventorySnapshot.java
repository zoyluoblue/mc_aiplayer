package com.aiplayer.snapshot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class InventorySnapshot {
    private final int slot;
    private final String item;
    private final int count;
    private final int damage;
    private final int maxDamage;

    public InventorySnapshot(int slot, String item, int count, int damage, int maxDamage) {
        this.slot = slot;
        this.item = item;
        this.count = count;
        this.damage = damage;
        this.maxDamage = maxDamage;
    }

    public static InventorySnapshot fromStack(int slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new InventorySnapshot(slot, "minecraft:air", 0, 0, 0);
        }
        int maxDamage = stack.isDamageableItem() ? stack.getMaxDamage() : 0;
        int damage = stack.isDamageableItem() ? stack.getDamageValue() : 0;
        return new InventorySnapshot(slot, itemKey(stack.getItem()), stack.getCount(), damage, maxDamage);
    }

    public int getSlot() {
        return slot;
    }

    public String getItem() {
        return item;
    }

    public int getCount() {
        return count;
    }

    public int getDamage() {
        return damage;
    }

    public int getMaxDamage() {
        return maxDamage;
    }

    private static String itemKey(Item item) {
        if (item == null || item == Items.AIR) {
            return "minecraft:air";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "minecraft:air" : key.toString();
    }
}
