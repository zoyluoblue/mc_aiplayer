package io.github.zoyluo.aibot.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Pure name-selection seam: blank and explicit names only locate a target; authorization happens afterwards. */
public final class BotTargetSelector {
    private BotTargetSelector() {
    }

    public static <T> Optional<T> resolve(UUID ownerUuid,
                                          String botName,
                                          Function<UUID, Optional<T>> ownedLookup,
                                          Function<String, Optional<T>> namedLookup) {
        Objects.requireNonNull(ownedLookup, "ownedLookup");
        Objects.requireNonNull(namedLookup, "namedLookup");
        if (botName == null || botName.isBlank()) {
            return ownerUuid == null ? Optional.empty() : ownedLookup.apply(ownerUuid);
        }
        return namedLookup.apply(botName);
    }
}
