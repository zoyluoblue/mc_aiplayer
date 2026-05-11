package com.aiplayer.planning;

public final class PlanStep {
    private String step;
    private String item;
    private String resource;
    private int count;
    private String from;
    private String station;
    private String reason;

    public PlanStep() {
    }

    private PlanStep(String step, String item, String resource, int count, String from, String station, String reason) {
        this.step = step;
        this.item = item;
        this.resource = resource;
        this.count = Math.max(1, count);
        this.from = from;
        this.station = station;
        this.reason = reason;
    }

    public static PlanStep withdraw(String item, int count, String from) {
        return new PlanStep("withdraw_chest", item, null, count, from, null, "从附近箱子取出材料");
    }

    public static PlanStep gather(String resource, String item, int count) {
        String step = switch (resource) {
            case "tree" -> "gather_tree";
            case "stone" -> "gather_stone";
            default -> "gather";
        };
        return new PlanStep(step, item, resource, count, null, null, "采集基础资源");
    }

    public static PlanStep craft(String item, int count, String station) {
        if ("water_source".equals(station)) {
            return new PlanStep("fill_water", item, "water_source", count, null, station, "用空桶从水源装水");
        }
        String step = "inventory".equals(station) ? "craft_inventory" : "craft_station";
        return new PlanStep(step, item, null, count, null, station, "按本地配方验证后合成");
    }

    public String getStep() {
        return step;
    }

    public void setStep(String step) {
        this.step = step;
    }

    public String getItem() {
        return item;
    }

    public void setItem(String item) {
        this.item = item;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = Math.max(1, count);
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getStation() {
        return station;
    }

    public void setStation(String station) {
        this.station = station;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
