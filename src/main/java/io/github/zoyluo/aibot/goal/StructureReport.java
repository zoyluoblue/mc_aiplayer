package io.github.zoyluo.aibot.goal;

public record StructureReport(
        String anchor,
        int expected,
        int matched,
        int placed,
        int skipped,
        int mismatched
) {
    public StructureReport {
        anchor = anchor == null ? "" : anchor;
        expected = Math.max(0, expected);
        matched = Math.max(0, matched);
        placed = Math.max(0, placed);
        skipped = Math.max(0, skipped);
        mismatched = Math.max(0, mismatched);
    }
}
