package com.aiplayer.execution;

import com.aiplayer.planning.PlanStep;

public final class ExecutionStep {
    private final String step;
    private final String item;
    private final String resource;
    private final int count;
    private final String from;
    private final String station;

    public ExecutionStep(PlanStep planStep) {
        this.step = planStep.getStep();
        this.item = planStep.getItem();
        this.resource = planStep.getResource();
        this.count = Math.max(1, planStep.getCount());
        this.from = planStep.getFrom();
        this.station = planStep.getStation();
    }

    public String getStep() {
        return step;
    }

    public String getItem() {
        return item;
    }

    public String getResource() {
        return resource;
    }

    public int getCount() {
        return count;
    }

    public String getFrom() {
        return from;
    }

    public String getStation() {
        return station;
    }

    public String describe() {
        String target = item == null || item.isBlank() ? resource : item;
        return step + " " + target + " x" + count;
    }
}
