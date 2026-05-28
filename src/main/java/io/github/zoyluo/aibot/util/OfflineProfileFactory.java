package io.github.zoyluo.aibot.util;

import com.mojang.authlib.GameProfile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class OfflineProfileFactory {
    private OfflineProfileFactory() {
    }

    public static GameProfile create(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new GameProfile(uuid, name);
    }
}
