package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class StoneAcquisitionPolicy {
    private StoneAcquisitionPolicy() {
    }

    public static ScanBuilder builder() {
        return new ScanBuilder();
    }

    public static List<Candidate> orderForPathChecks(List<Candidate> candidates) {
        List<Candidate> ordered = new ArrayList<>(candidates == null ? List.of() : candidates);
        ordered.sort(Comparator
            .comparingDouble(Candidate::distanceSq)
            .thenComparing(Comparator.comparingInt(Candidate::exposedAir).reversed()));
        return ordered;
    }

    public enum Category {
        DIRECT_WORKABLE("direct_workable", 0),
        REACHABLE_STAND("reachable_stand", 1),
        VISIBLE_UNREACHABLE("visible_unreachable", 2),
        NOT_VISIBLE("not_visible", 3);

        private final String label;
        private final int rank;

        Category(String label, int rank) {
            this.label = label;
            this.rank = rank;
        }

        public String label() {
            return label;
        }

        int rank() {
            return rank;
        }
    }

    public record Candidate(
        BlockPos stonePos,
        BlockPos standPos,
        Category category,
        double distanceSq,
        int exposedAir
    ) {
        public Candidate {
            if (stonePos != null) {
                stonePos = stonePos.immutable();
            }
            if (standPos != null) {
                standPos = standPos.immutable();
            }
            category = category == null ? Category.NOT_VISIBLE : category;
        }

        public String targetReason() {
            return switch (category) {
                case DIRECT_WORKABLE -> "stone_direct";
                case REACHABLE_STAND -> "stone_reachable_stand";
                case VISIBLE_UNREACHABLE -> "stone_visible_unreachable";
                case NOT_VISIBLE -> "stone_not_visible";
            };
        }
    }

    public record ScanResult(
        Candidate selected,
        int candidates,
        int directWorkable,
        int reachableStand,
        int visibleUnreachable,
        int notVisible,
        int rejected,
        int pathChecks,
        int pathBudgetSkipped,
        Map<String, Integer> rejectionReasons
    ) {
        public boolean hasUsableTarget() {
            return selected != null
                && (selected.category() == Category.DIRECT_WORKABLE || selected.category() == Category.REACHABLE_STAND);
        }

        public String descentReason() {
            if (directWorkable > 0 || reachableStand > 0) {
                return "已有可采集裸露石头";
            }
            if (visibleUnreachable > 0) {
                return "附近有裸露石头但没有可达站位";
            }
            if (notVisible > 0) {
                return "附近只有未暴露石头";
            }
            if (rejected > 0) {
                return "附近石头候选均被拒绝";
            }
            return "附近没有发现石头候选";
        }

        public String toLogText() {
            return "stoneScan{decision=" + (hasUsableTarget() ? "visible_target" : "stair_descent")
                + ", reason=" + descentReason()
                + ", selected=" + selectedText()
                + ", candidates=" + candidates
                + ", direct=" + directWorkable
                + ", reachable=" + reachableStand
                + ", visibleUnreachable=" + visibleUnreachable
                + ", hidden=" + notVisible
                + ", rejected=" + rejected
                + ", pathChecks=" + pathChecks
                + ", pathBudgetSkipped=" + pathBudgetSkipped
                + ", rejectionReasons=" + rejectionReasons
                + "}";
        }

        private String selectedText() {
            if (selected == null || selected.stonePos() == null) {
                return "none";
            }
            return selected.stonePos().toShortString()
                + "/" + selected.category().label()
                + "/stand=" + (selected.standPos() == null ? "direct" : selected.standPos().toShortString());
        }
    }

    public static final class ScanBuilder {
        private final Map<String, Integer> rejectionReasons = new TreeMap<>();
        private Candidate selected;
        private int candidates;
        private int directWorkable;
        private int reachableStand;
        private int visibleUnreachable;
        private int notVisible;
        private int rejected;
        private int pathChecks;
        private int pathBudgetSkipped;

        private ScanBuilder() {
        }

        public void recordCandidate(Candidate candidate) {
            if (candidate == null) {
                return;
            }
            candidates++;
            switch (candidate.category()) {
                case DIRECT_WORKABLE -> directWorkable++;
                case REACHABLE_STAND -> reachableStand++;
                case VISIBLE_UNREACHABLE -> visibleUnreachable++;
                case NOT_VISIBLE -> notVisible++;
            }
            if (isUsable(candidate) && isBetter(candidate, selected)) {
                selected = candidate;
            }
        }

        public void recordRejected(String reason) {
            rejected++;
            rejectionReasons.merge(reason == null || reason.isBlank() ? "unknown" : reason, 1, Integer::sum);
        }

        public void recordPathCheck() {
            pathChecks++;
        }

        public void recordPathBudgetSkipped() {
            pathBudgetSkipped++;
        }

        public int pathChecks() {
            return pathChecks;
        }

        public ScanResult build() {
            return new ScanResult(
                selected,
                candidates,
                directWorkable,
                reachableStand,
                visibleUnreachable,
                notVisible,
                rejected,
                pathChecks,
                pathBudgetSkipped,
                Map.copyOf(rejectionReasons)
            );
        }

        private static boolean isUsable(Candidate candidate) {
            return candidate.category() == Category.DIRECT_WORKABLE || candidate.category() == Category.REACHABLE_STAND;
        }

        private static boolean isBetter(Candidate candidate, Candidate current) {
            if (current == null) {
                return true;
            }
            if (candidate.category().rank() != current.category().rank()) {
                return candidate.category().rank() < current.category().rank();
            }
            int distanceCompare = Double.compare(candidate.distanceSq(), current.distanceSq());
            if (distanceCompare != 0) {
                return distanceCompare < 0;
            }
            return candidate.exposedAir() > current.exposedAir();
        }
    }
}
