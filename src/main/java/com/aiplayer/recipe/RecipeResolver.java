package com.aiplayer.recipe;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.snapshot.WorldSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RecipeResolver {
    private final BasicSourceResolver sourceResolver = new BasicSourceResolver();

    public RecipePlan resolve(AiPlayerEntity aiPlayer, WorldSnapshot snapshot, String targetItem, int count) {
        String normalizedTarget = normalizeTargetItem(targetItem, snapshot);
        MaterialRequirement target = MaterialRequirement.of(normalizedTarget, count);
        MissingMaterialResolver materials = MissingMaterialResolver.fromSnapshot(snapshot);
        Map<String, Integer> initialAvailable = materials.availableItems();

        if (!isKnownItem(normalizedTarget)) {
            return RecipePlan.failure(target, "未知物品 ID：" + normalizedTarget, initialAvailable);
        }

        List<RecipeNode> chain = new ArrayList<>();
        List<MaterialRequirement> missingBaseResources = new ArrayList<>();
        ResolveResult result = satisfyTopLevel(aiPlayer, snapshot, normalizedTarget, Math.max(1, count), materials, chain, missingBaseResources);
        if (!result.success()) {
            return RecipePlan.failure(target, result.message(), initialAvailable);
        }
        return RecipePlan.success(target, chain, missingBaseResources, initialAvailable);
    }

    public String normalizeTargetItem(String targetItem, WorldSnapshot snapshot) {
        String candidate = isGenericWoodenDoor(targetItem) ? chooseWoodenDoorTarget(snapshot) : targetItem;
        return MiningGoalResolver.resolve(candidate)
            .map(MiningGoalResolver.Goal::finalItem)
            .orElseGet(() -> normalizeItemId(candidate));
    }

    private ResolveResult satisfyTopLevel(
        AiPlayerEntity aiPlayer,
        WorldSnapshot snapshot,
        String item,
        int count,
        MissingMaterialResolver materials,
        List<RecipeNode> chain,
        List<MaterialRequirement> missingBaseResources
    ) {
        int neededAfterBackpack = Math.max(0, count - materials.getBackpackCount(item));
        MissingMaterialResolver.TakeResult movedFromChest = materials.withdrawToBackpackDetailed(item, neededAfterBackpack);
        addWithdrawNodes(chain, movedFromChest.fromChestItems());

        int missing = Math.max(0, count - materials.getBackpackCount(item));
        if (missing <= 0) {
            return ResolveResult.ok();
        }
        return produceMissing(aiPlayer, snapshot, item, missing, false, materials, chain, missingBaseResources, new HashSet<>());
    }

    private ResolveResult satisfyIngredient(
        AiPlayerEntity aiPlayer,
        WorldSnapshot snapshot,
        String item,
        int count,
        MissingMaterialResolver materials,
        List<RecipeNode> chain,
        List<MaterialRequirement> missingBaseResources,
        Set<String> stack
    ) {
        MissingMaterialResolver.TakeResult existing = materials.takeForIngredient(item, count);
        addWithdrawNodes(chain, existing.fromChestItems());
        if (existing.missing() <= 0) {
            return ResolveResult.ok();
        }
        return produceMissing(aiPlayer, snapshot, item, existing.missing(), true, materials, chain, missingBaseResources, stack);
    }

    private ResolveResult produceMissing(
        AiPlayerEntity aiPlayer,
        WorldSnapshot snapshot,
        String item,
        int missing,
        boolean consumeResult,
        MissingMaterialResolver materials,
        List<RecipeNode> chain,
        List<MaterialRequirement> missingBaseResources,
        Set<String> stack
    ) {
        if (!stack.add(item)) {
            return ResolveResult.failure("配方递归出现循环：" + item);
        }

        if (shouldPreferBaseSource(item)) {
            ResolveResult gatherResult = produceFromBaseResource(aiPlayer, snapshot, item, missing, consumeResult, materials, chain, missingBaseResources, stack);
            stack.remove(item);
            return gatherResult;
        }

        List<RecipeDefinition> recipes = supportedRecipes(aiPlayer, item);
        if (recipes.isEmpty()) {
            ResolveResult gatherResult = produceFromBaseResource(aiPlayer, snapshot, item, missing, consumeResult, materials, chain, missingBaseResources, stack);
            stack.remove(item);
            return gatherResult;
        }

        String lastFailure = "没有找到可完成的原版配方：" + item;
        for (RecipeDefinition definition : recipes) {
            MissingMaterialResolver trialMaterials = materials.copy();
            List<RecipeNode> trialChain = new ArrayList<>(chain);
            List<MaterialRequirement> trialMissingBaseResources = new ArrayList<>(missingBaseResources);
            ResolveResult result = produceFromRecipe(
                aiPlayer,
                snapshot,
                item,
                missing,
                consumeResult,
                definition,
                trialMaterials,
                trialChain,
                trialMissingBaseResources,
                new HashSet<>(stack)
            );
            if (result.success()) {
                materials.replaceWith(trialMaterials);
                chain.clear();
                chain.addAll(trialChain);
                missingBaseResources.clear();
                missingBaseResources.addAll(trialMissingBaseResources);
                stack.remove(item);
                return ResolveResult.ok();
            }
            lastFailure = result.message();
        }
        ResolveResult gatherResult = produceFromBaseResource(aiPlayer, snapshot, item, missing, consumeResult, materials, chain, missingBaseResources, stack);
        if (gatherResult.success()) {
            stack.remove(item);
            return gatherResult;
        }
        stack.remove(item);
        return ResolveResult.failure(lastFailure);
    }

    private ResolveResult produceFromBaseResource(
        AiPlayerEntity aiPlayer,
        WorldSnapshot snapshot,
        String item,
        int missing,
        boolean consumeResult,
        MissingMaterialResolver materials,
        List<RecipeNode> chain,
        List<MaterialRequirement> missingBaseResources,
        Set<String> stack
    ) {
        String tool = requiredToolForBaseResource(item);
        if (tool != null) {
            ResolveResult toolResult = ensureNonConsumableItem(aiPlayer, snapshot, tool, materials, chain, missingBaseResources, stack);
            if (!toolResult.success()) {
                return toolResult;
            }
        }
        Optional<RecipeNode> gatherNode = sourceResolver.gatherNode(item, missing);
        if (gatherNode.isEmpty()) {
            return ResolveResult.failure("没有找到可用配方或基础资源来源：" + item);
        }
        RecipeNode node = gatherNode.get();
        if (SurvivalRecipeBook.isGenericBlockSource(item, node.getSource()) && !isReachableBlockInSnapshot(snapshot, item)) {
            return ResolveResult.failure("附近没有可访问的基础方块来源：" + item);
        }
        chain.add(node);
        missingBaseResources.add(MaterialRequirement.of(item, missing));
        materials.addBackpack(item, missing);
        if (consumeResult) {
            materials.consumeBackpack(item, missing);
        }
        return ResolveResult.ok();
    }

    private ResolveResult produceFromRecipe(
        AiPlayerEntity aiPlayer,
        WorldSnapshot snapshot,
        String item,
        int missing,
        boolean consumeResult,
        RecipeDefinition definition,
        MissingMaterialResolver materials,
        List<RecipeNode> chain,
        List<MaterialRequirement> missingBaseResources,
        Set<String> stack
    ) {
        int batches = Math.max(1, (missing + definition.output().getCount() - 1) / definition.output().getCount());
        List<MaterialRequirement> batchedRequirements = new ArrayList<>();
        for (MaterialRequirement requirement : definition.requires()) {
            MaterialRequirement batched = MaterialRequirement.of(requirement.getItem(), requirement.getCount() * batches);
            batchedRequirements.add(batched);
            ResolveResult requirementResult = satisfyIngredient(aiPlayer, snapshot, batched.getItem(), batched.getCount(), materials, chain, missingBaseResources, stack);
            if (!requirementResult.success()) {
                return requirementResult;
            }
        }

        if (definition.stationItem() != null) {
            ResolveResult stationResult = ensureNonConsumableItem(
                aiPlayer,
                snapshot,
                definition.stationItem(),
                materials,
                chain,
                missingBaseResources,
                stack
            );
            if (!stationResult.success()) {
                return stationResult;
            }
        }

        int produced = definition.output().getCount() * batches;
        chain.add(RecipeNode.craft(MaterialRequirement.of(item, produced), batchedRequirements, definition.station(), definition.note()));
        materials.addBackpack(item, produced);
        if (consumeResult) {
            materials.consumeBackpack(item, missing);
        }
        return ResolveResult.ok();
    }

    private ResolveResult ensureNonConsumableItem(
        AiPlayerEntity aiPlayer,
        WorldSnapshot snapshot,
        String item,
        MissingMaterialResolver materials,
        List<RecipeNode> chain,
        List<MaterialRequirement> missingBaseResources,
        Set<String> stack
    ) {
        if (materials.getBackpackCount(item) > 0) {
            return ResolveResult.ok();
        }
        MissingMaterialResolver.TakeResult movedFromChest = materials.withdrawToBackpackDetailed(item, 1);
        if (movedFromChest.fromChest() > 0) {
            addWithdrawNodes(chain, movedFromChest.fromChestItems());
            return ResolveResult.ok();
        }
        return produceMissing(aiPlayer, snapshot, item, 1, false, materials, chain, missingBaseResources, stack);
    }

    private void addWithdrawNodes(List<RecipeNode> chain, Map<String, Integer> items) {
        items.forEach((item, count) -> chain.add(RecipeNode.withdraw(MaterialRequirement.of(item, count), "nearest_chest")));
    }

    private List<RecipeDefinition> supportedRecipes(AiPlayerEntity aiPlayer, String item) {
        return SurvivalRecipeBook.findAll(aiPlayer, item)
            .stream()
            .map(this::definitionFromLocalRecipe)
            .toList();
    }

    private RecipeDefinition definitionFromLocalRecipe(SurvivalRecipeBook.Definition definition) {
        return new RecipeDefinition(
            definition.output(),
            definition.requires(),
            definition.station(),
            definition.stationItem(),
            definition.note()
        );
    }

    public String normalizeItemId(String item) {
        return SurvivalRecipeBook.normalizeItemId(item);
    }

    private String requiredToolForBaseResource(String item) {
        return SurvivalRecipeBook.requiredToolForBaseResource(item);
    }

    public boolean isGenericWoodenDoor(String item) {
        return SurvivalRecipeBook.isGenericWoodenDoor(item);
    }

    public String chooseWoodenDoorTarget(WorldSnapshot snapshot) {
        for (String item : snapshot.availableItems().keySet()) {
            if (SurvivalRecipeBook.DOOR_TO_PLANK.containsKey(item)) {
                return item;
            }
        }
        for (String item : snapshot.availableItems().keySet()) {
            String door = doorForPlank(item);
            if (door != null) {
                return door;
            }
        }
        for (String item : snapshot.availableItems().keySet()) {
            String door = doorForLog(item);
            if (door != null) {
                return door;
            }
        }
        for (WorldSnapshot.BlockSnapshot block : snapshot.getNearbyBlocks()) {
            String door = doorForLog(block.getBlock());
            if (door != null) {
                return door;
            }
        }
        return "minecraft:oak_door";
    }

    private String doorForLog(String log) {
        return SurvivalRecipeBook.doorForLog(log);
    }

    private String doorForPlank(String plank) {
        return SurvivalRecipeBook.doorForPlank(plank);
    }

    private boolean isKnownItem(String item) {
        if (SurvivalRecipeBook.findLocal(item).isPresent() || SurvivalRecipeBook.baseSource(item).isPresent()) {
            return true;
        }
        return itemFromId(item) != Items.AIR || "minecraft:air".equals(item);
    }

    boolean shouldPreferBaseSource(String item) {
        return BasicSourceResolver.isLog(item) || SurvivalRecipeBook.explicitBaseSource(item).isPresent();
    }

    boolean isReachableBlockInSnapshot(WorldSnapshot snapshot, String item) {
        if (snapshot == null) {
            return false;
        }
        for (WorldSnapshot.BlockSnapshot block : snapshot.getNearbyBlocks()) {
            if (item.equals(block.getBlock()) && block.isReachable()) {
                return true;
            }
        }
        return false;
    }

    private Item itemFromId(String item) {
        try {
            return BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(item));
        } catch (RuntimeException e) {
            AiPlayerMod.warn("recipe", "Invalid item id in recipe resolver: {}", item);
            return Items.AIR;
        }
    }

    private record RecipeDefinition(MaterialRequirement output, List<MaterialRequirement> requires, String station, String stationItem, String note) {
    }

    private record ResolveResult(boolean success, String message) {
        static ResolveResult ok() {
            return new ResolveResult(true, "");
        }

        static ResolveResult failure(String message) {
            return new ResolveResult(false, message);
        }
    }
}
