package com.aiplayer.action.actions;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.execution.ExecutionStep;
import com.aiplayer.execution.GoalChecker;
import com.aiplayer.execution.ReplanPolicy;
import com.aiplayer.execution.ResourceGatherSession;
import com.aiplayer.execution.StepExecutor;
import com.aiplayer.execution.StepResult;
import com.aiplayer.execution.TaskSession;
import com.aiplayer.planning.PlanSchema;
import com.aiplayer.planning.PlanValidator;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class MakeItemAction extends BaseAction {
    private final RecipeResolver recipeResolver = new RecipeResolver();
    private final PlanValidator planValidator = new PlanValidator(recipeResolver);
    private final GoalChecker goalChecker = new GoalChecker();
    private final ReplanPolicy replanPolicy = new ReplanPolicy();
    private final ResourceGatherSession resourceGatherSession = new ResourceGatherSession();
    private StepExecutor stepExecutor;
    private TaskSession session;
    private String targetItem;
    private String taskId;
    private String lastLoggedStepDescription;
    private int lastLoggedStepIndex = -1;
    private int quantity;
    private int ticksSinceLastPlan;
    private int failureReplans;

    public MakeItemAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
    }

    @Override
    protected void onStart() {
        taskId = task.getTaskId();
        targetItem = recipeResolver.normalizeItemId(task.getStringParameter("item", "minecraft:oak_door").toLowerCase(Locale.ROOT));
        quantity = task.getIntParameter("quantity", 1);
        failureReplans = 0;
        stepExecutor = new StepExecutor(aiPlayer, resourceGatherSession);
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' make_item start: target={} x{}, pos={}, mainHand={}, backpack={}",
            taskId, aiPlayer.getAiPlayerName(), targetItem, quantity, positionText(), mainHandText(), inventoryText());
        if (goalChecker.isComplete(aiPlayer, targetItem, quantity)) {
            result = ActionResult.success("目标已完成：" + targetItem + " x" + quantity);
            return;
        }
        rebuildSession("start");
    }

    @Override
    protected void onTick() {
        if (result != null) {
            return;
        }
        ticksSinceLastPlan++;
        if (goalChecker.isComplete(aiPlayer, targetItem, quantity)) {
            result = ActionResult.success("已完成制作目标：" + targetItem + " x" + quantity);
            return;
        }
        if (session == null) {
            result = ActionResult.failure("没有可执行计划：" + targetItem);
            return;
        }
        if (replanPolicy.shouldPeriodicReplan(ticksSinceLastPlan)) {
            if (isStatefulGatherStep(session.currentStep())) {
                ticksSinceLastPlan = 0;
                observeAfterStep();
                AiPlayerMod.debug("make_item", "[taskId={}] AiPlayer '{}' skipped periodic rebuild during stateful step: {}",
                    taskId, aiPlayer.getAiPlayerName(), session.currentStep().describe());
                return;
            }
            rebuildSession("periodic");
            return;
        }
        if (session.isDone()) {
            rebuildSession("plan_exhausted");
            if (goalChecker.isComplete(aiPlayer, targetItem, quantity)) {
                result = ActionResult.success("已完成制作目标：" + targetItem + " x" + quantity);
            } else if (session == null || session.isDone()) {
                result = ActionResult.failure("计划执行完毕但目标未完成：" + targetItem);
            }
            return;
        }

        ExecutionStep step = session.currentStep();
        logStepStartIfNeeded(step);
        StepResult stepResult = stepExecutor.tick(step);
        if (stepResult.isRunning()) {
            return;
        }
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' step end: index={}/{}, status={}, replanAllowed={}, step={}, message={}, pos={}, mainHand={}, backpack={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            session.getCurrentStepIndex() + 1,
            session.getStepCount(),
            stepResult.getStatus(),
            stepResult.requiresReplan(),
            step.describe(),
            stepResult.getMessage(),
            positionText(),
            mainHandText(),
            inventoryText());
        if (stepResult.isSuccess()) {
            failureReplans = 0;
            observeAfterStep();
            session.advance();
            if (goalChecker.isComplete(aiPlayer, targetItem, quantity)) {
                result = ActionResult.success("已完成制作目标：" + targetItem + " x" + quantity);
            } else if (replanPolicy.shouldReplanAfterStep()) {
                rebuildSession("after_step");
            }
            return;
        }

        session.addFailure(stepResult.getMessage());
        if (stepResult.requiresReplan() && replanPolicy.shouldReplanAfterFailure(session)
            && replanPolicy.shouldReplanAfterFailureCount(failureReplans)) {
            failureReplans++;
            rebuildSession(stepResult.getMessage());
            return;
        }
        result = ActionResult.failure(stepResult.getMessage());
    }

    @Override
    protected void onCancel() {
        if (stepExecutor != null) {
            stepExecutor.cancel();
        }
    }

    @Override
    public String getDescription() {
        if (session == null) {
            return "制作 " + targetItem + "：规划中";
        }
        ExecutionStep step = session.currentStep();
        String stepText = step == null ? "检查目标是否完成" : step.describe();
        return "制作 " + targetItem + " " + goalChecker.countItem(aiPlayer, targetItem) + "/" + quantity
            + "，step " + (session.getCurrentStepIndex() + 1) + "/" + session.getStepCount()
            + "：" + stepText;
    }

    private void rebuildSession(String reason) {
        ticksSinceLastPlan = 0;
        WorldSnapshot snapshot = WorldSnapshot.capture(aiPlayer, reason);
        RecipePlan recipePlan = recipeResolver.resolve(aiPlayer, snapshot, targetItem, quantity);
        if (!recipePlan.isSuccess()) {
            AiPlayerMod.warn("make_item", "[taskId={}] AiPlayer '{}' make_item plan failed: target={} x{}, reason={}, pos={}, backpack={}",
                taskId, aiPlayer.getAiPlayerName(), targetItem, quantity, recipePlan.getFailureReason(), positionText(), inventoryText());
            result = ActionResult.failure(recipePlan.getFailureReason());
            return;
        }
        PlanSchema plan = PlanSchema.fromRecipePlan("make_item", recipePlan);
        PlanValidator.ValidationResult validation = planValidator.validate(plan, aiPlayer, snapshot, recipePlan);
        if (!validation.valid()) {
            AiPlayerMod.warn("make_item", "[taskId={}] AiPlayer '{}' make_item validation failed: target={} x{}, reason={}, pos={}, backpack={}",
                taskId, aiPlayer.getAiPlayerName(), targetItem, quantity, validation.toUserText(), positionText(), inventoryText());
            result = ActionResult.failure(validation.toUserText());
            return;
        }
        session = new TaskSession("make_item", targetItem, quantity, validation.plan(), snapshot);
        stepExecutor = new StepExecutor(aiPlayer, resourceGatherSession);
        lastLoggedStepIndex = -1;
        lastLoggedStepDescription = null;
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' rebuilt make_item session because '{}': {} steps, target={} x{}, pos={}, mainHand={}, backpack={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason,
            session.getStepCount(),
            targetItem,
            quantity,
            positionText(),
            mainHandText(),
            inventoryText());
    }

    private void observeAfterStep() {
        if (session != null) {
            session.setLastSnapshot(WorldSnapshot.capture(aiPlayer, "after_step"));
        }
    }

    private boolean isStatefulGatherStep(ExecutionStep step) {
        if (step == null) {
            return false;
        }
        return "gather_stone".equals(step.getStep())
            || ("gather".equals(step.getStep()) && step.getResource() != null
                && (step.getResource().endsWith("_ore") || step.getResource().startsWith("block:")));
    }

    private void logStepStartIfNeeded(ExecutionStep step) {
        if (step == null || session == null) {
            return;
        }
        int index = session.getCurrentStepIndex();
        String description = step.describe();
        if (index == lastLoggedStepIndex && description.equals(lastLoggedStepDescription)) {
            return;
        }
        lastLoggedStepIndex = index;
        lastLoggedStepDescription = description;
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' step start: index={}/{}, step={}, target={} x{}, currentTargetCount={}, pos={}, dimension={}, mainHand={}, backpack={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            index + 1,
            session.getStepCount(),
            description,
            targetItem,
            quantity,
            goalChecker.countItem(aiPlayer, targetItem),
            positionText(),
            aiPlayer.level().dimension().location(),
            mainHandText(),
            inventoryText());
    }

    private String positionText() {
        return aiPlayer.blockPosition().toShortString();
    }

    private String mainHandText() {
        ItemStack stack = aiPlayer.getMainHandItem();
        if (stack.isEmpty()) {
            return "minecraft:air";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String item = key == null ? stack.getItem().toString() : key.toString();
        return item + " x" + stack.getCount();
    }

    private String inventoryText() {
        Map<String, Integer> inventory = new TreeMap<>(aiPlayer.getInventorySnapshot());
        return inventory.isEmpty() ? "[empty]" : inventory.toString();
    }
}
