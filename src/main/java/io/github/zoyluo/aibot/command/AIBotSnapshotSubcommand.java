package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.zoyluo.aibot.auth.BotAuthorizationGate;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * 真实地形捕获器 /aibot snapshot [radius]——把 bot 周围 radius 立方体的"非凡方块"
 * dump 成可复现的 setRel 代码 + 紧凑统计,写到 reports/snapshot_<坐标>.txt。
 * 用途:real_diamond/real_iron 真实失败那刻一键抓现场 → 粘成确定性 L1 场景 → 把 flaky 真实失败真修。
 */
public final class AIBotSnapshotSubcommand {
    private static final int DEFAULT_RADIUS = 8;
    private static final int MAX_RADIUS = 24;

    private static final Set<Block> SKIP = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
            Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
            Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.MUD,
            Blocks.SAND, Blocks.GRAVEL, Blocks.SANDSTONE, Blocks.NETHERRACK);

    private AIBotSnapshotSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("snapshot")
                .executes(context -> run(context, DEFAULT_RADIUS))
                .then(argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                        .executes(context -> run(context, IntegerArgumentType.getInteger(context, "radius"))));
    }

    private static int run(CommandContext<ServerCommandSource> context, int radius) {
        ServerCommandSource source = context.getSource();
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:snapshot")) {
            return 0;
        }
        Optional<AIPlayerEntity> botOpt = selectBot(source);
        if (botOpt.isEmpty()) {
            source.sendError(Text.literal("[AIBot Snapshot] no bot — /aibot spawn <name> first"));
            return 0;
        }
        AIPlayerEntity bot = botOpt.get();
        if (!CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "admin_snapshot").allowed()) {
            source.sendError(Text.literal("[AIBot Snapshot] unavailable in strict_survival; enable operator hiddenBlockScan explicitly"));
            return 0;
        }
        ServerWorld world = bot.getServerWorld();
        BlockPos center = bot.getBlockPos();

        StringBuilder code = new StringBuilder();
        Map<String, Integer> tally = new TreeMap<>();
        Map<String, Integer> notable = new LinkedHashMap<>();
        int written = 0;

        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -radius, -radius),
                center.add(radius, radius, radius))) {
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            boolean fluid = !state.getFluidState().isEmpty();
            if (SKIP.contains(block) && !fluid) {
                continue;
            }
            Identifier id = Registries.BLOCK.getId(block);
            String key = id.toString();
            tally.merge(key, 1, Integer::sum);
            if (fluid || isOreLike(block, id)) {
                notable.merge(key, 1, Integer::sum);
            }
            int dx = pos.getX() - center.getX();
            int dy = pos.getY() - center.getY();
            int dz = pos.getZ() - center.getZ();
            code.append(String.format(
                    "        setRel(world, origin, %d, %d, %d, \"%s\");%n", dx, dy, dz, key));
            written++;
        }

        String header = buildHeader(world, bot, center, radius, written, notable);
        Path file;
        try {
            file = writeReport(center, header, code.toString(), tally);
        } catch (IOException e) {
            source.sendError(Text.literal("[AIBot Snapshot] write failed: " + e.getMessage()));
            return 0;
        }
        final int total = written;
        source.sendFeedback(() -> Text.literal("[AIBot Snapshot] " + total + " notable blocks @ "
                + center.toShortString() + " r=" + radius + " notable=" + notable + " -> " + file), false);
        return 1;
    }

    private static boolean isOreLike(Block block, Identifier id) {
        return io.github.zoyluo.aibot.mining.OreScan.isOreBlock(block)
                || id.getPath().endsWith("_ore") || id.getPath().contains("ancient_debris");
    }

    private static String buildHeader(ServerWorld world, AIPlayerEntity bot, BlockPos center,
                                      int radius, int written, Map<String, Integer> notable) {
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING,
                center.getX(), center.getZ());
        long seed = world.getSeed();
        return "// === AIBot terrain snapshot ===\n"
                + "// bot=" + bot.getGameProfile().getName()
                + "  center=" + center.getX() + "," + center.getY() + "," + center.getZ()
                + "  yaw=" + Math.round(bot.getYaw()) + "  pitch=" + Math.round(bot.getPitch()) + "\n"
                + "// dimension=" + world.getRegistryKey().getValue()
                + "  seed=" + seed + "  surface_y=" + surfaceY + "\n"
                + "// radius=" + radius + "  notable_blocks=" + written + "  notable_kinds=" + notable + "\n"
                + "// PASTE 下面整段进 testmod verification fixture 的 assignCapturedX。\n";
    }

    private static Path writeReport(BlockPos center, String header, String code,
                                    Map<String, Integer> tally) throws IOException {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path reports = (gameDir.getParent() != null ? gameDir.getParent() : gameDir).resolve("reports");
        Files.createDirectories(reports);
        Path file = reports.resolve(String.format("snapshot_%d_%d_%d.txt",
                center.getX(), center.getY(), center.getZ()));
        StringBuilder out = new StringBuilder();
        out.append(header).append('\n');
        out.append("// ---- TALLY ----\n");
        tally.forEach((k, v) -> out.append("//   ").append(k).append(" x").append(v).append('\n'));
        out.append("\n    // ---- SETBLOCK CODE ----\n").append(code);
        Files.writeString(file, out.toString());
        return file;
    }

    private static Optional<AIPlayerEntity> selectBot(ServerCommandSource source) {
        return Optional.ofNullable(source.getPlayer())
                .flatMap(p -> AIPlayerManager.INSTANCE.botOf(p.getUuid()))
                .or(() -> AIPlayerManager.INSTANCE.all().stream().findFirst());
    }
}
