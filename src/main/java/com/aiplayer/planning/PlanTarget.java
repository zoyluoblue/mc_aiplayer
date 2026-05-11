package com.aiplayer.planning;

public final class PlanTarget {
    private String item;
    private int count;

    public PlanTarget() {
    }

    public PlanTarget(String item, int count) {
        this.item = item;
        this.count = Math.max(1, count);
    }

    public String getItem() {
        return item;
    }

    public void setItem(String item) {
        this.item = item;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = Math.max(1, count);
    }
}
