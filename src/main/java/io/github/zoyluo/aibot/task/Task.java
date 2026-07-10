package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;

public interface Task {
    String name();

    String describe();

    TaskState state();

    String failureReason();

    void start(AIPlayerEntity bot);

    void tick(AIPlayerEntity bot);

    void pause(AIPlayerEntity bot);

    void resume(AIPlayerEntity bot);

    void abort(AIPlayerEntity bot);

    void cancel(AIPlayerEntity bot, String reason);

    double progress();

    int elapsedTicks();

    default boolean isWaiting() {
        return false;
    }
}
