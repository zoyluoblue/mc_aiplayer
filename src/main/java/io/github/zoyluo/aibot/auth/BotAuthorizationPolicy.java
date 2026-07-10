package io.github.zoyluo.aibot.auth;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure authorization policy for every externally reachable bot operation.
 * Minecraft objects, name lookup, messaging, and audit logging deliberately live in the gate adapter.
 */
public final class BotAuthorizationPolicy {
    public enum Operation {
        VIEW,
        COMMAND,
        INVENTORY,
        TELEPORT,
        ADMIN
    }

    public enum ActorKind {
        PLAYER,
        BOT,
        CONSOLE,
        SYSTEM,
        UNKNOWN
    }

    public enum Reason {
        SYSTEM,
        CONSOLE,
        OPERATOR,
        OWNER,
        SAME_OWNER_BOT,
        GLOBAL_ADMIN_REQUIRED,
        TARGET_HAS_NO_OWNER,
        NOT_OWNER,
        BOT_OPERATION_FORBIDDEN,
        CROSS_OWNER_BOT,
        BOT_HAS_NO_OWNER,
        UNKNOWN_ACTOR
    }

    public record Actor(ActorKind kind, UUID actorUuid, UUID ownerUuid, boolean operator) {
        public Actor {
            Objects.requireNonNull(kind, "kind");
        }

        public static Actor player(UUID playerUuid, boolean operator) {
            return new Actor(ActorKind.PLAYER, Objects.requireNonNull(playerUuid, "playerUuid"), playerUuid, operator);
        }

        public static Actor bot(UUID botUuid, UUID ownerUuid) {
            return new Actor(ActorKind.BOT, Objects.requireNonNull(botUuid, "botUuid"), ownerUuid, false);
        }

        public static Actor console() {
            return new Actor(ActorKind.CONSOLE, null, null, true);
        }

        public static Actor system() {
            return new Actor(ActorKind.SYSTEM, null, null, true);
        }

        public static Actor unknown() {
            return new Actor(ActorKind.UNKNOWN, null, null, false);
        }
    }

    public sealed interface Target permits BotTarget, GlobalTarget {
    }

    public record BotTarget(UUID botUuid, UUID ownerUuid) implements Target {
        public BotTarget {
            Objects.requireNonNull(botUuid, "botUuid");
        }
    }

    public enum GlobalTarget implements Target {
        INSTANCE
    }

    public record Decision(boolean allowed, Reason reason) {
        public Decision {
            Objects.requireNonNull(reason, "reason");
        }

        private static Decision allow(Reason reason) {
            return new Decision(true, reason);
        }

        private static Decision deny(Reason reason) {
            return new Decision(false, reason);
        }
    }

    public Decision evaluate(Actor actor, Target target, Operation operation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");

        if (actor.kind() == ActorKind.SYSTEM) {
            return Decision.allow(Reason.SYSTEM);
        }
        if (actor.kind() == ActorKind.CONSOLE) {
            return Decision.allow(Reason.CONSOLE);
        }
        if (actor.kind() == ActorKind.UNKNOWN) {
            return Decision.deny(Reason.UNKNOWN_ACTOR);
        }
        if (target instanceof GlobalTarget) {
            return actor.kind() == ActorKind.PLAYER && actor.operator()
                    ? Decision.allow(Reason.OPERATOR)
                    : Decision.deny(Reason.GLOBAL_ADMIN_REQUIRED);
        }

        BotTarget bot = (BotTarget) target;
        if (actor.kind() == ActorKind.PLAYER) {
            if (actor.operator()) {
                return Decision.allow(Reason.OPERATOR);
            }
            if (bot.ownerUuid() == null) {
                return Decision.deny(Reason.TARGET_HAS_NO_OWNER);
            }
            return bot.ownerUuid().equals(actor.actorUuid())
                    ? Decision.allow(Reason.OWNER)
                    : Decision.deny(Reason.NOT_OWNER);
        }

        if (actor.kind() == ActorKind.BOT) {
            if (actor.ownerUuid() == null) {
                return Decision.deny(Reason.BOT_HAS_NO_OWNER);
            }
            if (bot.ownerUuid() == null || !actor.ownerUuid().equals(bot.ownerUuid())) {
                return Decision.deny(Reason.CROSS_OWNER_BOT);
            }
            return operation == Operation.COMMAND
                    ? Decision.allow(Reason.SAME_OWNER_BOT)
                    : Decision.deny(Reason.BOT_OPERATION_FORBIDDEN);
        }

        return Decision.deny(Reason.UNKNOWN_ACTOR);
    }
}
