package com.aiplayer.execution;

import com.aiplayer.di.ServiceContainer;
import com.aiplayer.event.EventBus;

import java.util.Optional;

public class ActionContext {

    private final ServiceContainer serviceContainer;
    private final EventBus eventBus;
    private final AgentStateMachine stateMachine;
    private final InterceptorChain interceptorChain;

        public ActionContext(
            ServiceContainer serviceContainer,
            EventBus eventBus,
            AgentStateMachine stateMachine,
            InterceptorChain interceptorChain) {
        this.serviceContainer = serviceContainer;
        this.eventBus = eventBus;
        this.stateMachine = stateMachine;
        this.interceptorChain = interceptorChain;
    }

        public ServiceContainer getServiceContainer() {
        return serviceContainer;
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

        public <T> T getService(Class<T> serviceType) {
        return serviceContainer.getService(serviceType);
    }

        public <T> Optional<T> findService(Class<T> serviceType) {
        return serviceContainer.findService(serviceType);
    }

        public <T> void publishEvent(T event) {
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }

        public AgentState getCurrentState() {
        return stateMachine != null ? stateMachine.getCurrentState() : AgentState.IDLE;
    }

        public static class Builder {
        private ServiceContainer serviceContainer;
        private EventBus eventBus;
        private AgentStateMachine stateMachine;
        private InterceptorChain interceptorChain;

        public Builder serviceContainer(ServiceContainer serviceContainer) {
            this.serviceContainer = serviceContainer;
            return this;
        }

        public Builder eventBus(EventBus eventBus) {
            this.eventBus = eventBus;
            return this;
        }

        public Builder stateMachine(AgentStateMachine stateMachine) {
            this.stateMachine = stateMachine;
            return this;
        }

        public Builder interceptorChain(InterceptorChain interceptorChain) {
            this.interceptorChain = interceptorChain;
            return this;
        }

        public ActionContext build() {
            return new ActionContext(serviceContainer, eventBus, stateMachine, interceptorChain);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
