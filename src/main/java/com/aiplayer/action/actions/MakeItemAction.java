package com.aiplayer.action.actions;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.agent.AgentCritic;
import com.aiplayer.agent.AgentFailureAdvice;
import com.aiplayer.agent.AgentSkillRecord;
import com.aiplayer.agent.FailureRecoveryAdvisor;
import com.aiplayer.agent.MiningStrategyAdvice;
import com.aiplayer.agent.MiningStrategyAdvisor;
import com.aiplayer.agent.StepExecutionEvent;
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
import com.aiplayer.recipe.MiningResource;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

public class MakeItemAction extends BaseAction {
    private static final int MINING_STRATEGY_REVIEW_TICKS = 600;

    private final RecipeResolver recipeResolver = new RecipeResolver();
    private final PlanValidator planValidator = new PlanValidator(recipeResolver);
    private final GoalChecker goalChecker = new GoalChecker();
    private final ReplanPolicy replanPolicy = new ReplanPolicy();
    private final ResourceGatherSession resourceGatherSession;
    private final FailureRecoveryAdvisor failureRecoveryAdvisor = new FailureRecoveryAdvisor();
    private final MiningStrategyAdvisor miningStrategyAdvisor = new MiningStrategyAdvisor();
    private final AgentCritic critic = new AgentCritic();
    private StepExecutor stepExecutor;
    private TaskSession session;
    private String targetItem;
    private String taskId;
    private String sourceCommand;
    private String lastLoggedStepDescription;
    private int lastLoggedStepIndex = -1;
    private Map<String, Integer> stepStartInventory = Map.of();
    private List<StepExecutionEvent> taskEvents = new ArrayList<>();
    private List<ExecutionStep> latestPlanSteps = List.of();
    private List<String> retrievedSkillSummaries = List.of();
    private long stepStartedAtTick;
    private int quantity;
    private int ticksSinceLastPlan;
    private int ticksSinceMiningStrategyReport;
    private int failureReplans;
    private boolean skillCommitted;
    private CompletableFuture<MiningStrategyAdvice> miningStrategyRequest;
    private volatile MiningStrategyAdvice pendingMiningStrategyAdvice;

