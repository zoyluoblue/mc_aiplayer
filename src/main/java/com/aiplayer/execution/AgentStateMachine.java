package com.aiplayer.execution;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.event.EventBus;
import com.aiplayer.event.StateTransitionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class AgentStateMachine {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentStateMachine.class);

        private static final Map<AgentState, Set<AgentState>> VALID_TRANSITIONS;

    static {
        VALID_TRANSITIONS = new EnumMap<>(AgentState.class);
        VALID_TRANSITIONS.put(AgentState.IDLE,
            EnumSet.of(AgentState.PLANNING));
        VALID_TRANSITIONS.put(AgentState.PLANNING,
            EnumSet.of(AgentState.EXECUTING, AgentState.FAILED, AgentState.IDLE));
        VALID_TRANSITIONS.put(AgentState.EXECUTING,
            EnumSet.of(AgentState.COMPLETED, AgentState.FAILED, AgentState.PAUSED));
        VALID_TRANSITIONS.put(AgentState.PAUSED,
            EnumSet.of(AgentState.EXECUTING, AgentState.IDLE));
        VALID_TRANSITIONS.put(AgentState.COMPLETED,
            EnumSet.of(AgentState.IDLE));
        VALID_TRANSITIONS.put(AgentState.FAILED,
            EnumSet.of(AgentState.IDLE));
    }

        private final AtomicReference<AgentState> currentState;

        private final EventBus eventBus;

        private final String agentId;

        public AgentStateMachine(EventBus eventBus) {
        this(eventBus, "default");
    }

        public AgentStateMachine(EventBus eventBus, String agentId) {
        this.currentState = new AtomicReference<>(AgentState.IDLE);
        this.eventBus = eventBus;
        this.agentId = agentId;
        LOGGER.debug("[{}] State machine initialized in IDLE state", agentId);
    }

        public AgentState getCurrentState() {
        return currentState.get();
    }

        public boolean canTransitionTo(AgentState targetState) {
        if (targetState == null) return false;

        AgentState current = currentState.get();
        Set<AgentState> validTargets = VALID_TRANSITIONS.get(current);

        return validTargets != null && validTargets.contains(targetState);
    }

        public boolean transitionTo(AgentState targetState) {
        return transitionTo(targetState, null);
    }

        public boolean transitionTo(AgentState targetState, String reason) {
        if (targetState == null) {
            LOGGER.warn("[{}] Cannot transition to null state", agentId);
            return false;
        }

        AgentState fromState = currentState.get();
        if (!canTransitionTo(targetState)) {
            LOGGER.warn("[{}] Invalid state transition: {} → {} (allowed: {})",
                agentId, fromState, targetState, VALID_TRANSITIONS.get(fromState));
            return false;
        }
        if (currentState.compareAndSet(fromState, targetState)) {
            AiPlayerMod.info("state", "[{}] State transition: {} → {}{}",
                agentId, fromState, targetState,
                reason != null ? " (reason: " + reason + ")" : "");
            if (eventBus != null) {
                eventBus.publish(new StateTransitionEvent(agentId, fromState, targetState, reason));
            }

            return true;
        } else {
            LOGGER.warn("[{}] State transition failed: concurrent modification", agentId);
            return false;
        }
    }

        public void forceTransition(AgentState targetState, String reason) {
        if (targetState == null) return;

        AgentState fromState = currentState.getAndSet(targetState);
        LOGGER.warn("[{}] FORCED state transition: {} → {} (reason: {})",
            agentId, fromState, targetState, reason);

        if (eventBus != null) {
            eventBus.publish(new StateTransitionEvent(agentId, fromState, targetState,
                "FORCED: " + reason));
        }
    }

        public void reset() {
        AgentState previous = currentState.getAndSet(AgentState.IDLE);
        if (previous != AgentState.IDLE) {
            AiPlayerMod.info("state", "[{}] State machine reset: {} → IDLE", agentId, previous);
            if (eventBus != null) {
                eventBus.publish(new StateTransitionEvent(agentId, previous, AgentState.IDLE, "reset"));
            }
        }
    }

        public boolean canAcceptCommands() {
        return currentState.get().canAcceptCommands();
    }

        public boolean isActive() {
        return currentState.get().isActive();
    }

        public Set<AgentState> getValidTransitions() {
        Set<AgentState> valid = VALID_TRANSITIONS.get(currentState.get());
        return valid != null ? EnumSet.copyOf(valid) : EnumSet.noneOf(AgentState.class);
    }

        public String getAgentId() {
        return agentId;
    }
}
