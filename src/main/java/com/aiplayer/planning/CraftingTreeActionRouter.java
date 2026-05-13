package com.aiplayer.planning;

import com.aiplayer.recipe.CraftingTree;
import com.aiplayer.recipe.MaterialRequirement;
import com.aiplayer.recipe.MiningResource;

import java.util.ArrayList;
import java.util.List;

public final class CraftingTreeActionRouter {
    public List<Route> route(CraftingTree tree) {
        if (tree == null || !tree.success()) {
            return List.of();
        }
        List<Route> routes = new ArrayList<>();
        for (CraftingTree.TreeStep step : tree.steps()) {
            routes.add(route(step));
        }
        return List.copyOf(routes);
    }

    public Route route(CraftingTree.TreeStep step) {
        if (step == null || step.output() == null) {
            return Route.unsupported("", "unknown", "tree step 为空");
        }
        return switch (safe(step.action())) {
            case "withdraw_chest" -> withdraw(step);
            case "gather" -> gather(step);
            case "craft" -> craft(step);
            default -> Route.unsupported(step.id(), step.action(), "unsupported_action_route:" + step.action());
        };
    }

    public List<PlanStep> toPlanSteps(CraftingTree tree) {
        List<PlanStep> steps = new ArrayList<>();
        if (tree == null || !tree.success()) {
            return List.of();
        }
        for (CraftingTree.TreeStep step : tree.steps()) {
            steps.add(toPlanStep(step));
        }
        return List.copyOf(steps);
    }

    public PlanStep toPlanStep(CraftingTree.TreeStep step) {
        Route route = route(step);
        if (!route.supported()) {
            throw new IllegalArgumentException(route.reason());
        }
        return route.toPlanStep();
    }

    private Route withdraw(CraftingTree.TreeStep step) {
        MaterialRequirement output = step.output();
        return Route.supported(
            step.id(),
            step.action(),
            "withdraw_chest",
            output.getItem(),
            null,
            output.getCount(),
            step.source(),
            null,
            "从附近箱子取出材料"
        );
    }

    private Route gather(CraftingTree.TreeStep step) {
        MaterialRequirement output = step.output();
        String source = step.source();
        String executorStep = switch (safe(source)) {
            case "tree" -> "gather_tree";
            case "stone" -> "gather_stone";
            default -> "gather";
        };
        String reason = MiningResource.findByItemOrSource(output.getItem(), source).isPresent()
            ? "采集矿物或基础方块"
            : "采集基础资源";
        return Route.supported(
            step.id(),
            step.action(),
            executorStep,
            output.getItem(),
            source,
            output.getCount(),
            null,
            null,
            reason
        );
    }

    private Route craft(CraftingTree.TreeStep step) {
        MaterialRequirement output = step.output();
        String station = safe(step.station()).isBlank() ? "inventory" : step.station();
        String executorStep = "water_source".equals(station)
            ? "fill_water"
            : "inventory".equals(station) ? "craft_inventory" : "craft_station";
        String reason = isSmeltingStation(station)
            ? "使用熔炼工作站处理材料"
            : "按本地配方验证后合成";
        return Route.supported(
            step.id(),
            step.action(),
            executorStep,
            output.getItem(),
            "fill_water".equals(executorStep) ? "water_source" : null,
            output.getCount(),
            null,
            station,
            reason
        );
    }

    private static boolean isSmeltingStation(String station) {
        return "furnace".equals(station)
            || "blast_furnace".equals(station)
            || "smoker".equals(station)
            || "campfire".equals(station);
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    public record Route(
        String treeStepId,
        String treeAction,
        String executorStep,
        String item,
        String resource,
        int count,
        String from,
        String station,
        boolean supported,
        String reason
    ) {
        static Route supported(
            String treeStepId,
            String treeAction,
            String executorStep,
            String item,
            String resource,
            int count,
            String from,
            String station,
            String reason
        ) {
            return new Route(treeStepId, treeAction, executorStep, item, resource, Math.max(1, count), from, station, true, reason);
        }

        static Route unsupported(String treeStepId, String treeAction, String reason) {
            return new Route(treeStepId, treeAction, "", "", "", 0, null, null, false, reason);
        }

        PlanStep toPlanStep() {
            if (!supported) {
                throw new IllegalStateException(reason);
            }
            return switch (executorStep) {
                case "withdraw_chest" -> PlanStep.withdraw(item, count, from);
                case "gather_tree", "gather_stone", "gather" -> PlanStep.gather(resource, item, count);
                case "fill_water", "craft_inventory", "craft_station" -> PlanStep.craft(item, count, station);
                default -> throw new IllegalStateException("unsupported_action_route:" + executorStep);
            };
        }
    }
}
