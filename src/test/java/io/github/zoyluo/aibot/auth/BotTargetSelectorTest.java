package io.github.zoyluo.aibot.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotTargetSelectorTest {
    @Test
    void blankAndExplicitNameSelectTheSameTargetBeforeAuthorization() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String bot = "Bob";
        Map<UUID, String> byOwner = Map.of(owner, bot);
        Map<String, String> byName = Map.of("Bob", bot);

        assertEquals(Optional.of(bot), BotTargetSelector.resolve(owner, "",
                id -> Optional.ofNullable(byOwner.get(id)), name -> Optional.ofNullable(byName.get(name))));
        assertEquals(Optional.of(bot), BotTargetSelector.resolve(owner, "Bob",
                id -> Optional.ofNullable(byOwner.get(id)), name -> Optional.ofNullable(byName.get(name))));
    }

    @Test
    void blankNameWithoutPlayerOwnerDoesNotFallBackToAnArbitraryBot() {
        Optional<String> result = BotTargetSelector.resolve(null, " ", ignored -> Optional.of("Bob"), ignored -> Optional.of("Alice"));
        assertTrue(result.isEmpty());
    }
}
