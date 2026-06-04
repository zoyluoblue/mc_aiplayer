package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.goal.GoalPlanner;
import io.github.zoyluo.aibot.goal.GoalStep;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * S6:依赖链审计器 `/aibot deplint <bot> <spec>`——离线对指定目标跑 {@link GoalPlanner#plan},
 * 打印步骤树 / 未解析项 / 步数,作为"技能依赖链完整性"的回归尺子(每步开发后用它体检)。
 *
 * spec 形如:mine_ore:diamond:3 / item:cooked_beef:8 / item:bread:3 / pickaxe:iron / armor / workstation / stockpile:iron_ingot:32
 */
public final class AIBotDeplintSubcommand {
    private AIBotDeplintSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("deplint")
                .then(argument("name", StringArgumentType.word())
                        .then(argument("spec", StringArgumentType.greedyString())
                                .executes(AIBotDeplintSubcommand::run)));
    }

    private static int run(CommandContext<ServerCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        Optional<AIPlayerEntity> botOpt = AIPlayerManager.INSTANCE.getByName(name);
        if (botOpt.isEmpty()) {
            context.getSource().sendError(Text.literal("[AIBot] No such bot: " + name));
            return 0;
        }
        String spec = StringArgumentType.getString(context, "spec").trim();
        Goal goal;
        try {
            goal = parseGoal(spec);
        } catch (RuntimeException e) {
            context.getSource().sendError(Text.literal("[deplint] bad spec '" + spec + "': " + e.getMessage()));
            return 0;
        }
        GoalPlanner.GoalPlan plan = GoalPlanner.plan(botOpt.get(), goal);
        List<GoalStep> steps = plan.steps();
        List<String> unresolved = plan.unresolved();
        context.getSource().sendFeedback(() -> Text.literal(
                "[deplint] goal=" + goal + "  steps=" + steps.size() + "  unresolved=" + unresolved.size()), false);
        context.getSource().sendFeedback(() -> Text.literal("[deplint] " + plan.describeSteps()), false);
        if (unresolved.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("[deplint] OK · 链完整,无未解析项"), false);
        } else {
            context.getSource().sendError(Text.literal("[deplint] UNRESOLVED · " + String.join(", ", unresolved)));
        }
        return 1;
    }

    private static Goal parseGoal(String spec) {
        String[] p = spec.split(":");
        String kind = p[0].toLowerCase(Locale.ROOT);
        switch (kind) {
            case "armor":
                return new Goal.Armor();
            case "workstation":
                return new Goal.Workstation();
            case "pickaxe":
                return new Goal.HavePickaxeTier(parseTier(arg(p, 1)));
            case "mine_ore": {
                Block ore = block(arg(p, 1));
                return new Goal.MineOre(OreScan.oreFamily(ore), count(p, 2));
            }
            case "item":
                return new Goal.HaveItem(item(arg(p, 1)), count(p, 2));
            case "stockpile":
                return new Goal.Stockpile(item(arg(p, 1)), count(p, 2));
            default:
                throw new IllegalArgumentException("unknown kind: " + kind
                        + " (mine_ore/item/pickaxe/armor/workstation/stockpile)");
        }
    }

    private static String arg(String[] p, int i) {
        if (i >= p.length) {
            throw new IllegalArgumentException("missing arg #" + i);
        }
        return p[i];
    }

    private static int count(String[] p, int i) {
        return i < p.length ? Integer.parseInt(p[i]) : 1;
    }

    private static int parseTier(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "wood", "wooden" -> ToolTier.WOOD;
            case "stone" -> ToolTier.STONE;
            case "iron" -> ToolTier.IRON;
            case "diamond" -> ToolTier.DIAMOND;
            default -> Integer.parseInt(s);
        };
    }

    // 接受 "diamond" / "diamond_ore" / "minecraft:diamond_ore"
    private static Block block(String name) {
        Block b = Registries.BLOCK.get(id(name));
        if (b == Blocks.AIR && !name.contains(":") && !name.endsWith("_ore")) {
            b = Registries.BLOCK.get(id(name + "_ore"));
        }
        if (b == Blocks.AIR) {
            throw new IllegalArgumentException("unknown ore block: " + name);
        }
        return b;
    }

    private static Item item(String name) {
        return Registries.ITEM.getOptionalValue(id(name))
                .orElseThrow(() -> new IllegalArgumentException("unknown item: " + name));
    }

    private static Identifier id(String name) {
        if (name.contains(":")) {
            String[] x = name.split(":", 2);
            return Identifier.of(x[0], x[1]);
        }
        return Identifier.of("minecraft", name);
    }
}
