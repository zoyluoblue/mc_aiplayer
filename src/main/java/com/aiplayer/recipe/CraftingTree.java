package com.aiplayer.recipe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class CraftingTree {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final String graphId;
    private final Target target;
    private final boolean success;
    private final String failureReason;
    private final List<Node> nodes;
    private final List<Edge> edges;
    private final List<TreeStep> steps;

    private CraftingTree(Target target, boolean success, String failureReason, List<Node> nodes, List<Edge> edges, List<TreeStep> steps) {
        this.target = target;
        this.graphId = "tree:" + stableId(target.item()) + ":x" + Math.max(0, target.count());
        this.success = success;
        this.failureReason = failureReason == null ? "" : failureReason;
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.steps = List.copyOf(steps);
    }

    public static CraftingTree fromRecipePlan(RecipePlan plan) {
        if (plan == null) {
            return empty("minecraft:air", 0);
        }
        Target target = new Target(plan.getTarget().getItem(), plan.getTarget().getCount());
        if (!plan.isSuccess()) {
            return failure(target.item(), target.count(), plan.getFailureReason());
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        List<TreeStep> steps = new ArrayList<>();
        addItemNode(nodes, plan.getTarget().getItem(), true);

        int index = 1;
        for (RecipeNode node : plan.getRecipeChain()) {
            String action = actionFor(node);
            MaterialRequirement output = node.getOutput();
            String actionId = actionNodeId(index, action, output.getItem());
            addActionNode(nodes, actionId, action, node.getStation(), node.getSource(), node.getNote());
            addItemNode(nodes, output.getItem(), output.getItem().equals(target.item()));
            edges.add(new Edge(
                actionId,
                itemNodeId(output.getItem()),
                EdgeType.PRODUCES,
                action,
                output.getItem(),
                output.getCount(),
                node.getStation(),
                node.getSource(),
                node.getNote()
            ));

            if ("craft".equals(node.getType())) {
                for (MaterialRequirement requirement : node.getRequires()) {
                    addItemNode(nodes, requirement.getItem(), requirement.getItem().equals(target.item()));
                    edges.add(new Edge(
                        itemNodeId(requirement.getItem()),
                        actionId,
                        EdgeType.REQUIRES,
                        action,
                        requirement.getItem(),
                        requirement.getCount(),
                        node.getStation(),
                        node.getSource(),
                        node.getNote()
                    ));
                }
            } else {
                String source = node.getSource() == null || node.getSource().isBlank() ? "unknown" : node.getSource();
                String sourceId = sourceNodeId(source);
                addSourceNode(nodes, source);
                edges.add(new Edge(
                    sourceId,
                    actionId,
                    EdgeType.REQUIRES,
                    action,
                    source,
                    output.getCount(),
                    node.getStation(),
                    node.getSource(),
                    node.getNote()
                ));
            }

            steps.add(new TreeStep(
                actionId,
                index,
                action,
                output,
                node.getRequires(),
                node.getStation(),
                node.getSource(),
                node.getNote()
            ));
            index++;
        }

        return new CraftingTree(target, true, "", new ArrayList<>(nodes.values()), edges, steps);
    }

    public static CraftingTree empty(String item, int count) {
        String safeItem = item == null || item.isBlank() ? "minecraft:air" : item;
        Map<String, Node> nodes = new LinkedHashMap<>();
        addItemNode(nodes, safeItem, true);
        return new CraftingTree(new Target(safeItem, Math.max(0, count)), true, "", new ArrayList<>(nodes.values()), List.of(), List.of());
    }

    public static CraftingTree failure(String item, int count, String reason) {
        String safeItem = item == null || item.isBlank() ? "minecraft:air" : item;
        Map<String, Node> nodes = new LinkedHashMap<>();
        addItemNode(nodes, safeItem, true);
        return new CraftingTree(
            new Target(safeItem, Math.max(0, count)),
            false,
            reason == null || reason.isBlank() ? "unknown" : reason,
            new ArrayList<>(nodes.values()),
            List.of(),
            List.of()
        );
    }

    public Target target() {
        return target;
    }

    public String graphId() {
        return graphId;
    }

    public boolean success() {
        return success;
    }

    public String failureReason() {
        return failureReason;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<TreeStep> steps() {
        return steps;
    }

    public Optional<TreeStep> stepByIndex(int oneBasedIndex) {
        if (oneBasedIndex <= 0) {
            return Optional.empty();
        }
        return steps.stream()
            .filter(step -> step.index() == oneBasedIndex)
            .findFirst();
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public String toDependencyText() {
        if (!success) {
            return "规划图：配方解析失败，无法生成依赖图。原因：" + failureReason;
        }
        if (steps.isEmpty()) {
            return "规划图：AI 背包 -> " + target.item() + " x" + target.count() + " [already_available]";
        }
        StringBuilder builder = new StringBuilder("规划图：\n");
        for (TreeStep step : steps) {
            builder.append("- ");
            if ("craft".equals(step.action())) {
                builder.append(step.prerequisites());
            } else {
                builder.append("source:").append(step.source());
            }
            builder.append(" -> ")
                .append(step.action());
            if (step.station() != null && !step.station().isBlank()) {
                builder.append(":").append(step.station());
            }
            builder.append(" -> ")
                .append(step.output())
                .append("\n");
        }
        return builder.toString().stripTrailing();
    }

    public String toMermaid() {
        StringBuilder builder = new StringBuilder("graph TD\n");
        for (Node node : nodes) {
            builder.append("  ")
                .append(mermaidId(node.id()))
                .append("[\"")
                .append(escapeMermaid(node.label()))
                .append("\"]\n");
        }
        for (Edge edge : edges) {
            builder.append("  ")
                .append(mermaidId(edge.from()))
                .append(" -->|")
                .append(escapeMermaid(edge.action() + " x" + edge.count()))
                .append("| ")
                .append(mermaidId(edge.to()))
                .append("\n");
        }
        return builder.toString().stripTrailing();
    }

    private static String actionFor(RecipeNode node) {
        if (node == null || node.getType() == null) {
            return "unknown";
        }
        return switch (node.getType()) {
            case "withdraw" -> "withdraw_chest";
            case "gather" -> "gather";
            case "craft" -> "craft";
            default -> node.getType();
        };
    }

    private static void addItemNode(Map<String, Node> nodes, String item, boolean target) {
        String safeItem = item == null || item.isBlank() ? "minecraft:air" : item;
        String id = itemNodeId(safeItem);
        nodes.putIfAbsent(id, new Node(
            id,
            target ? NodeType.TARGET_ITEM : NodeType.ITEM,
            safeItem,
            safeItem,
            null,
            null
        ));
    }

    private static void addActionNode(Map<String, Node> nodes, String id, String action, String station, String source, String note) {
        nodes.putIfAbsent(id, new Node(
            id,
            NodeType.ACTION,
            action,
            null,
            source,
            station == null || station.isBlank() ? note : station
        ));
    }

    private static void addSourceNode(Map<String, Node> nodes, String source) {
        String safeSource = source == null || source.isBlank() ? "unknown" : source;
        String id = sourceNodeId(safeSource);
        nodes.putIfAbsent(id, new Node(
            id,
            NodeType.SOURCE,
            "source:" + safeSource,
            null,
            safeSource,
            null
        ));
    }

    private static String itemNodeId(String item) {
        return "item:" + item;
    }

    private static String sourceNodeId(String source) {
        return "source:" + source;
    }

    private static String actionNodeId(int index, String action, String outputItem) {
        return "action:" + index + ":" + action + ":" + outputItem;
    }

    private static String stableId(String value) {
        return (value == null || value.isBlank() ? "minecraft:air" : value)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_]+", "_")
            .replaceAll("^_+|_+$", "");
    }

    private static String mermaidId(String id) {
        String normalized = id == null ? "node" : id.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_]", "_");
        return "n_" + normalized;
    }

    private static String escapeMermaid(String text) {
        return (text == null ? "" : text).replace("\"", "\\\"");
    }

    public record Target(String item, int count) {
    }

    public record Node(
        String id,
        NodeType type,
        String label,
        String item,
        String source,
        String station
    ) {
    }

    public record Edge(
        String from,
        String to,
        EdgeType type,
        String action,
        String item,
        int count,
        String station,
        String source,
        String note
    ) {
    }

    public record TreeStep(
        String id,
        int index,
        String action,
        MaterialRequirement output,
        List<MaterialRequirement> prerequisites,
        String station,
        String source,
        String note
    ) {
        public TreeStep {
            prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        }
    }

    public enum NodeType {
        TARGET_ITEM,
        ITEM,
        ACTION,
        SOURCE
    }

    public enum EdgeType {
        REQUIRES,
        PRODUCES
    }
}
