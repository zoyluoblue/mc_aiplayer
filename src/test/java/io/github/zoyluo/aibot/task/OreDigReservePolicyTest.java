package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.mining.MiningBudget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreDigReservePolicyTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/aibot/task/OreDigTask.java");

    @Test
    void parentMissionAndRareEpochReservesComposeWithoutWeakeningEitherOwner() {
        assertEquals(76, OreDigTask.protectedStoneLikeReserveForPolicy(
                76, false, 0));
        assertEquals(MiningBudget.RARE_BOOTSTRAP_STONE_LIKE,
                OreDigTask.protectedStoneLikeReserveForPolicy(
                        MiningBudget.RARE_BOOTSTRAP_STONE_LIKE, false, 0));
        assertEquals(MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE,
                OreDigTask.protectedStoneLikeReserveForPolicy(
                        MiningBudget.EMERGENCY_STONE_LIKE, true, 0));
        assertEquals(MiningBudget.EMERGENCY_STONE_LIKE,
                OreDigTask.protectedStoneLikeReserveForPolicy(
                        MiningBudget.EMERGENCY_STONE_LIKE, true, 1));
    }

    @Test
    void everyPathOwnerCarriesTheProtectedStoneLikeReserve() throws IOException {
        String source = Files.readString(SOURCE);
        long allPathStarts = Pattern.compile("start(?:Dig)?PathTo\\(")
                .matcher(source)
                .results()
                .count();
        long reservedPathStarts = Pattern.compile(
                        "start(?:Dig)?PathTo\\([^;]*?protectedStoneLikeReserve\\)",
                        Pattern.DOTALL)
                .matcher(source)
                .results()
                .count();

        assertTrue(allPathStarts > 0, "OreDig must retain at least one path owner");
        assertEquals(allPathStarts, reservedPathStarts,
                "every OreDig A* owner must preserve the mission's stone-like reserve");
    }
}
