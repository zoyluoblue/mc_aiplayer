package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;

public final class EquipAction {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    private EquipAction() {
    }

    public static int equipBestArmor(AIPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        Map<EquipmentSlot, Candidate> best = new EnumMap<>(EquipmentSlot.class);
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            EquipmentSlot equipmentSlot = bot.getPreferredEquipmentSlot(stack);
            if (!isArmorSlot(equipmentSlot)) {
                continue;
            }
            double score = armorScore(stack, equipmentSlot);
            if (score <= equippedArmorScore(bot, equipmentSlot)) {
                continue;
            }
            Candidate current = best.get(equipmentSlot);
            if (current == null || score > current.score()) {
                best.put(equipmentSlot, new Candidate(slot, stack.copy(), score));
            }
        }
        int equipped = 0;
        for (Map.Entry<EquipmentSlot, Candidate> entry : best.entrySet()) {
            EquipmentSlot slot = entry.getKey();
            Candidate candidate = entry.getValue();
            ItemStack old = bot.getEquippedStack(slot).copy();
            inventory.main.set(candidate.sourceSlot(), old);
            bot.equipStack(slot, candidate.stack());
            inventory.markDirty();
            equipped++;
            BotLog.action(bot, "equip_armor", "slot", slot.asString(), "item", candidate.stack().getItem(), "score", candidate.score());
        }
        return equipped;
    }

    public static OptionalInt equipBestWeapon(AIPlayerEntity bot) {
        OptionalInt slot = bestWeaponSlot(bot);
        slot.ifPresent(value -> InventoryAction.equipFromSlot(bot, value));
        return slot;
    }

    public static OptionalInt bestWeaponSlot(AIPlayerEntity bot) {
        PlayerInventory inventory = bot.getInventory();
        int bestSlot = -1;
        double bestDamage = 1.0D;
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            double damage = attackDamage(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }
        return bestSlot < 0 ? OptionalInt.empty() : OptionalInt.of(bestSlot);
    }

    public static OptionalInt bestRangedSlot(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, Items.ARROW) <= 0) {
            return OptionalInt.empty();
        }
        PlayerInventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            if (inventory.main.get(slot).isOf(Items.BOW)) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public static boolean equipShieldOffhand(AIPlayerEntity bot) {
        if (bot.getOffHandStack().isOf(Items.SHIELD)) {
            return true;
        }
        PlayerInventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (!stack.isOf(Items.SHIELD)) {
                continue;
            }
            ItemStack oldOffhand = bot.getOffHandStack().copy();
            bot.equipStack(EquipmentSlot.OFFHAND, stack.copy());
            inventory.main.set(slot, oldOffhand);
            inventory.markDirty();
            BotLog.action(bot, "equip_shield_offhand", "source_slot", slot);
            return true;
        }
        return false;
    }

    public static boolean equipTotemOffhand(AIPlayerEntity bot) {
        if (bot.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return true;
        }
        PlayerInventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (!stack.isOf(Items.TOTEM_OF_UNDYING)) {
                continue;
            }
            ItemStack oldOffhand = bot.getOffHandStack().copy();
            bot.equipStack(EquipmentSlot.OFFHAND, stack.copy());
            inventory.main.set(slot, oldOffhand);
            inventory.markDirty();
            BotLog.action(bot, "equip_totem_offhand", "source_slot", slot);
            return true;
        }
        return false;
    }

    /** Экипировать лучший оффхенд: тотем > щит */
    public static boolean equipBestOffhand(AIPlayerEntity bot) {
        if (equipTotemOffhand(bot)) return true;
        return equipShieldOffhand(bot);
    }


    public static double attackDamage(ItemStack stack) {
        return attributeValue(stack, EquipmentSlot.MAINHAND, EntityAttributes.ATTACK_DAMAGE);
    }

    private static double equippedArmorScore(AIPlayerEntity bot, EquipmentSlot slot) {
        return armorScore(bot.getEquippedStack(slot), slot);
    }

    private static double armorScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double armor = attributeValue(stack, slot, EntityAttributes.ARMOR);
        double toughness = attributeValue(stack, slot, EntityAttributes.ARMOR_TOUGHNESS);
        return armor + toughness * 0.25D;
    }

    private static double attributeValue(ItemStack stack,
                                         EquipmentSlot slot,
                                         RegistryEntry<EntityAttribute> attribute) {
        double[] value = {0.0D};
        stack.applyAttributeModifiers(slot, (entry, modifier) -> {
            if (entry.equals(attribute) && modifier.operation() == EntityAttributeModifier.Operation.ADD_VALUE) {
                value[0] += modifier.value();
            }
        });
        return value[0];
    }

    private static boolean isArmorSlot(EquipmentSlot slot) {
        for (EquipmentSlot armorSlot : ARMOR_SLOTS) {
            if (slot == armorSlot) {
                return true;
            }
        }
        return false;
    }

    private record Candidate(int sourceSlot, ItemStack stack, double score) {
    }
}

