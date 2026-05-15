package com.aiplayer.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmeltingFuelPolicyTest {
    @Test
    void acceptsCoalCharcoalLogsAndPlanksAsFurnaceFuel() {
        Map<String, Integer> inventory = Map.of(
            "minecraft:charcoal", 1,
            "minecraft:oak_log", 2,
            "minecraft:birch_planks", 3,
            "minecraft:wooden_pickaxe", 1
        );

        assertEquals(7, SmeltingFuelPolicy.countFuel(inventory));
        assertTrue(SmeltingFuelPolicy.fuelItems().contains("minecraft:coal"));
        assertTrue(SmeltingFuelPolicy.fuelItems().contains("minecraft:charcoal"));
        assertTrue(SmeltingFuelPolicy.fuelItems().contains("minecraft:oak_log"));
        assertTrue(SmeltingFuelPolicy.fuelItems().contains("minecraft:oak_planks"));
        assertTrue(SmeltingFuelPolicy.fuelItems().contains("minecraft:wooden_pickaxe"));
        assertFalse(SmeltingFuelPolicy.fuelItems().contains("minecraft:stick"));
    }

    @Test
    void coalRequirementIsTreatedAsFuelSlotForSmelting() {
        assertTrue(SmeltingFuelPolicy.isFuelRequirement("minecraft:coal"));
        assertTrue(SmeltingFuelPolicy.fuelSummary(Map.of("minecraft:oak_planks", 4)).contains("minecraft:oak_planks=4"));
    }

    @Test
    void inputCannotAlsoBeCountedAsFuel() {
        assertFalse(SmeltingFuelPolicy.canAllocateFuelAfterInputs(
            Map.of("minecraft:oak_log", 1),
            Map.of("minecraft:oak_log", 1),
            1
        ));
        assertTrue(SmeltingFuelPolicy.canAllocateFuelAfterInputs(
            Map.of("minecraft:oak_log", 2),
            Map.of("minecraft:oak_log", 1),
            1
        ));
    }
}
