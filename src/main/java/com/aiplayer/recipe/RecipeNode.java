package com.aiplayer.recipe;

import java.util.List;

public final class RecipeNode {
    private final String type;
    private final MaterialRequirement output;
    private final List<MaterialRequirement> requires;
    private final String station;
    private final String source;
    private final String note;

    private RecipeNode(
        String type,
        MaterialRequirement output,
        List<MaterialRequirement> requires,
        String station,
        String source,
        String note
    ) {
        this.type = type;
        this.output = output;
        this.requires = List.copyOf(requires);
        this.station = station;
        this.source = source;
        this.note = note;
    }

    public static RecipeNode withdraw(MaterialRequirement output, String source) {
        return new RecipeNode("withdraw", output, List.of(), null, source, "从附近可访问箱子取出");
    }

    public static RecipeNode gather(MaterialRequirement output, String source) {
        return new RecipeNode("gather", output, List.of(), null, source, "基础资源采集");
    }

    public static RecipeNode craft(MaterialRequirement output, List<MaterialRequirement> requires, String station, String note) {
        return new RecipeNode("craft", output, requires, station, null, note);
    }

    public String getType() {
        return type;
    }

    public MaterialRequirement getOutput() {
        return output;
    }

    public List<MaterialRequirement> getRequires() {
        return requires;
    }

    public String getStation() {
        return station;
    }

    public String getSource() {
        return source;
    }

    public String getNote() {
        return note;
    }

    public String toUserText() {
        return switch (type) {
            case "withdraw" -> "取出 " + output + "，来源：" + source;
            case "gather" -> "采集 " + output + "，来源：" + source;
            case "craft" -> "合成 " + output + "，工作站：" + station + "，材料：" + requires;
            default -> type + " " + output;
        };
    }
}
