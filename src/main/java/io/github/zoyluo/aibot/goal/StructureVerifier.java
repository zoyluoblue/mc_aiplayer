package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.task.BlueprintSchema;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public final class StructureVerifier {
    private StructureVerifier() {
    }

    public static StructureReport verify(ServerWorld world,
                                         BlueprintSchema blueprint,
                                         BlockPos anchor,
                                         int placed,
                                         int skipped) {
        if (blueprint == null || anchor == null || blueprint.placements() == null) {
            return new StructureReport("", 0, 0, placed, skipped, 0);
        }
        int matched = 0;
        int mismatched = 0;
        for (BlueprintSchema.BlockPlacement placement : blueprint.placements()) {
            if (matches(world, anchor, placement)) {
                matched++;
            } else {
                mismatched++;
            }
        }
        return new StructureReport(compact(anchor), blueprint.placements().size(), matched, placed, skipped, mismatched);
    }

    public static boolean matches(ServerWorld world,
                                  BlockPos anchor,
                                  BlueprintSchema.BlockPlacement placement) {
        if (placement == null || placement.blockId() == null) {
            return false;
        }
        Identifier expectedId;
        try {
            expectedId = Identifier.of(placement.blockId());
        } catch (RuntimeException exception) {
            return false;
        }
        if (world == null || anchor == null) {
            return false;
        }
        BlockPos pos = anchor.add(placement.dx(), placement.dy(), placement.dz());
        var state = world.getBlockState(pos);
        if ("minecraft:air".equals(placement.blockId())) {
            return state.isAir();
        }
        if (placement.palette() != null && !placement.palette().isBlank()) {
            return MaterialPalette.matchesBlock(state, placement.palette());
        }
        Block expected = Registries.BLOCK.getOptionalValue(expectedId).orElse(null);
        return expected != null && state.isOf(expected);
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
