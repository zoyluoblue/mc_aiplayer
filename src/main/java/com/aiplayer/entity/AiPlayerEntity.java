package com.aiplayer.entity;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.ActionExecutor;
import com.aiplayer.action.ActionRecoveryState;
import com.aiplayer.execution.ResourceGatherSession;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
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
    private static final Item[] PICKAXES = {
        Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE,
        Items.WOODEN_PICKAXE, Items.GOLDEN_PICKAXE
    };
    private static final Item[] AXES = {
        Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE
    };
    private static final Item[] SHOVELS = {
        Items.NETHERITE_SHOVEL, Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL, Items.STONE_SHOVEL, Items.WOODEN_SHOVEL
    };
    private static final Item[] WEAPONS = {
        Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD,
        Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE
    };
    private static final int MIN_USABLE_TOOL_DURABILITY = 2;
    private static final EntityDataAccessor<String> AI_PLAYER_NAME = 
        SynchedEntityData.defineId(AiPlayerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> OWNER_UUID =
        SynchedEntityData.defineId(AiPlayerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> BACKPACK_SNAPSHOT =
        SynchedEntityData.defineId(AiPlayerEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> WORK_ANIMATION_UNTIL_TICK =
        SynchedEntityData.defineId(AiPlayerEntity.class, EntityDataSerializers.INT);

    private String aiPlayerName;
    private UUID ownerUuid;
    private AiPlayerMemory memory;
    private ActionExecutor actionExecutor;
    private ActionRecoveryState pendingActionRecoveryState = ActionRecoveryState.empty();
    private String pendingActionRecoveryReason = "";
    private String lastAppliedActionRecoveryFingerprint = "";
    private final ResourceGatherSession resourceGatherSession = new ResourceGatherSession();
    private final NonNullList<ItemStack> backpack = NonNullList.withSize(BACKPACK_SIZE, ItemStack.EMPTY);
    private int workSwingCooldown;

    public AiPlayerEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.aiPlayerName = "AiPlayer";
        this.memory = new AiPlayerMemory(this);
        this.actionExecutor = new ActionExecutor(this);
        this.setCustomNameVisible(true);
        this.setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.32D)
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
        builder.define(WORK_ANIMATION_UNTIL_TICK, 0);
    }

    @Override
    public void tick() {
        super.tick();
        
        if (!this.level().isClientSide) {
            this.setInvulnerable(true);
            if (this.getHealth() < this.getMaxHealth()) {
                this.setHealth(this.getMaxHealth());
            }
            this.setAirSupply(this.getMaxAirSupply());
            this.clearFire();
            if (workSwingCooldown > 0) {
                workSwingCooldown--;
            }
            applyPendingActionRecovery();
            actionExecutor.tick();
        }
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return true;
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

    public void lookAtWorkTarget(Entity entity) {
        if (entity == null) {
            return;
        }
        lookAtWorkTarget(entity.getX(), entity.getEyeY(), entity.getZ());
    }

    public void swingWorkHand(InteractionHand hand) {
        if (!this.level().isClientSide) {
            this.entityData.set(WORK_ANIMATION_UNTIL_TICK, this.tickCount + 8);
        }
        if (workSwingCooldown > 0) {
            return;
        }
        this.swing(hand, true);
        workSwingCooldown = 3;
    }

    public boolean isWorkAnimating() {
        return this.tickCount <= this.entityData.get(WORK_ANIMATION_UNTIL_TICK);
    }

    public float getWorkAnimationProgress(float partialTick) {
        float phase = ((this.tickCount + partialTick) % 8.0F) / 8.0F;
        return Math.max(0.15F, phase);
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

    public ResourceGatherSession getResourceGatherSession() {
        return resourceGatherSession;
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

        CompoundTag resourceSessionTag = new CompoundTag();
        this.resourceGatherSession.saveToNBT(resourceSessionTag);
        tag.put("ResourceGatherSession", resourceSessionTag);
        
        ListTag inventoryTag = new ListTag();
        for (int slot = 0; slot < backpack.size(); slot++) {
            ItemStack stack = backpack.get(slot);
            if (!stack.isEmpty()) {
                CompoundTag stackTag = new CompoundTag();
                stackTag.putInt("Slot", slot);
                stackTag.putString("Item", itemKey(stack.getItem()));
                stackTag.putInt("Count", stack.getCount());
                if (stack.isDamageableItem()) {
                    stackTag.putInt("Damage", stack.getDamageValue());
                }
                inventoryTag.add(stackTag);
            }
        }
        tag.put("AiPlayerInventory", inventoryTag);
        this.actionExecutor.saveRecoveryState(tag);
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

        if (tag.contains("ResourceGatherSession")) {
            this.resourceGatherSession.loadFromNBT(tag.getCompound("ResourceGatherSession"));
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
                    ItemStack stack = new ItemStack(item, Math.min(count, item.getDefaultMaxStackSize()));
                    if (stack.isDamageableItem() && stackTag.contains("Damage")) {
                        stack.setDamageValue(Math.min(stackTag.getInt("Damage"), stack.getMaxDamage() - 1));
                    }
                    backpack.set(slot, stack);
                }
            }
        }
        syncBackpackData();
        queueActionRecoveryState(tag, "nbt_load");
    }

    public void queueActionRecoveryState(CompoundTag tag, String reason) {
        if (tag == null || !tag.contains("ActionRecovery")) {
            pendingActionRecoveryState = ActionRecoveryState.empty();
            pendingActionRecoveryReason = "";
            return;
        }
        ActionRecoveryState state = ActionRecoveryState.fromTag(tag.getCompound("ActionRecovery"));
        if (state.emptyState()) {
            pendingActionRecoveryState = ActionRecoveryState.empty();
            pendingActionRecoveryReason = "";
            return;
        }
        pendingActionRecoveryState = state;
        pendingActionRecoveryReason = reason == null || reason.isBlank() ? "recovery" : reason;
    }

    public boolean applyPendingActionRecovery() {
        if (pendingActionRecoveryState == null || pendingActionRecoveryState.emptyState()) {
            return false;
        }
        String fingerprint = pendingActionRecoveryState.fingerprint();
        String reason = pendingActionRecoveryReason == null || pendingActionRecoveryReason.isBlank()
            ? "recovery"
            : pendingActionRecoveryReason;
        if (fingerprint.equals(lastAppliedActionRecoveryFingerprint)) {
            pendingActionRecoveryState = ActionRecoveryState.empty();
            pendingActionRecoveryReason = "";
            return false;
        }
        boolean applied = actionExecutor.restoreRecoveryStateIfIdle(pendingActionRecoveryState, reason);
        if (applied) {
            lastAppliedActionRecoveryFingerprint = fingerprint;
        } else {
            AiPlayerMod.debug("action", "[taskId={}] AiPlayer '{}' discarded pending recovery after {} because current executor state is newer",
                pendingActionRecoveryState.activeTaskId(), getAiPlayerName(), reason);
        }
        pendingActionRecoveryState = ActionRecoveryState.empty();
        pendingActionRecoveryReason = "";
        return applied;
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

    public boolean hasBackpackSpaceFor(Item item) {
        return hasBackpackSpaceFor(item, 1);
    }

    public boolean hasBackpackSpaceFor(Item item, int count) {
        if (item == null || item == Items.AIR) {
            return true;
        }
        return availableBackpackSpaceFor(item) >= Math.max(1, count);
    }

    public int availableBackpackSpaceFor(Item item) {
        if (item == null || item == Items.AIR) {
            return Integer.MAX_VALUE;
        }
        int capacity = 0;
        for (ItemStack stack : backpack) {
            if (stack.isEmpty()) {
                capacity += item.getDefaultMaxStackSize();
                continue;
            }
            if (stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
                capacity += stack.getMaxStackSize() - stack.getCount();
            }
        }
        return capacity;
    }

    public boolean isBackpackFull() {
        for (ItemStack stack : backpack) {
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    public int emptyBackpackSlots() {
        int empty = 0;
        for (ItemStack stack : backpack) {
            if (stack.isEmpty()) {
                empty++;
            }
        }
        return empty;
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
        if (hasUsableToolFor("pickaxe")) {
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
        if (hasUsableToolFor("axe")) {
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
        if (!firstOwnedUsableStack(Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE).isEmpty()) {
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
        ItemStack stack = firstOwnedUsableStack(toolsFor(toolType));
        return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    public ItemStack getBestToolStackFor(String toolType, int requiredDurability) {
        ItemStack stack = firstOwnedUsableStack(Math.max(1, requiredDurability), toolsFor(toolType));
        return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    public boolean hasUsableToolFor(String toolType) {
        return !firstOwnedUsableStack(toolsFor(toolType)).isEmpty();
    }

    public boolean damageBestTool(String toolType, int amount) {
        return damageBestTool(toolType, amount, amount);
    }

    public boolean damageBestTool(String toolType, int amount, int requiredDurability) {
        if (amount <= 0) {
            return true;
        }
        int minimumDurability = Math.max(amount, requiredDurability);
        for (Item item : toolsFor(toolType)) {
            for (int slot = 0; slot < backpack.size(); slot++) {
                ItemStack stack = backpack.get(slot);
                if (!stack.isEmpty() && stack.is(item) && isUsableToolStack(stack, minimumDurability)) {
                    if (!stack.isDamageableItem()) {
                        return true;
                    }
                    stack.setDamageValue(stack.getDamageValue() + amount);
                    if (stack.getDamageValue() >= stack.getMaxDamage()) {
                        backpack.set(slot, ItemStack.EMPTY);
                    }
                    syncBackpackData();
                    return true;
                }
            }
        }
        return false;
    }

    public int getBestToolRemainingDurability(String toolType) {
        ItemStack stack = firstOwnedUsableStack(toolsFor(toolType));
        if (stack.isEmpty()) {
            return 0;
        }
        if (!stack.isDamageableItem()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    private ItemStack firstOwnedUsableStack(Item... items) {
        return firstOwnedUsableStack(MIN_USABLE_TOOL_DURABILITY, items);
    }

    private ItemStack firstOwnedUsableStack(int requiredDurability, Item... items) {
        for (Item item : items) {
            for (ItemStack stack : backpack) {
                if (!stack.isEmpty() && stack.is(item) && isUsableToolStack(stack, requiredDurability)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean isUsableToolStack(ItemStack stack, int requiredDurability) {
        if (stack.isEmpty()) {
            return false;
        }
        if (!stack.isDamageableItem()) {
            return true;
        }
        return stack.getMaxDamage() - stack.getDamageValue() >= requiredDurability;
    }

    private Item[] toolsFor(String toolType) {
        return switch (toolType) {
            case "pickaxe" -> PICKAXES;
            case "axe" -> AXES;
            case "shovel" -> SHOVELS;
            case "weapon", "sword" -> WEAPONS;
            default -> new Item[0];
        };
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
