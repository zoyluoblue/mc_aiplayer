package com.aiplayer.execution;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.actions.BaseAction;
import com.aiplayer.action.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingInterceptor implements ActionInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public boolean beforeAction(BaseAction action, ActionContext context) {
        AiPlayerMod.info("action", "[ACTION START] {}", action.getDescription());
        return true;
    }

    @Override
    public void afterAction(BaseAction action, ActionResult result, ActionContext context) {
        if (result.isSuccess()) {
            AiPlayerMod.info("action", "[ACTION COMPLETE] {} - Success: {}",
                action.getDescription(), result.getMessage());
        } else {
            LOGGER.warn("[ACTION FAILED] {} - Reason: {}",
                action.getDescription(), result.getMessage());
        }
    }

    @Override
    public boolean onError(BaseAction action, Exception exception, ActionContext context) {
        LOGGER.error("[ACTION ERROR] {} - Exception: {}",
            action.getDescription(), exception.getMessage(), exception);
        return false;
    }

    @Override
    public int getPriority() {
        return 1000;
    }

    @Override
    public String getName() {
        return "LoggingInterceptor";
    }
}
