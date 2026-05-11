package com.aiplayer.plugin;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.actions.BaseAction;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.execution.ActionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionRegistry.class);

    private static final ActionRegistry INSTANCE = new ActionRegistry();

        private final ConcurrentHashMap<String, FactoryEntry> factories;

        private final ConcurrentHashMap<String, String> actionToPlugin;

    private ActionRegistry() {
        this.factories = new ConcurrentHashMap<>();
        this.actionToPlugin = new ConcurrentHashMap<>();
    }

        public static ActionRegistry getInstance() {
        return INSTANCE;
    }

        public void register(String actionName, ActionFactory factory) {
        register(actionName, factory, 0, "unknown");
    }

        public void register(String actionName, ActionFactory factory, int priority, String pluginId) {
        if (actionName == null || actionName.isBlank()) {
            throw new IllegalArgumentException("Action name cannot be null or blank");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Factory cannot be null");
        }

        String normalizedName = actionName.toLowerCase().trim();

        factories.compute(normalizedName, (key, existing) -> {
            if (existing == null) {
                AiPlayerMod.info("plugin", "Registered action '{}' from plugin '{}' (priority: {})",
                    normalizedName, pluginId, priority);
                actionToPlugin.put(normalizedName, pluginId);
                return new FactoryEntry(factory, priority, pluginId);
            }
            if (priority > existing.priority) {
                AiPlayerMod.info("plugin", "Action '{}' overridden by plugin '{}' (priority {} > {})",
                    normalizedName, pluginId, priority, existing.priority);
                actionToPlugin.put(normalizedName, pluginId);
                return new FactoryEntry(factory, priority, pluginId);
            } else if (priority == existing.priority) {
                LOGGER.warn("Action '{}' already registered by '{}' with same priority, keeping existing",
                    normalizedName, existing.pluginId);
                return existing;
            } else {
                LOGGER.debug("Action '{}' registration from '{}' ignored (priority {} < {})",
                    normalizedName, pluginId, priority, existing.priority);
                return existing;
            }
        });
    }

        public boolean unregister(String actionName) {
        if (actionName == null) return false;

        String normalizedName = actionName.toLowerCase().trim();
        FactoryEntry removed = factories.remove(normalizedName);
        actionToPlugin.remove(normalizedName);

        if (removed != null) {
            AiPlayerMod.info("plugin", "Unregistered action '{}'", normalizedName);
            return true;
        }
        return false;
    }

        public BaseAction createAction(String actionName, AiPlayerEntity aiPlayer, Task task, ActionContext context) {
        if (actionName == null) {
            LOGGER.warn("Cannot create action: actionName is null");
            return null;
        }

        String normalizedName = actionName.toLowerCase().trim();
        FactoryEntry entry = factories.get(normalizedName);

        if (entry == null) {
            LOGGER.warn("No factory registered for action '{}'", normalizedName);
            return null;
        }

        try {
            BaseAction action = entry.factory.create(aiPlayer, task, context);
            LOGGER.debug("Created action '{}' from plugin '{}'", normalizedName, entry.pluginId);
            return action;
        } catch (Exception e) {
            LOGGER.error("Failed to create action '{}': {}", normalizedName, e.getMessage(), e);
            return null;
        }
    }

        public boolean hasAction(String actionName) {
        if (actionName == null) return false;
        return factories.containsKey(actionName.toLowerCase().trim());
    }

        public Set<String> getRegisteredActions() {
        return Collections.unmodifiableSet(factories.keySet());
    }

        public int getActionCount() {
        return factories.size();
    }

        public String getPluginForAction(String actionName) {
        if (actionName == null) return null;
        return actionToPlugin.get(actionName.toLowerCase().trim());
    }

        public void clear() {
        factories.clear();
        actionToPlugin.clear();
        AiPlayerMod.info("plugin", "ActionRegistry cleared");
    }

        public String getActionsAsList() {
        return String.join(", ", getRegisteredActions());
    }

        private static class FactoryEntry {
        final ActionFactory factory;
        final int priority;
        final String pluginId;

        FactoryEntry(ActionFactory factory, int priority, String pluginId) {
            this.factory = factory;
            this.priority = priority;
            this.pluginId = pluginId;
        }
    }
}
