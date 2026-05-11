package com.aiplayer.execution;

import com.aiplayer.action.actions.BaseAction;
import com.aiplayer.action.ActionResult;
import com.aiplayer.event.ActionCompletedEvent;
import com.aiplayer.event.ActionStartedEvent;
import com.aiplayer.event.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EventPublishingInterceptor implements ActionInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventPublishingInterceptor.class);

    private final EventBus eventBus;
    private final String agentId;

        private final ConcurrentHashMap<Integer, Long> startTimes;

        public EventPublishingInterceptor(EventBus eventBus, String agentId) {
        this.eventBus = eventBus;
        this.agentId = agentId;
        this.startTimes = new ConcurrentHashMap<>();
    }

    @Override
    public boolean beforeAction(BaseAction action, ActionContext context) {
        startTimes.put(System.identityHashCode(action), System.currentTimeMillis());
        ActionStartedEvent event = new ActionStartedEvent(
            agentId,
            extractActionName(action),
            action.getDescription(),
            Map.of()
        );

        eventBus.publish(event);
        LOGGER.debug("Published ActionStartedEvent: {}", action.getDescription());

        return true;
    }

    @Override
    public void afterAction(BaseAction action, ActionResult result, ActionContext context) {
        Long startTime = startTimes.remove(System.identityHashCode(action));
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;
        ActionCompletedEvent event = new ActionCompletedEvent(
            agentId,
            extractActionName(action),
            result.isSuccess(),
            result.getMessage(),
            duration
        );

        eventBus.publish(event);
        LOGGER.debug("Published ActionCompletedEvent: {} (success: {}, duration: {}ms)",
            action.getDescription(), result.isSuccess(), duration);
    }

    @Override
    public boolean onError(BaseAction action, Exception exception, ActionContext context) {
        Long startTime = startTimes.remove(System.identityHashCode(action));
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;
        ActionCompletedEvent event = new ActionCompletedEvent(
            agentId,
            extractActionName(action),
            false,
            "Exception: " + exception.getMessage(),
            duration
        );

        eventBus.publish(event);
        return false;
    }

    @Override
    public int getPriority() {
        return 500;
    }

    @Override
    public String getName() {
        return "EventPublishingInterceptor";
    }

        private String extractActionName(BaseAction action) {
        String className = action.getClass().getSimpleName();
        if (className.endsWith("Action")) {
            return className.substring(0, className.length() - 6).toLowerCase();
        }
        return className.toLowerCase();
    }
}