    public MakeItemAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
        this.resourceGatherSession = aiPlayer.getResourceGatherSession();
    }

    @Override
    protected void onStart() {
        taskId = task.getTaskId();
        targetItem = recipeResolver.normalizeItemId(task.getStringParameter("item", "minecraft:oak_door").toLowerCase(Locale.ROOT));
        quantity = task.getIntParameter("quantity", 1);
        sourceCommand = task.getStringParameter("source_command", "");
        failureReplans = 0;
        ticksSinceMiningStrategyReport = 0;
        skillCommitted = false;
        taskEvents = new ArrayList<>();
        latestPlanSteps = List.of();
        miningStrategyRequest = null;
        pendingMiningStrategyAdvice = null;
        stepExecutor = new StepExecutor(aiPlayer, resourceGatherSession, taskId);
        retrievedSkillSummaries = retrieveSkillSummaries();
        if (!miningStrategyAdvisor.isEnabled()) {
            AiPlayerMod.debug("mining_strategy", "[taskId={}] DeepSeek mining strategy disabled for AiPlayer '{}': {}",
                taskId, aiPlayer.getAiPlayerName(), miningStrategyAdvisor.disabledReason());
        }
        if (!retrievedSkillSummaries.isEmpty()) {
            AiPlayerMod.info("planning", "[taskId={}] AiPlayer '{}' retrieved {} skill memories for target {}: {}",
                taskId, aiPlayer.getAiPlayerName(), retrievedSkillSummaries.size(), targetItem, retrievedSkillSummaries);
        }
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' make_item start: target={} x{}, pos={}, mainHand={}, backpack={}",
            taskId, aiPlayer.getAiPlayerName(), targetItem, quantity, positionText(), mainHandText(), inventoryText());
        if (goalChecker.isComplete(aiPlayer, targetItem, quantity)) {
            completeSuccessfully("目标已完成：" + targetItem + " x" + quantity);
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
        ticksSinceMiningStrategyReport++;
        if (goalChecker.isComplete(aiPlayer, targetItem, quantity)) {
            completeSuccessfully("已完成制作目标：" + targetItem + " x" + quantity);
            return;
        }
        if (session == null) {
            result = ActionResult.failure("没有可执行计划：" + targetItem);
            return;
        }
        if (pollMiningStrategyAdvice()) {
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
                completeSuccessfully("已完成制作目标：" + targetItem + " x" + quantity);
            } else if (session == null || session.isDone()) {
                AgentCritic.CritiqueResult critique = critic.evaluateMakeItem(
                    aiPlayer,
                    targetItem,
                    quantity,
                    session,
                    WorldSnapshot.capture(aiPlayer, "critic_plan_exhausted")
                );
                result = ActionResult.failure(critique.message());
            }
            return;
        }

        ExecutionStep step = session.currentStep();
        maybeRequestMiningStrategy(step);
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
        recordStepEvent(step, stepResult);
        if (stepResult.isSuccess()) {
            failureReplans = 0;
            observeAfterStep();
            session.advance();
            if (goalChecker.isComplete(aiPlayer, targetItem, quantity)) {
                completeSuccessfully("已完成制作目标：" + targetItem + " x" + quantity);
            } else if (replanPolicy.shouldReplanAfterStep()) {
                rebuildSession("after_step");
            }
            return;
        }

        session.addFailure(stepResult.getMessage());
        AgentFailureAdvice advice = failureRecoveryAdvisor.review(
            targetItem,
            step,
            WorldSnapshot.capture(aiPlayer, "failure_review"),
            session.getFailureHistory(),
            null,
            retrievedSkillSummaries
        );
        AiPlayerMod.info("planning", "[taskId={}] AiPlayer '{}' failure review: source={}, category={}, strategy={}, action={}, message={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            advice.source(),
            advice.category(),
            advice.strategy(),
            advice.action(),
            advice.userMessage());
        if (stepResult.requiresReplan() && replanPolicy.shouldReplanAfterFailure(session)
            && replanPolicy.shouldReplanAfterFailureCount(failureReplans)) {
            failureReplans++;
            rebuildSession(stepResult.getMessage());
            return;
        }
        result = ActionResult.failure(stepResult.getMessage(), stepResult.requiresReplan());
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
        latestPlanSteps = session.getSteps();
        stepExecutor = new StepExecutor(aiPlayer, resourceGatherSession, taskId);
        lastLoggedStepIndex = -1;
        lastLoggedStepDescription = null;
        ticksSinceMiningStrategyReport = 0;
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
        return "gather_tree".equals(step.getStep())
            || "gather_stone".equals(step.getStep())
            || ("gather".equals(step.getStep()) && step.getResource() != null
                && (step.getResource().endsWith("_ore") || step.getResource().startsWith("block:")));
    }

    private boolean isMiningStrategyStep(ExecutionStep step) {
        if (step == null) {
            return false;
        }
        if ("gather_stone".equals(step.getStep())) {
            return true;
        }
        return "gather".equals(step.getStep())
            && MiningResource.findByItemOrSource(step.getItem(), step.getResource()).isPresent();
    }

    private void maybeRequestMiningStrategy(ExecutionStep step) {
        if (!isMiningStrategyStep(step) || !miningStrategyAdvisor.isEnabled()) {
            return;
        }
        if (ticksSinceMiningStrategyReport < MINING_STRATEGY_REVIEW_TICKS) {
            return;
        }
        if (miningStrategyRequest != null && !miningStrategyRequest.isDone()) {
            return;
        }
        ticksSinceMiningStrategyReport = 0;
        AiPlayerMod.info("mining_strategy", "[taskId={}] AiPlayer '{}' reports mining state to DeepSeek: target={} x{}, step={}, pos={}, mainHand={}, backpack={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            targetItem,
            quantity,
            step.describe(),
            positionText(),
            mainHandText(),
            inventoryText());
        miningStrategyRequest = miningStrategyAdvisor
            .requestAdvice(taskId, aiPlayer, targetItem, quantity, session, retrievedSkillSummaries)
            .thenApply(advice -> {
                pendingMiningStrategyAdvice = advice;
                return advice;
            });
    }

    private boolean pollMiningStrategyAdvice() {
        MiningStrategyAdvice advice = pendingMiningStrategyAdvice;
        if (advice == null) {
            return false;
        }
        pendingMiningStrategyAdvice = null;
        if (!advice.accepted()) {
            AiPlayerMod.warn("mining_strategy", "[taskId={}] AiPlayer '{}' rejected DeepSeek mining strategy: source={}, reason={}, raw={}",
                taskId, aiPlayer.getAiPlayerName(), advice.source(), advice.reason(), advice.rawJson());
            return false;
        }
        AiPlayerMod.info("mining_strategy", "[taskId={}] AiPlayer '{}' accepted DeepSeek mining strategy: action={}, strategy={}, rebuild={}, userHelp={}, message={}, reason={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            advice.action(),
            advice.strategy(),
            advice.needsRebuild(),
            advice.needsUserHelp(),
            advice.message(),
            advice.reason());
        if (stepExecutor != null) {
            stepExecutor.applyMiningStrategyAdvice(advice);
        }
        if (advice.rebuildPlan()) {
            rebuildSession("deepseek_mining_strategy:" + advice.strategy());
            return true;
        }
        return false;
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
        stepStartInventory = new TreeMap<>(aiPlayer.getInventorySnapshot());
        stepStartedAtTick = aiPlayer.tickCount;
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

    private void recordStepEvent(ExecutionStep step, StepResult stepResult) {
        if (session == null) {
            return;
        }
        StepExecutionEvent event = StepExecutionEvent.fromResult(
            taskId,
            session.getCurrentStepIndex(),
            session.getStepCount(),
            step,
            stepResult,
            stepStartedAtTick,
            aiPlayer.tickCount,
            inventoryDelta(stepStartInventory, aiPlayer.getInventorySnapshot()),
            positionText()
        );
        session.addEvent(event);
        taskEvents.add(event);
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' step event: stepId={}, status={}, progress={}, delta={}, message={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            event.stepId(),
            event.status(),
            event.progress(),
            event.inventoryDelta(),
            event.message());
    }

    private Map<String, Integer> inventoryDelta(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> delta = new TreeMap<>();
        Map<String, Integer> safeBefore = before == null ? Map.of() : before;
        Map<String, Integer> safeAfter = after == null ? Map.of() : after;
        for (String key : safeBefore.keySet()) {
            int change = safeAfter.getOrDefault(key, 0) - safeBefore.getOrDefault(key, 0);
            if (change != 0) {
                delta.put(key, change);
            }
        }
        for (String key : safeAfter.keySet()) {
            if (safeBefore.containsKey(key)) {
                continue;
            }
            int change = safeAfter.getOrDefault(key, 0);
            if (change != 0) {
                delta.put(key, change);
            }
        }
        return delta;
    }

    private List<String> retrieveSkillSummaries() {
        List<AgentSkillRecord> skills = aiPlayer.getMemory().getSkillLibrary()
            .findRelevant(targetItem, sourceCommand, 3, aiPlayer.tickCount);
        return skills.stream().map(AgentSkillRecord::toPromptSummary).toList();
    }

    private void completeSuccessfully(String message) {
        AgentCritic.CritiqueResult critique = critic.evaluateMakeItem(
            aiPlayer,
            targetItem,
            quantity,
            session,
            session == null ? WorldSnapshot.capture(aiPlayer, "critic_success") : session.getLastSnapshot()
        );
        if (!critique.complete()) {
            AiPlayerMod.warn("make_item", "[taskId={}] AiPlayer '{}' local critic blocked success: {}",
                taskId, aiPlayer.getAiPlayerName(), critique.message());
            result = ActionResult.failure(critique.message());
            return;
        }
        commitSkillMemory(critique);
        result = ActionResult.success(message);
    }

    private void commitSkillMemory(AgentCritic.CritiqueResult critique) {
        if (skillCommitted) {
            return;
        }
        skillCommitted = true;
        AgentSkillRecord skill = aiPlayer.getMemory().getSkillLibrary().rememberSuccess(
            "make_item",
            targetItem,
            quantity,
            sourceCommand,
            latestPlanSteps,
            taskEvents,
            critique.recentFailures(),
            aiPlayer.tickCount
        );
        AiPlayerMod.info("planning", "[taskId={}] AiPlayer '{}' committed skill memory: {}",
            taskId, aiPlayer.getAiPlayerName(), skill.toPromptSummary());
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
