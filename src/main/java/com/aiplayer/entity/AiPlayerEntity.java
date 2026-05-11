package com.aiplayer.entity;

import com.aiplayer.action.ActionExecutor;
import com.aiplayer.memory.AiPlayerMemory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AiPlayerEntity extends PathfinderMob {
    private static final int BACKPACK_SIZE = 36;
    public static final int CLIENT_BACKPACK_SLOTS = 20;
    private static final Item[] WOODEN_PLANKS = {
        Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
        Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS
    };
    private static final Item[] WOODEN_LOGS = {
        Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG,
        Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG
    };
    private static final Item[] WOODEN_DOORS = {
        Items.OAK_DOOR, Items.SPRUCE_DOOR, Items.BIRCH_DOOR, Items.JUNGLE_DOOR,
        Items.ACACIA_DOOR, Items.DARK_OAK_DOOR, Items.MANGROVE_DOOR, Items.CHERRY_DOOR
    };
    private static final EntityDataAccessor<String> AI_PLAYER_NAME = 
        SynchedEntityData.defineId(AiPlayerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> OWNER_UUID =
        SynchedEntityData.defineId(AiPlayerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> BACKPACK_SNAPSHOT =
        SynchedEntityData.defineId(AiPlayerEntity.class, EntityDataSerializers.STRING);

    private String aiPlayerName;
    private UUID ownerUuid;
    private AiPlayerMemory memory;
    private ActionExecutor actionExecutor;
    private final NonNullList<ItemStack> backpack = NonNullList.withSize(BACKPACK_SIZE, ItemStack.EMPTY);
    private int workSwingCooldown;

    public AiPlayerEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.aiPlayerName = "AiPlayer";
        this.memory = new AiPlayerMemory(this);
        this.actionExecutor = new ActionExecutor(this);
        this.setCustomNameVisible(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.ATTACK_DAMAGE, 8.0D)
            .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(AI_PLAYER_NAME, "AiPlayer");
        builder.define(OWNER_UUID, "");
        builder.define(BACKPACK_SNAPSHOT, emptyBackpackSlots(CLIENT_BACKPACK_SLOTS));
    }

    @Override
    public void tick() {
        super.tick();
        
        if (!this.level().isClientSide) {
            if (workSwingCooldown > 0) {
                workSwingCooldown--;
            }
            actionExecutor.tick();
        }
    }

    public void lookAtWorkTarget(double x, double y, double z) {
        double dx = x - this.getX();
        double dy = y - this.getEyeY();
        double dz = z - this.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);
        this.getLookControl().setLookAt(x, y, z);
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.yBodyRot = yaw;
        this.setXRot(Mth.clamp(pitch, -75.0F, 75.0F));
    }

    public void lookAtWorkTarget(BlockPos pos) {
        lookAtWorkTarget(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    public void swingWorkHand(InteractionHand hand) {
        if (workSwingCooldown > 0) {
            return;
        }
        this.swing(hand, true);
        workSwingCooldown = 6;
    }

    public void setAiPlayerName(String name) {
        this.aiPlayerName = name;
        this.entityData.set(AI_PLAYER_NAME, name);
        this.setCustomName(Component.literal(name));
    }

    public String getAiPlayerName() {
        return this.entityData.get(AI_PLAYER_NAME);
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
        this.entityData.set(OWNER_UUID, ownerUuid == null ? "" : ownerUuid.toString());
    }

    public UUID getOwnerUuid() {
        if (ownerUuid != null) {
            return ownerUuid;
        }
        String syncedOwner = this.entityData.get(OWNER_UUID);
        if (syncedOwner == null || syncedOwner.isBlank()) {
            return null;
        }
        try {
            ownerUuid = UUID.fromString(syncedOwner);
            return ownerUuid;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isOwnedBy(UUID uuid) {
        UUID currentOwner = getOwnerUuid();
        return currentOwner != null && currentOwner.equals(uuid);
    }

    public String getClientBackpackSnapshot() {
        return this.entityData.get(BACKPACK_SNAPSHOT);
    }

    public AiPlayerMemory getMemory() {
        return this.memory;
    }

    public ActionExecutor getActionExecutor() {
        return this.actionExecutor;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("AiPlayerName", this.aiPlayerName);
        if (this.ownerUuid != null) {
            tag.putUUID("OwnerUuid", this.ownerUuid);
        }
        
        CompoundTag memoryTag = new CompoundTag();
        this.memory.saveToNBT(memoryTag);
        tag.put("Memory", memoryTag);
        
        ListTag inventoryTag = new ListTag();
        for (int slot = 0; slot < backpack.size(); slot++) {
            ItemStack stack = backpack.get(slot);
            if (!stack.isEmpty()) {
                CompoundTag stackTag = new CompoundTag();
                stackTag.putInt("Slot", slot);
                stackTag.putString("Item", itemKey(stack.getItem()));
                stackTag.putInt("Count", stack.getCount());
                inventoryTag.add(stackTag);
            }
        }
        tag.put("AiPlayerInventory", inventoryTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("AiPlayerName")) {
            this.setAiPlayerName(tag.getString("AiPlayerName"));
        }
        if (tag.hasUUID("OwnerUuid")) {
            this.setOwnerUuid(tag.getUUID("OwnerUuid"));
        }
        
        if (tag.contains("Memory")) {
            this.memory.loadFromNBT(tag.getCompound("Memory"));
        }
        
        clearBackpack();
        if (tag.contains("AiPlayerInventory")) {
            ListTag inventoryTag = tag.getList("AiPlayerInventory", 10);
            for (int i = 0; i < inventoryTag.size(); i++) {
                CompoundTag stackTag = inventoryTag.getCompound(i);
                int slot = stackTag.getInt("Slot");
                Item item = itemFromKey(stackTag.getString("Item"));
                int count = stackTag.getInt("Count");
                if (slot >= 0 && slot < backpack.size() && item != Items.AIR && count > 0) {
                    backpack.set(slot, new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize())));
                }
            }
        }
        syncBackpackData();
    }

    public void sendChatMessage(String message) {
        if (this.level().isClientSide) return;
        
        Component chatComponent = Component.literal("<" + this.aiPlayerName + "> " + message);
        this.level().players().forEach(player -> {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(chatComponent);
            } else {
                player.displayClientMessage(chatComponent, false);
            }
        });
    }

    public int getItemCount(Item item) {
        int count = 0;
        for (ItemStack stack : backpack) {
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public boolean hasItem(Item item, int count) {
        return getItemCount(item) >= count;
    }

    public int addItem(Item item, int count) {
        if (item == null || item == Items.AIR || count <= 0) {
            return 0;
        }
        int remaining = count;
        int maxStackSize = item.getDefaultMaxStackSize();
        for (int slot = 0; slot < backpack.size() && remaining > 0; slot++) {
            ItemStack stack = backpack.get(slot);
            if (!stack.isEmpty() && stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
                int moved = Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
                stack.grow(moved);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < backpack.size() && remaining > 0; slot++) {
            if (backpack.get(slot).isEmpty()) {
                int moved = Math.min(remaining, maxStackSize);
                backpack.set(slot, new ItemStack(item, moved));
                remaining -= moved;
            }
        }
        int inserted = count - remaining;
        if (inserted > 0) {
            syncBackpackData();
        }
        return inserted;
    }

    public boolean consumeItem(Item item, int count) {
        if (item == null || count <= 0) {
            return false;
        }
        if (getItemCount(item) < count) {
            return false;
        }
        int remaining = count;
        for (int slot = 0; slot < backpack.size() && remaining > 0; slot++) {
            ItemStack stack = backpack.get(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                int taken = Math.min(remaining, stack.getCount());
                stack.shrink(taken);
                remaining -= taken;
                if (stack.isEmpty()) {
                    backpack.set(slot, ItemStack.EMPTY);
                }
            }
        }
        syncBackpackData();
        return true;
    }

    public Map<String, Integer> getInventorySnapshot() {
        Map<String, Integer> snapshot = new HashMap<>();
        for (ItemStack stack : backpack) {
            if (!stack.isEmpty()) {
                snapshot.merge(itemKey(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        return Map.copyOf(snapshot);
    }

    public List<ItemStack> getBackpackItems() {
        List<ItemStack> items = new ArrayList<>(backpack.size());
        for (ItemStack stack : backpack) {
            items.add(stack.copy());
        }
        return List.copyOf(items);
    }

    public int getBackpackSize() {
        return backpack.size();
    }

    public ItemStack getBackpackStack(int slot) {
        if (slot < 0 || slot >= backpack.size()) {
            return ItemStack.EMPTY;
        }
        return backpack.get(slot).copy();
    }

    public int addItemStack(ItemStack source) {
        if (source == null || source.isEmpty()) {
            return 0;
        }
        ItemStack moving = source.copy();
        int originalCount = moving.getCount();
        for (int slot = 0; slot < backpack.size() && !moving.isEmpty(); slot++) {
            ItemStack stack = backpack.get(slot);
            if (!stack.isEmpty() && canMerge(stack, moving)) {
                int moved = Math.min(moving.getCount(), stack.getMaxStackSize() - stack.getCount());
                if (moved > 0) {
                    stack.grow(moved);
                    moving.shrink(moved);
                }
            }
        }
        for (int slot = 0; slot < backpack.size() && !moving.isEmpty(); slot++) {
            if (backpack.get(slot).isEmpty()) {
                int moved = Math.min(moving.getCount(), moving.getMaxStackSize());
                backpack.set(slot, moving.copyWithCount(moved));
                moving.shrink(moved);
            }
        }
        int inserted = originalCount - moving.getCount();
        if (inserted > 0) {
            syncBackpackData();
        }
        return inserted;
    }

    public int insertItemStackIntoSlot(int slot, ItemStack source) {
        if (slot < 0 || slot >= backpack.size() || source == null || source.isEmpty()) {
            return 0;
        }
        ItemStack stack = backpack.get(slot);
        int moved = 0;
        if (stack.isEmpty()) {
            moved = Math.min(source.getCount(), source.getMaxStackSize());
            backpack.set(slot, source.copyWithCount(moved));
        } else if (canMerge(stack, source)) {
            moved = Math.min(source.getCount(), stack.getMaxStackSize() - stack.getCount());
            if (moved > 0) {
                stack.grow(moved);
            }
        }
        if (moved > 0) {
            syncBackpackData();
        }
        return moved;
    }

    public List<ItemStack> removeItemStacks(Item item, int count) {
        List<ItemStack> removed = new ArrayList<>();
        if (item == null || item == Items.AIR || count <= 0) {
            return List.of();
        }
        int remaining = count;
        for (int slot = 0; slot < backpack.size() && remaining > 0; slot++) {
            ItemStack stack = backpack.get(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                int moved = Math.min(remaining, stack.getCount());
                removed.add(stack.split(moved));
                remaining -= moved;
                if (stack.isEmpty()) {
                    backpack.set(slot, ItemStack.EMPTY);
                }
            }
        }
        if (!removed.isEmpty()) {
            syncBackpackData();
        }
        return List.copyOf(removed);
    }

    public ItemStack removeItemFromBackpackSlot(int slot, int count) {
        if (slot < 0 || slot >= backpack.size() || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = backpack.get(slot);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = stack.split(Math.min(count, stack.getCount()));
        if (stack.isEmpty()) {
            backpack.set(slot, ItemStack.EMPTY);
        }
        if (!removed.isEmpty()) {
            syncBackpackData();
        }
        return removed;
    }

    public int getTotalInventoryCount(Item... items) {
        int count = 0;
        for (Item item : items) {
            count += getItemCount(item);
        }
        return count;
    }

    public boolean craftPlanksFromLogs(int targetPlanks) {
        if (getTotalInventoryCount(WOODEN_PLANKS) >= targetPlanks) {
            return true;
        }
        for (int i = 0; i < WOODEN_LOGS.length; i++) {
            Item log = WOODEN_LOGS[i];
            Item plank = WOODEN_PLANKS[i];
            while (getItemCount(log) > 0 && getTotalInventoryCount(WOODEN_PLANKS) < targetPlanks) {
                consumeItem(log, 1);
                addItem(plank, 4);
            }
            if (getTotalInventoryCount(WOODEN_PLANKS) >= targetPlanks) {
                return true;
            }
        }
        return getTotalInventoryCount(WOODEN_PLANKS) >= targetPlanks;
    }

    public int getWoodenPlankCount() {
        return getTotalInventoryCount(WOODEN_PLANKS);
    }

    public int getWoodenDoorCount() {
        return getTotalInventoryCount(WOODEN_DOORS);
    }

    public boolean craftWoodenDoor(int targetDoors) {
        if (getWoodenDoorCount() >= targetDoors) {
            return true;
        }
        while (getWoodenDoorCount() < targetDoors) {
            int plankIndex = firstOwnedIndexWithCount(WOODEN_PLANKS, 6);
            if (plankIndex < 0) {
                craftPlanksFromLogs(requiredPlanksForDoor(targetDoors));
                plankIndex = firstOwnedIndexWithCount(WOODEN_PLANKS, 6);
            }
            if (plankIndex < 0 || !consumeItem(WOODEN_PLANKS[plankIndex], 6)) {
                return false;
            }
            addItem(WOODEN_DOORS[plankIndex], 3);
        }
        return true;
    }

    public boolean craftSticks(int targetSticks) {
        if (getItemCount(Items.STICK) >= targetSticks) {
            return true;
        }
        craftPlanksFromLogs(2);
        while (getItemCount(Items.STICK) < targetSticks && consumeAnyPlanks(2)) {
            addItem(Items.STICK, 4);
        }
        return getItemCount(Items.STICK) >= targetSticks;
    }

    public boolean craftWoodenPickaxe() {
        if (hasItem(Items.WOODEN_PICKAXE, 1)) {
            return true;
        }
        craftPlanksFromLogs(3);
        craftSticks(2);
        if (consumeAnyPlanks(3) && consumeItem(Items.STICK, 2)) {
            addItem(Items.WOODEN_PICKAXE, 1);
            return true;
        }
        return false;
    }

    public boolean craftWoodenAxe() {
        if (hasItem(Items.WOODEN_AXE, 1)) {
            return true;
        }
        craftPlanksFromLogs(3);
        craftSticks(2);
        if (consumeAnyPlanks(3) && consumeItem(Items.STICK, 2)) {
            addItem(Items.WOODEN_AXE, 1);
            return true;
        }
        return false;
    }

    public boolean craftStonePickaxe() {
        if (hasItem(Items.STONE_PICKAXE, 1)) {
            return true;
        }
        craftSticks(2);
        if (consumeItem(Items.COBBLESTONE, 3) && consumeItem(Items.STICK, 2)) {
            addItem(Items.STONE_PICKAXE, 1);
            return true;
        }
        return false;
    }

    public ItemStack getBestToolStackFor(String toolType) {
        Item item = switch (toolType) {
            case "pickaxe" -> firstOwned(Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE);
            case "axe" -> firstOwned(Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE);
            default -> Items.AIR;
        };
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private Item firstOwned(Item... items) {
        for (Item item : items) {
            if (hasItem(item, 1)) {
                return item;
            }
        }
        return Items.AIR;
    }

    private boolean canMerge(ItemStack existing, ItemStack incoming) {
        return ItemStack.isSameItemSameComponents(existing, incoming) && existing.getCount() < existing.getMaxStackSize();
    }

    private int firstOwnedIndexWithCount(Item[] items, int count) {
        for (int i = 0; i < items.length; i++) {
            if (hasItem(items[i], count)) {
                return i;
            }
        }
        return -1;
    }

    private boolean consumeAnyPlanks(int count) {
        int remaining = Math.max(0, count);
        if (getTotalInventoryCount(WOODEN_PLANKS) < remaining) {
            return false;
        }
        for (Item plank : WOODEN_PLANKS) {
            int taken = Math.min(getItemCount(plank), remaining);
            if (taken > 0) {
                consumeItem(plank, taken);
                remaining -= taken;
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
    }

    private int requiredPlanksForDoor(int targetDoors) {
        int missingDoors = Math.max(0, targetDoors - getWoodenDoorCount());
        int craftBatches = Math.max(1, (missingDoors + 2) / 3);
        return craftBatches * 6;
    }

    private String itemKey(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "minecraft:air" : key.toString();
    }

    private Item itemFromKey(String key) {
        if (key == null || key.isBlank()) {
            return Items.AIR;
        }
        Item item = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(key));
        return item == null ? Items.AIR : item;
    }

    private void clearBackpack() {
        for (int slot = 0; slot < backpack.size(); slot++) {
            backpack.set(slot, ItemStack.EMPTY);
        }
        syncBackpackData();
    }

    private void syncBackpackData() {
        this.entityData.set(BACKPACK_SNAPSHOT, encodeBackpackSlots(CLIENT_BACKPACK_SLOTS));
    }

    private String encodeBackpackSlots(int limit) {
        StringBuilder builder = new StringBuilder();
        int slots = Math.min(limit, backpack.size());
        for (int slot = 0; slot < slots; slot++) {
            if (slot > 0) {
                builder.append("|");
            }
            ItemStack stack = backpack.get(slot);
            if (stack.isEmpty()) {
                builder.append("minecraft:air,0");
            } else {
                builder.append(itemKey(stack.getItem())).append(",").append(stack.getCount());
            }
        }
        return builder.toString();
    }

    private static String emptyBackpackSlots(int slots) {
        StringBuilder builder = new StringBuilder();
        for (int slot = 0; slot < slots; slot++) {
            if (slot > 0) {
                builder.append("|");
            }
            builder.append("minecraft:air,0");
        }
        return builder.toString();
    }
}
