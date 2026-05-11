package com.aiplayer.execution;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.actions.BaseAction;
import com.aiplayer.action.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InterceptorChain {

    private static final Logger LOGGER = LoggerFactory.getLogger(InterceptorChain.class);

        private final CopyOnWriteArrayList<ActionInterceptor> interceptors;

    public InterceptorChain() {
        this.interceptors = new CopyOnWriteArrayList<>();
    }

        public void addInterceptor(ActionInterceptor interceptor) {
        if (interceptor == null) {
            throw new IllegalArgumentException("Interceptor cannot be null");
        }

        interceptors.add(interceptor);
        sortInterceptors();

        LOGGER.debug("Added interceptor: {} (priority: {})",
            interceptor.getName(), interceptor.getPriority());
    }

        public boolean removeInterceptor(ActionInterceptor interceptor) {
        boolean removed = interceptors.remove(interceptor);
        if (removed) {
            LOGGER.debug("Removed interceptor: {}", interceptor.getName());
        }
        return removed;
    }

        private void sortInterceptors() {
        List<ActionInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort(Comparator.comparingInt(ActionInterceptor::getPriority).reversed());
        interceptors.clear();
        interceptors.addAll(sorted);
    }

        public boolean executeBeforeAction(BaseAction action, ActionContext context) {
        for (ActionInterceptor interceptor : interceptors) {
            try {
                if (!interceptor.beforeAction(action, context)) {
                    AiPlayerMod.info("action", "Action cancelled by interceptor: {}", interceptor.getName());
                    return false;
                }
            } catch (Exception e) {
                LOGGER.error("Error in interceptor {} beforeAction: {}",
                    interceptor.getName(), e.getMessage(), e);
            }
        }
        return true;
    }

        public void executeAfterAction(BaseAction action, ActionResult result, ActionContext context) {
        List<ActionInterceptor> reversed = new ArrayList<>(interceptors);
        Collections.reverse(reversed);

        for (ActionInterceptor interceptor : reversed) {
            try {
                interceptor.afterAction(action, result, context);
            } catch (Exception e) {
                LOGGER.error("Error in interceptor {} afterAction: {}",
                    interceptor.getName(), e.getMessage(), e);
            }
        }
    }

        public boolean executeOnError(BaseAction action, Exception exception, ActionContext context) {
        List<ActionInterceptor> reversed = new ArrayList<>(interceptors);
        Collections.reverse(reversed);

        boolean suppressed = false;
        for (ActionInterceptor interceptor : reversed) {
            try {
                if (interceptor.onError(action, exception, context)) {
                    LOGGER.debug("Exception suppressed by interceptor: {}", interceptor.getName());
                    suppressed = true;
                }
            } catch (Exception e) {
                LOGGER.error("Error in interceptor {} onError: {}",
                    interceptor.getName(), e.getMessage(), e);
            }
        }
        return suppressed;
    }

        public void clear() {
        interceptors.clear();
        LOGGER.debug("InterceptorChain cleared");
    }

        public int size() {
        return interceptors.size();
    }

        public List<ActionInterceptor> getInterceptors() {
        return Collections.unmodifiableList(new ArrayList<>(interceptors));
    }
}
