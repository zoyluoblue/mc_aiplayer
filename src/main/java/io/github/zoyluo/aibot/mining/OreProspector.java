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

/**
 * 探矿器(移植自玩家 magic mod 的 HelmetOreLocator):在大半径立方体内逐区块 / section 扫描,
 * 返回**最近的目标矿坐标**——供 OreDigTask 在身边扫不到矿时大范围定位、再定向挖过去。
 *
 * 高效关键(同参考):用 {@link ChunkSection#hasAny}(palette 级,不逐方块)快速跳过不含目标矿的 section,
 * 只深入含矿 section 精扫;故 64 格也不卡。仅扫**已加载区块**(getChunk FULL, create=false),调用方限频护 TPS。
 */
public final class OreProspector {
    private OreProspector() {
    }

    /** 在 origin 周围 range 立方体的已加载区块内,找最近的目标矿;无则 null。全程主线程(只读世界数据)。 */
    public static BlockPos nearest(ServerWorld world, BlockPos origin, Set<Block> targets, int range) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
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
                            || !section.hasAny(state -> OreScan.isOre(state, targets))) {
                        continue; // palette 级快速跳过不含目标矿的 section
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
                                if (!OreScan.isOre(state, targets)) {
                                    continue;
                                }
                                double d = origin.getSquaredDistance(x, y, z);
                                if (d < bestDist) {
                                    bestDist = d;
                                    best = new BlockPos(x, y, z);
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }
}
