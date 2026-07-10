package io.github.zoyluo.aibot.auth;

import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy.Actor;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy.BotTarget;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy.Decision;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy.GlobalTarget;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy.Operation;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Optional;
import java.util.UUID;

/** Minecraft-facing resolver, denial response, and audit adapter for {@link BotAuthorizationPolicy}. */
public final class BotAuthorizationGate {
    public static final BotAuthorizationGate INSTANCE = new BotAuthorizationGate();

    private static final int OPERATOR_LEVEL = 2;
    private static final int TRUSTED_CONSOLE_LEVEL = 4;
    private static final String GENERIC_NOT_FOUND = "[AIBot] 找不到该 Bot 或无权限。";
    private final BotAuthorizationPolicy policy = new BotAuthorizationPolicy();

    private BotAuthorizationGate() {
    }

    public Optional<AIPlayerEntity> resolveAuthorized(ServerCommandSource source,
                                                      String botName,
                                                      Operation operation,
                                                      String channel) {
        Optional<AIPlayerEntity> bot = resolveForSource(source, botName);
        if (bot.isEmpty()) {
            auditMissing(actor(source), source.getName(), botName, operation, channel);
            source.sendError(Text.literal(GENERIC_NOT_FOUND));
            return Optional.empty();
        }
        if (!authorize(source, bot.get(), operation, channel)) {
            source.sendError(Text.literal(GENERIC_NOT_FOUND));
            return Optional.empty();
        }
        return bot;
    }

    public Optional<AIPlayerEntity> resolveAuthorized(ServerPlayerEntity player,
                                                      String botName,
                                                      Operation operation,
                                                      String channel) {
        Optional<AIPlayerEntity> bot = resolveForPlayer(player, botName);
        if (bot.isEmpty()) {
            auditMissing(Actor.player(player.getUuid(), player.hasPermissionLevel(OPERATOR_LEVEL)),
                    player.getGameProfile().getName(), botName, operation, channel);
            return Optional.empty();
        }
        if (!authorize(player, bot.get(), operation, channel)) {
            return Optional.empty();
        }
        return bot;
    }

    public boolean authorize(ServerCommandSource source,
                             AIPlayerEntity bot,
                             Operation operation,
                             String channel) {
        Actor actor = actor(source);
        Decision decision = policy.evaluate(actor, target(bot), operation);
        if (!decision.allowed()) {
            auditDenied(actor, source.getName(), bot, operation, channel, decision);
        }
        return decision.allowed();
    }

    public boolean authorize(ServerPlayerEntity player,
                             AIPlayerEntity bot,
                             Operation operation,
                             String channel) {
        Actor actor = Actor.player(player.getUuid(), player.hasPermissionLevel(OPERATOR_LEVEL));
        Decision decision = policy.evaluate(actor, target(bot), operation);
        if (!decision.allowed()) {
            auditDenied(actor, player.getGameProfile().getName(), bot, operation, channel, decision);
        }
        return decision.allowed();
    }

    public boolean authorizeBot(AIPlayerEntity actorBot,
                                AIPlayerEntity targetBot,
                                Operation operation,
                                String channel) {
        Actor actor = Actor.bot(actorBot.getUuid(), AIPlayerManager.INSTANCE.ownerOf(actorBot).orElse(null));
        Decision decision = policy.evaluate(actor, target(targetBot), operation);
        if (!decision.allowed()) {
            auditDenied(actor, actorBot.getGameProfile().getName(), targetBot, operation, channel, decision);
        }
        return decision.allowed();
    }

    public boolean requireGlobalAdmin(ServerCommandSource source, String channel) {
        Actor actor = actor(source);
        Decision decision = policy.evaluate(actor, GlobalTarget.INSTANCE, Operation.ADMIN);
        if (decision.allowed()) {
            return true;
        }
        BotLog.security("authorization_denied",
                "actor_kind", actor.kind(),
                "actor_uuid", safe(actor.actorUuid()),
                "actor_name", source.getName(),
                "bot_uuid", "-",
                "bot_name", "-",
                "operation", Operation.ADMIN,
                "channel", cleanChannel(channel),
                "reason", decision.reason());
        source.sendError(Text.literal("[AIBot] 该操作需要服务器管理员权限。"));
        return false;
    }

    public boolean canProvisionPersonalBot(ServerCommandSource source, String channel) {
        if (source.getPlayer() != null) {
            return true;
        }
        return requireGlobalAdmin(source, channel);
    }

    public boolean canView(ServerCommandSource source, AIPlayerEntity bot) {
        return policy.evaluate(actor(source), target(bot), Operation.VIEW).allowed();
    }

    private Optional<AIPlayerEntity> resolveForSource(ServerCommandSource source, String botName) {
        ServerPlayerEntity player = source.getPlayer();
        return BotTargetSelector.resolve(player == null ? null : player.getUuid(), botName,
                AIPlayerManager.INSTANCE::botOf, AIPlayerManager.INSTANCE::getByName);
    }

    private Optional<AIPlayerEntity> resolveForPlayer(ServerPlayerEntity player, String botName) {
        return BotTargetSelector.resolve(player.getUuid(), botName,
                AIPlayerManager.INSTANCE::botOf, AIPlayerManager.INSTANCE::getByName);
    }

    private Actor actor(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            return Actor.player(player.getUuid(), source.hasPermissionLevel(OPERATOR_LEVEL));
        }
        return source.hasPermissionLevel(TRUSTED_CONSOLE_LEVEL) ? Actor.console() : Actor.unknown();
    }

    private BotTarget target(AIPlayerEntity bot) {
        return new BotTarget(bot.getUuid(), AIPlayerManager.INSTANCE.ownerOf(bot).orElse(null));
    }

    private static void auditDenied(Actor actor,
                                    String actorName,
                                    AIPlayerEntity bot,
                                    Operation operation,
                                    String channel,
                                    Decision decision) {
        BotLog.security("authorization_denied",
                "actor_kind", actor.kind(),
                "actor_uuid", safe(actor.actorUuid()),
                "actor_name", actorName == null ? "-" : actorName,
                "bot_uuid", bot.getUuid(),
                "bot_name", bot.getGameProfile().getName(),
                "operation", operation,
                "channel", cleanChannel(channel),
                "reason", decision.reason());
    }

    private static void auditMissing(Actor actor,
                                     String actorName,
                                     String requestedName,
                                     Operation operation,
                                     String channel) {
        BotLog.security("authorization_target_unresolved",
                "actor_kind", actor.kind(),
                "actor_uuid", safe(actor.actorUuid()),
                "actor_name", actorName == null ? "-" : actorName,
                "bot_uuid", "-",
                "bot_name", requestedName == null || requestedName.isBlank() ? "<owned>" : requestedName,
                "operation", operation,
                "channel", cleanChannel(channel),
                "reason", "target_not_found");
    }

    private static String safe(UUID value) {
        return value == null ? "-" : value.toString();
    }

    private static String cleanChannel(String channel) {
        return channel == null || channel.isBlank() ? "unknown" : channel;
    }
}
