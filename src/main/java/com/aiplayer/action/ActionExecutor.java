package com.aiplayer.action;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.actions.*;
import com.aiplayer.di.ServiceContainer;
import com.aiplayer.di.SimpleServiceContainer;
import com.aiplayer.event.EventBus;
import com.aiplayer.event.SimpleEventBus;
import com.aiplayer.execution.*;
import com.aiplayer.llm.ResponseParser;
import com.aiplayer.llm.TaskPlanner;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.plugin.ActionRegistry;
import com.aiplayer.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ActionExecutor {
    private final AiPlayerEntity aiPlayer;
    private TaskPlanner taskPlanner;
    private final Queue<Task> taskQueue;

    private BaseAction currentAction;
    private String currentGoal;
    private int ticksSinceLastAction;
    private BaseAction idleFollowAction;
    private CompletableFuture<ResponseParser.ParsedResponse> planningFuture;
    private boolean isPlanning = false;
    private String pendingCommand;
    private String pendingTaskId;
    private String activeTaskId;
    private final ActionContext actionContext;
    private final InterceptorChain interceptorChain;
    private final AgentStateMachine stateMachine;
    private final EventBus eventBus;

    public ActionExecutor(AiPlayerEntity aiPlayer) {
        this.aiPlayer = aiPlayer;
        this.taskPlanner = null;
        this.taskQueue = new LinkedList<>();
        this.ticksSinceLastAction = 0;
        this.idleFollowAction = null;
        this.planningFuture = null;
        this.pendingCommand = null;
        this.pendingTaskId = null;
        this.activeTaskId = "task-unknown";
        this.eventBus = new SimpleEventBus();
        this.stateMachine = new AgentStateMachine(eventBus, aiPlayer.getAiPlayerName());
        this.interceptorChain = new InterceptorChain();
        interceptorChain.addInterceptor(new LoggingInterceptor());
        interceptorChain.addInterceptor(new MetricsInterceptor());
        interceptorChain.addInterceptor(new EventPublishingInterceptor(eventBus, aiPlayer.getAiPlayerName()));
        ServiceContainer container = new SimpleServiceContainer();
        this.actionContext = ActionContext.builder()
            .serviceContainer(container)
            .eventBus(eventBus)
            .stateMachine(stateMachine)
            .interceptorChain(interceptorChain)
            .build();

        AiPlayerMod.debug("action", "ActionExecutor initialized with plugin architecture for AiPlayer '{}'",
            aiPlayer.getAiPlayerName());
    }
    
    private TaskPlanner getTaskPlanner() {
        if (taskPlanner == null) {
            AiPlayerMod.info("planning", "Initializing TaskPlanner for AiPlayer '{}'", aiPlayer.getAiPlayerName());
            taskPlanner = new TaskPlanner();
        }
        return taskPlanner;
    }

        public void processNaturalLanguageCommand(String command) {
        String taskId = createTaskId();
        AiPlayerMod.info("planning", "[taskId={}] AiPlayer '{}' processing command (async): {}",
            taskId, aiPlayer.getAiPlayerName(), command);
        if (isPlanning) {
            AiPlayerMod.warn("planning", "[taskId={}] AiPlayer '{}' is already planning, ignoring command: {}",
                taskId, aiPlayer.getAiPlayerName(), command);
            sendToGUI(aiPlayer.getAiPlayerName(), "请稍等，我还在处理上一条指令...");
            return;
        }
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }

        try {
            this.pendingCommand = command;
            this.pendingTaskId = taskId;
            this.isPlanning = true;
            sendToGUI(aiPlayer.getAiPlayerName(), "正在思考...");
            planningFuture = getTaskPlanner().planTasksAsync(aiPlayer, command, taskId);

            AiPlayerMod.info("planning", "[taskId={}] AiPlayer '{}' started async planning for: {}",
                taskId, aiPlayer.getAiPlayerName(), command);

        } catch (NoClassDefFoundError e) {
            AiPlayerMod.error("planning", "Failed to initialize AI components", e);
            sendToGUI(aiPlayer.getAiPlayerName(), "AI 系统暂时无法初始化，请检查配置。");
            isPlanning = false;
            planningFuture = null;
        } catch (Exception e) {
            AiPlayerMod.error("planning", "Error starting async planning", e);
            sendToGUI(aiPlayer.getAiPlayerName(), "处理指令时出现错误。");
            isPlanning = false;
            planningFuture = null;
        }
    }

        private void sendToGUI(String aiPlayerName, String message) {
        if (aiPlayer.level().isClientSide) {
            com.aiplayer.client.AiPlayerGUI.addAiPlayerMessage(aiPlayerName, message);
        }
    }

    public void tick() {
        ticksSinceLastAction++;
        if (isPlanning && planningFuture != null && planningFuture.isDone()) {
            try {
                ResponseParser.ParsedResponse response = planningFuture.get();

                if (response != null) {
                    currentGoal = response.getPlan();
                    aiPlayer.getMemory().setCurrentGoal(currentGoal);

                    taskQueue.clear();
                    taskQueue.addAll(stampTasks(response.getTasks(), pendingTaskId));

                    if (AiPlayerConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(aiPlayer.getAiPlayerName(), "好的，" + currentGoal);
                    }

                    AiPlayerMod.info("planning", "[taskId={}] AiPlayer '{}' async planning complete: {} tasks queued",
                        pendingTaskId, aiPlayer.getAiPlayerName(), taskQueue.size());
                } else {
                    sendToGUI(aiPlayer.getAiPlayerName(), "我没能理解这条指令。");
                    AiPlayerMod.warn("planning", "[taskId={}] AiPlayer '{}' async planning returned null response",
                        pendingTaskId, aiPlayer.getAiPlayerName());
                }

            } catch (java.util.concurrent.CancellationException e) {
                AiPlayerMod.info("planning", "[taskId={}] AiPlayer '{}' planning was cancelled",
                    pendingTaskId, aiPlayer.getAiPlayerName());
                sendToGUI(aiPlayer.getAiPlayerName(), "任务规划已取消。");
            } catch (Exception e) {
                AiPlayerMod.error("planning", "[taskId={}] AiPlayer '{}' failed to get planning result",
                    pendingTaskId, aiPlayer.getAiPlayerName(), e);
                sendToGUI(aiPlayer.getAiPlayerName(), "规划任务时出现错误。");
            } finally {
                isPlanning = false;
                planningFuture = null;
                pendingCommand = null;
                pendingTaskId = null;
            }
        }

        if (currentAction != null) {
            if (currentAction.isComplete()) {
                ActionResult result = currentAction.getResult();
                AiPlayerMod.info("action", "[taskId={}] AiPlayer '{}' - Action completed: {} (Success: {})", 
                    activeTaskId, aiPlayer.getAiPlayerName(), result.getMessage(), result.isSuccess());
                
                aiPlayer.getMemory().addAction(currentAction.getDescription());
                
                if (!result.isSuccess() && result.requiresReplanning()) {
                    if (AiPlayerConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(aiPlayer.getAiPlayerName(), "遇到问题：" + result.getMessage());
                    }
                }
                
                currentAction = null;
                activeTaskId = "task-unknown";
            } else {
                if (ticksSinceLastAction % 100 == 0) {
                    AiPlayerMod.info("action", "[taskId={}] AiPlayer '{}' - Ticking action: {}", 
                        activeTaskId, aiPlayer.getAiPlayerName(), currentAction.getDescription());
                }
                currentAction.tick();
                return;
            }
        }

        if (ticksSinceLastAction >= AiPlayerConfig.ACTION_TICK_DELAY.get()) {
            if (!taskQueue.isEmpty()) {
                Task nextTask = taskQueue.poll();
                executeTask(nextTask);
                ticksSinceLastAction = 0;
                return;
            }
        }
        if (taskQueue.isEmpty() && currentAction == null && currentGoal == null) {
            if (idleFollowAction == null) {
                idleFollowAction = new IdleFollowAction(aiPlayer);
                idleFollowAction.start();
            } else if (idleFollowAction.isComplete()) {
                idleFollowAction = new IdleFollowAction(aiPlayer);
                idleFollowAction.start();
            } else {
                idleFollowAction.tick();
            }
        } else if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
    }

    private void executeTask(Task task) {
        activeTaskId = task.getTaskId();
        AiPlayerMod.info("action", "[taskId={}] AiPlayer '{}' executing task: {} (action type: {})", 
            activeTaskId, aiPlayer.getAiPlayerName(), task, task.getAction());
        
        currentAction = createAction(task);
        
        if (currentAction == null) {
            AiPlayerMod.error("action", "[taskId={}] FAILED to create action for task: {}", activeTaskId, task);
            return;
        }

        AiPlayerMod.info("action", "[taskId={}] Created action: {} - starting now...",
            activeTaskId, currentAction.getClass().getSimpleName());
        currentAction.start();
        AiPlayerMod.info("action", "[taskId={}] Action started! Is complete: {}", activeTaskId, currentAction.isComplete());
    }

    private List<Task> stampTasks(List<Task> tasks, String taskId) {
        List<Task> stamped = new ArrayList<>();
        String normalizedTaskId = taskId == null || taskId.isBlank() ? "task-unknown" : taskId;
        for (Task task : tasks) {
            if (task != null) {
                Task stampedTask = task.withParameter("task_id", normalizedTaskId);
                if (pendingCommand != null && !pendingCommand.isBlank()) {
                    stampedTask = stampedTask.withParameter("source_command", pendingCommand);
                }
                stamped.add(stampedTask);
            }
        }
        return stamped;
    }

    private String createTaskId() {
        return "task-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public void startLocalTask(String goal, Task task) {
        if (task == null) {
            return;
        }
        if (planningFuture != null && !planningFuture.isDone()) {
            planningFuture.cancel(false);
        }
        isPlanning = false;
        planningFuture = null;
        pendingCommand = null;
        pendingTaskId = null;
        stopCurrentAction();

        String taskId = createTaskId();
        currentGoal = goal == null || goal.isBlank() ? task.getAction() : goal;
        aiPlayer.getMemory().setCurrentGoal(currentGoal);
        taskQueue.add(task.withParameter("task_id", taskId).withParameter("source_command", currentGoal));
        activeTaskId = "task-unknown";
        AiPlayerMod.info("planning", "[taskId={}] AiPlayer '{}' queued local task: goal={}, task={}",
            taskId, aiPlayer.getAiPlayerName(), currentGoal, task);
    }

        private BaseAction createAction(Task task) {
        String actionType = task.getAction();
        ActionRegistry registry = ActionRegistry.getInstance();
        if (registry.hasAction(actionType)) {
            BaseAction action = registry.createAction(actionType, aiPlayer, task, actionContext);
            if (action != null) {
                AiPlayerMod.debug("action", "Created action '{}' via registry (plugin: {})",
                    actionType, registry.getPluginForAction(actionType));
                return action;
            }
        }
        AiPlayerMod.debug("action", "Using built-in action factory for action: {}", actionType);
        return createBuiltInAction(task);
    }

    private BaseAction createBuiltInAction(Task task) {
        return switch (task.getAction()) {
            case "pathfind" -> new PathfindAction(aiPlayer, task);
            case "mine" -> new MineBlockAction(aiPlayer, task);
            case "place" -> new PlaceBlockAction(aiPlayer, task);
            case "craft" -> new CraftItemAction(aiPlayer, task);
            case "attack" -> new CombatAction(aiPlayer, task);
            case "follow" -> new FollowPlayerAction(aiPlayer, task);
            case "gather" -> new GatherResourceAction(aiPlayer, task);
            case "build" -> new BuildStructureAction(aiPlayer, task);
            case "make_item" -> new MakeItemAction(aiPlayer, task);
            default -> {
                AiPlayerMod.warn("action", "Unknown action type: {}", task.getAction());
                yield null;
            }
        };
    }

    public void stopCurrentAction() {
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }
        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
        taskQueue.clear();
        currentGoal = null;
        stateMachine.reset();
    }

    public boolean isExecuting() {
        return currentAction != null || !taskQueue.isEmpty();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    public String getCurrentActionDescription() {
        return currentAction == null ? "" : currentAction.getDescription();
    }

    public String getActiveTaskId() {
        return activeTaskId;
    }

        public EventBus getEventBus() {
        return eventBus;
    }

        public AgentStateMachine getStateMachine() {
        return stateMachine;
    }

        public InterceptorChain getInterceptorChain() {
        return interceptorChain;
    }

        public ActionContext getActionContext() {
        return actionContext;
    }

        public boolean isPlanning() {
        return isPlanning;
    }
}
