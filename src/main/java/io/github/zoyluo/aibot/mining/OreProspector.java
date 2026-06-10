package io.github.zoyluo.aibot.mining;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Set;
import java.util.function.Predicate;

/**
 * 探矿器(移植自玩家 magic mod 的 HelmetOreLocator):在大半径立方体内逐区块 / section 扫描,
 * 返回**最近的目标方块坐标**——供 OreDigTask 大范围定位矿脉、GatherQuotaTask 大范围定位树木(跨海拔/出高原)等共用。
 *
 * 高效关键(同参考):用 {@link ChunkSection#hasAny}(palette 级,不逐方块)快速跳过不含目标的 section,
 * 只深入含目标 section 精扫;故 64~128 格也不卡。仅扫**已加载区块**(getChunk FULL, create=false),调用方限频护 TPS。
 */
public final class OreProspector {
    private OreProspector() {
    }

    /** 在 origin 周围 range 立方体的已加载区块内,找最近的目标矿;无则 null。全程主线程(只读世界数据)。 */
    public static BlockPos nearest(ServerWorld world, BlockPos origin, Set<Block> targets, int range) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        return nearest(world, origin, range, state -> OreScan.isOre(state, targets));
    }

    /**
     * 通用版:找最近的"满足 match 的方块"——找矿用 OreScan.isOre、找树用"原木集合 contains"等皆可复用。
     * palette 级 section.hasAny(match) 快速跳过不含目标的 section,故大半径也不卡。
     */
    public static BlockPos nearest(ServerWorld world, BlockPos origin, int range, Predicate<BlockState> match) {
        return nearest(world, origin, range, match, null);
    }

    /**
     * 带坐标过滤版:posFilter 拒绝的坐标跳过(如调用方拉黑"走不到的目标"防止反复 prospect 同一个死循环)。
     * posFilter 为 null 时不过滤。
     */
    public static BlockPos nearest(ServerWorld world, BlockPos origin, int range,
                                   Predicate<BlockState> match, Predicate<BlockPos> posFilter) {
        int minX = origin.getX() - range;
        int maxX = origin.getX() + range;
        int minY = Math.max(world.getBottomY(), origin.getY() - range);
        int maxY = Math.min(world.getBottomY() + world.getHeight() - 1, origin.getY() + range);
        int minZ = origin.getZ() - range;
        int maxZ = origin.getZ() + range;
        int minCX = ChunkSectionPos.getSectionCoord(minX);
        int maxCX = ChunkSectionPos.getSectionCoord(maxX);
        int minCZ = ChunkSectionPos.getSectionCoord(minZ);
        int maxCZ = ChunkSectionPos.getSectionCoord(maxZ);
        int minSY = ChunkSectionPos.getSectionCoord(minY);
        int maxSY = ChunkSectionPos.getSectionCoord(maxY);

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Chunk raw = world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(raw instanceof WorldChunk chunk)) {
                    continue; // 未加载,跳过
                }
                int startX = chunk.getPos().getStartX();
                int startZ = chunk.getPos().getStartZ();
                int lMinX = Math.max(minX, startX) - startX;
                int lMaxX = Math.min(maxX, startX + 15) - startX;
                int lMinZ = Math.max(minZ, startZ) - startZ;
                int lMaxZ = Math.min(maxZ, startZ + 15) - startZ;
                for (int sy = minSY; sy <= maxSY; sy++) {
                    int idx = world.sectionCoordToIndex(sy);
                    if (idx < 0 || idx >= chunk.getSectionArray().length) {
                        continue;
                    }
                    ChunkSection section = chunk.getSection(idx);
                    if (section == null || section.isEmpty()
                            || !section.hasAny(match)) {
                        continue; // palette 级快速跳过不含目标的 section
                    }
                    int startY = ChunkSectionPos.getBlockCoord(sy);
                    int lMinY = Math.max(minY, startY) - startY;
                    int lMaxY = Math.min(maxY, startY + 15) - startY;
                    for (int ly = lMinY; ly <= lMaxY; ly++) {
                        int y = startY + ly;
                        for (int lx = lMinX; lx <= lMaxX; lx++) {
                            int x = startX + lx;
                            for (int lz = lMinZ; lz <= lMaxZ; lz++) {
                                int z = startZ + lz;
                                BlockState state = section.getBlockState(lx, ly, lz);
                                if (!match.test(state)) {
                                    continue;
                                }
                                double d = origin.getSquaredDistance(x, y, z);
                                if (d >= bestDist) {
                                    continue;
                                }
                                BlockPos pos = new BlockPos(x, y, z);
                                if (posFilter != null && !posFilter.test(pos)) {
                                    continue; // 被调用方拉黑(如反复走不到的目标)
                                }
                                bestDist = d;
                                best = pos;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }
}
