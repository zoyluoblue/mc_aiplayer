package com.aiplayer.execution;

import com.aiplayer.action.actions.BaseAction;
import com.aiplayer.action.ActionResult;

public interface ActionInterceptor {

        default boolean beforeAction(BaseAction action, ActionContext context) {
        return true;
    }

        default void afterAction(BaseAction action, ActionResult result, ActionContext context) {
    }

        default boolean onError(BaseAction action, Exception exception, ActionContext context) {
        return false;
    }

        default int getPriority() {
        return 0;
    }

        default String getName() {
        return getClass().getSimpleName();
    }
}
