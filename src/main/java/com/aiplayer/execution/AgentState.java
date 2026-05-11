package com.aiplayer.execution;

public enum AgentState {

        IDLE("Idle", "Agent is waiting for commands"),

        PLANNING("Planning", "Processing command with AI"),

        EXECUTING("Executing", "Performing actions"),

        PAUSED("Paused", "Execution temporarily suspended"),

        COMPLETED("Completed", "All tasks finished successfully"),

        FAILED("Failed", "Encountered an error");

    private final String displayName;
    private final String description;

    AgentState(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

        public String getDisplayName() {
        return displayName;
    }

        public String getDescription() {
        return description;
    }

        public boolean canAcceptCommands() {
        return this == IDLE || this == COMPLETED || this == FAILED;
    }

        public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

        public boolean isActive() {
        return this == PLANNING || this == EXECUTING;
    }
}
