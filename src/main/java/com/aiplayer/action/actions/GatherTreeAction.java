package com.aiplayer.action.actions;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.execution.ExecutionStep;
import com.aiplayer.execution.GoalChecker;
import com.aiplayer.execution.ResourceGatherSession;
import com.aiplayer.execution.StepExecutor;
import com.aiplayer.execution.StepResult;
import com.aiplayer.planning.PlanStep;

import java.util.Locale;

public final class GatherTreeAction extends BaseAction {
    private static final String GENERIC_LOG_ITEM = "minecraft:oak_log";

    private final GoalChecker goalChecker = new GoalChecker();
    private StepExecutor stepExecutor;
    private ExecutionStep executionStep;
    private int quantity;
    private int startCount;
    private String lastMessage = "准备砍树";

    public GatherTreeAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
    }

    @Override
    protected void onStart() {
        String resourceType = task.getStringParameter("resource", "tree").toLowerCase(Locale.ROOT);
        quantity = Math.max(1, task.getIntParameter("quantity", 1));
        if (!isTreeResource(resourceType)) {
            result = ActionResult.failure("GatherTreeAction 只能处理 tree 资源");
            return;
        }
        startCount = goalChecker.countItem(aiPlayer, GENERIC_LOG_ITEM);
        executionStep = new ExecutionStep(PlanStep.gather("tree", GENERIC_LOG_ITEM, quantity));
        stepExecutor = new StepExecutor(aiPlayer, new ResourceGatherSession(), task.getTaskId());
        AiPlayerMod.info("action", "[taskId={}] AiPlayer '{}' direct gather_tree start: target={} x{}, startCount={}, pos={}",
            task.getTaskId(),
            aiPlayer.getAiPlayerName(),
            GENERIC_LOG_ITEM,
            quantity,
            startCount,
            aiPlayer.blockPosition().toShortString());
    }

    @Override
    protected void onTick() {
        if (stepExecutor == null || executionStep == null) {
            result = ActionResult.failure("砍树执行器未初始化");
            return;
        }
        StepResult stepResult = stepExecutor.tick(executionStep);
        lastMessage = stepResult.getMessage();
        if (stepResult.isRunning()) {
            return;
        }
        if (stepResult.isSuccess()) {
            result = ActionResult.success(stepResult.getMessage());
            return;
        }
        result = ActionResult.failure(stepResult.getMessage(), stepResult.requiresReplan());
    }

    @Override
    protected void onCancel() {
        aiPlayer.getNavigation().stop();
        aiPlayer.setSprinting(false);
    }

    @Override
    public String getDescription() {
        int current = Math.max(0, goalChecker.countItem(aiPlayer, GENERIC_LOG_ITEM) - startCount);
        return "砍树 " + Math.min(current, quantity) + "/" + quantity + " 块原木：" + lastMessage;
    }

    private boolean isTreeResource(String resourceType) {
        return resourceType.contains("tree") || resourceType.contains("树");
    }
}
