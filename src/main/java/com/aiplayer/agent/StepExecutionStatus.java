package com.aiplayer.agent;

public enum StepExecutionStatus {
    RUNNING,
    SUCCESS,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
    NEED_REPLAN
}
