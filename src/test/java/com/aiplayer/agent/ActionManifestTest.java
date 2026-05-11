package com.aiplayer.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionManifestTest {
    @Test
    void manifestRejectsUnknownAndMissingParameters() {
        ActionManifest manifest = ActionManifest.survivalDefaults();

        assertTrue(manifest.isAllowed("make_item"));
        assertFalse(manifest.isAllowed("teleport"));
        assertTrue(manifest.validate("make_item", Map.of("item", "minecraft:iron_pickaxe", "quantity", 1)).isEmpty());
        assertFalse(manifest.validate("make_item", Map.of("item", "minecraft:iron_pickaxe")).isEmpty());
    }
}
