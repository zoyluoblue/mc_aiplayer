package com.aiplayer.plugin;

import com.aiplayer.di.ServiceContainer;

public interface ActionPlugin {

        String getPluginId();

        void onLoad(ActionRegistry registry, ServiceContainer container);

        default void onUnload() {
    }

        default int getPriority() {
        return 0;
    }

        default String[] getDependencies() {
        return new String[0];
    }

        default String getVersion() {
        return "1.0.0";
    }

        default String getDescription() {
        return "No description provided";
    }
}
