package io.github.zoyluo.aibot.auth;

import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy.Actor;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy.BotTarget;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy.GlobalTarget;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy.Operation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotAuthorizationPolicyTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BOT = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ACTOR_BOT = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private final BotAuthorizationPolicy policy = new BotAuthorizationPolicy();

    @Test
    void ownerCanPerformEveryBotScopedOperation() {
        for (Operation operation : Operation.values()) {
            assertAllowed(Actor.player(OWNER, false), new BotTarget(BOT, OWNER), operation);
        }
    }

    @Test
    void unrelatedPlayerCannotPerformAnyBotScopedOperation() {
        for (Operation operation : Operation.values()) {
            assertDenied(Actor.player(OTHER, false), new BotTarget(BOT, OWNER), operation);
        }
    }

    @Test
    void operatorCanManageOwnedAndOwnerlessBots() {
        for (Operation operation : Operation.values()) {
            assertAllowed(Actor.player(OTHER, true), new BotTarget(BOT, OWNER), operation);
            assertAllowed(Actor.player(OTHER, true), new BotTarget(BOT, null), operation);
        }
    }

    @Test
    void consoleAndSystemCanPerformEveryOperation() {
        for (Operation operation : Operation.values()) {
            assertAllowed(Actor.console(), new BotTarget(BOT, OWNER), operation);
            assertAllowed(Actor.system(), new BotTarget(BOT, null), operation);
        }
        assertAllowed(Actor.console(), GlobalTarget.INSTANCE, Operation.ADMIN);
        assertAllowed(Actor.system(), GlobalTarget.INSTANCE, Operation.ADMIN);
    }

    @Test
    void ownerlessTargetRejectsNormalPlayer() {
        for (Operation operation : Operation.values()) {
            assertDenied(Actor.player(OWNER, false), new BotTarget(BOT, null), operation);
        }
    }

    @Test
    void sameOwnerBotCanOnlySendCommands() {
        Actor actor = Actor.bot(ACTOR_BOT, OWNER);
        for (Operation operation : Operation.values()) {
            if (operation == Operation.COMMAND) {
                assertAllowed(actor, new BotTarget(BOT, OWNER), operation);
            } else {
                assertDenied(actor, new BotTarget(BOT, OWNER), operation);
            }
        }
    }

    @Test
    void crossOwnerAndOwnerlessBotActorsAreDenied() {
        for (Operation operation : Operation.values()) {
            assertDenied(Actor.bot(ACTOR_BOT, OTHER), new BotTarget(BOT, OWNER), operation);
            assertDenied(Actor.bot(ACTOR_BOT, null), new BotTarget(BOT, OWNER), operation);
        }
    }

    @Test
    void globalTargetRequiresTrustedAdministrator() {
        assertAllowed(Actor.player(OWNER, true), GlobalTarget.INSTANCE, Operation.ADMIN);
        assertAllowed(Actor.console(), GlobalTarget.INSTANCE, Operation.VIEW);
        assertAllowed(Actor.system(), GlobalTarget.INSTANCE, Operation.COMMAND);
        assertDenied(Actor.player(OWNER, false), GlobalTarget.INSTANCE, Operation.VIEW);
        assertDenied(Actor.bot(ACTOR_BOT, OWNER), GlobalTarget.INSTANCE, Operation.COMMAND);
    }

    @Test
    void unknownActorIsAlwaysDenied() {
        for (Operation operation : Operation.values()) {
            assertDenied(Actor.unknown(), new BotTarget(BOT, OWNER), operation);
            assertDenied(Actor.unknown(), GlobalTarget.INSTANCE, operation);
        }
    }

    private void assertAllowed(Actor actor, BotAuthorizationPolicy.Target target, Operation operation) {
        assertTrue(policy.evaluate(actor, target, operation).allowed(), () -> actor + " should allow " + operation + " on " + target);
    }

    private void assertDenied(Actor actor, BotAuthorizationPolicy.Target target, Operation operation) {
        assertFalse(policy.evaluate(actor, target, operation).allowed(), () -> actor + " should deny " + operation + " on " + target);
    }
}
