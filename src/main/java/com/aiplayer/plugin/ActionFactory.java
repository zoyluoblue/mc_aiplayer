package com.aiplayer.plugin;

import com.aiplayer.action.actions.BaseAction;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.execution.ActionContext;

@FunctionalInterface
public interface ActionFactory {

        BaseAction create(AiPlayerEntity aiPlayer, Task task, ActionContext context);
}
