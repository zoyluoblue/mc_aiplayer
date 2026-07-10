package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;

public abstract class AbstractTask implements Task {
    protected TaskState state = TaskState.PENDING;
    protected String failureReason = "";
    protected int elapsed;
    private int startedTick;

    @Override
    public final void start(AIPlayerEntity bot) {
        if (state != TaskState.PENDING) {
            return;
        }
        state = TaskState.RUNNING;
        startedTick = bot.getServer().getTicks();
        onStart(bot);
    }

    @Override
    public final void tick(AIPlayerEntity bot) {
        if (state != TaskState.RUNNING) {
            return;
        }
        elapsed++;
        onTick(bot);
    }

    @Override
    public final void pause(AIPlayerEntity bot) {
        if (state != TaskState.RUNNING) {
            return;
        }
        state = TaskState.PAUSED;
        onPause(bot);
    }

    @Override
    public final void resume(AIPlayerEntity bot) {
        if (state != TaskState.PAUSED) {
            return;
        }
        state = TaskState.RUNNING;
        onResume(bot);
    }

    @Override
    public final void abort(AIPlayerEntity bot) {
        if (state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.CANCELLED) {
            return;
        }
        state = TaskState.FAILED;
        failureReason = "aborted";
        onAbort(bot);
    }

    @Override
    public final void cancel(AIPlayerEntity bot, String reason) {
        if (state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.CANCELLED) {
            return;
        }
        state = TaskState.CANCELLED;
        failureReason = reason == null ? "" : reason;
        onAbort(bot);
    }

    @Override
    public TaskState state() {
        return state;
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public int elapsedTicks() {
        return elapsed;
    }

    public int startedTick() {
        return startedTick;
    }

    protected void complete() {
        state = TaskState.COMPLETED;
    }

    protected void fail(String reason) {
        state = TaskState.FAILED;
        failureReason = reason;
    }

    protected abstract void onStart(AIPlayerEntity bot);

    protected abstract void onTick(AIPlayerEntity bot);

    protected void onPause(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    protected void onResume(AIPlayerEntity bot) {
    }

    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }
}
