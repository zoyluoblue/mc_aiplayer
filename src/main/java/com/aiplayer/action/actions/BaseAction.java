package com.aiplayer.action.actions;

import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;

public abstract class BaseAction {
    protected final AiPlayerEntity aiPlayer;
    protected final Task task;
    protected ActionResult result;
    protected boolean started = false;
    protected boolean cancelled = false;

    public BaseAction(AiPlayerEntity aiPlayer, Task task) {
        this.aiPlayer = aiPlayer;
        this.task = task;
    }

    public void start() {
        if (started) return;
        started = true;
        onStart();
    }

    public void tick() {
        if (!started || isComplete()) return;
        onTick();
    }

    public void cancel() {
        cancelled = true;
        result = ActionResult.failure("Action cancelled");
        onCancel();
    }

    public boolean isComplete() {
        return result != null || cancelled;
    }

    public ActionResult getResult() {
        return result;
    }

    protected abstract void onStart();
    protected abstract void onTick();
    protected abstract void onCancel();
    
    public abstract String getDescription();

    public String getStatusDetails() {
        return "";
    }
}
