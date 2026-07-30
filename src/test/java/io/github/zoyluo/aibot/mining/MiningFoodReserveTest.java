package io.github.zoyluo.aibot.mining;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningFoodReserveTest {
    @Test
    void normalizesBerriesAtTwoToOneWithoutNegativeCredit() {
        assertEquals(6, MiningFoodReserve.normalizedUnits(5, 3));
        assertEquals(5, MiningFoodReserve.normalizedUnits(5, 1));
        assertEquals(0, MiningFoodReserve.normalizedUnits(-5, -3));
    }

    @Test
    void convertsUnitDeficitsToPhysicalItemCounts() {
        assertEquals(2, MiningFoodReserve.physicalItemsForUnits(2, false));
        assertEquals(4, MiningFoodReserve.physicalItemsForUnits(2, true));
        assertEquals(0, MiningFoodReserve.physicalItemsForUnits(-2, true));
    }

    @Test
    void safeItemWhitelistIsExplicitAndExcludesRawOrHarmfulFood() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/mining/MiningFoodReserve.java"));
        int start = source.indexOf("private static final List<Item> ONE_UNIT_PRIORITY");
        int end = source.indexOf(");", start);
        String whitelist = source.substring(start, end);

        for (String safe : new String[]{
                "COOKED_BEEF", "COOKED_PORKCHOP", "COOKED_MUTTON", "COOKED_CHICKEN",
                "COOKED_RABBIT", "COOKED_COD", "COOKED_SALMON", "BREAD", "BAKED_POTATO"}) {
            assertTrue(whitelist.contains("Items." + safe), "missing safe reserve item " + safe);
        }
        for (String unsafe : new String[]{
                "Items.BEEF,", "Items.CHICKEN,", "ROTTEN_FLESH", "PUFFERFISH",
                "SPIDER_EYE", "POISONOUS_POTATO"}) {
            assertFalse(whitelist.contains(unsafe), "unsafe reserve item " + unsafe);
        }
        assertTrue(source.contains("Items.SWEET_BERRIES"));
        assertEquals(2, MiningFoodReserve.MIN_DEEP_MINE_UNITS);
    }
}
