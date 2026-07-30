package io.github.zoyluo.aibot.pathfinding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSupportMaterialSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");

    @Test
    void pathPillarsUseTheirOwnOrderedStableSupportPalette() throws IOException {
        String palette = Files.readString(MAIN.resolve("action/MaterialPalette.java"));
        String supports = listBody(palette, "PATH_SUPPORT_BLOCKS");

        assertEquals(List.of(
                        "DIRT",
                        "COARSE_DIRT",
                        "ROOTED_DIRT",
                        "GRASS_BLOCK",
                        "PODZOL",
                        "MYCELIUM",
                        "NETHERRACK",
                        "TUFF",
                        "ANDESITE",
                        "DIORITE",
                        "GRANITE",
                        "CALCITE",
                        "BLACKSTONE",
                        "DEEPSLATE",
                        "STONE",
                        "COBBLESTONE",
                        "COBBLED_DEEPSLATE"),
                Pattern.compile("Items\\.([A-Z_]+)")
                        .matcher(supports)
                        .results()
                        .map(result -> result.group(1))
                        .toList(),
                "the path palette is a closed whitelist of stable full-height non-falling blocks");
        assertTrue(supports.indexOf("Items.DIRT,") < supports.indexOf("Items.NETHERRACK,"),
                "dirt-like filler must be spent before other stable blocks");
        assertTrue(supports.indexOf("Items.NETHERRACK,") < supports.indexOf("Items.STONE,"),
                "stone must remain a last-resort path material");
        assertTrue(supports.indexOf("Items.STONE,") < supports.indexOf("Items.COBBLESTONE,"));
        assertTrue(supports.indexOf("Items.COBBLESTONE,")
                        < supports.indexOf("Items.COBBLED_DEEPSLATE"),
                "bootstrap cobble outputs must be the final path supports");

        assertFalse(supports.contains("Items.MUD"),
                "mud seals fluid but does not expose a full-height standing surface");
        assertFalse(supports.contains("Items.SAND"), "falling blocks are not stable pillars");
        assertFalse(supports.contains("Items.GRAVEL"), "falling blocks are not stable pillars");
    }

    @Test
    void pathExecutorDoesNotReuseTheFluidSealSelector() throws IOException {
        String executor = Files.readString(MAIN.resolve("pathfinding/PathExecutor.java"));
        int method = executor.indexOf("private static int findPlaceableBlock");
        int end = executor.indexOf("\n    }", method);
        String selector = executor.substring(method, end);

        assertTrue(selector.contains("MaterialPalette.pickPathSupportBlockSlot("));
        assertTrue(selector.contains("player, protectedStoneLikeReserve"),
                "physical PILLAR_UP execution must use the executor-scoped reserve");
        assertFalse(selector.contains("pickSacrificialBlockSlot"),
                "fluid sealing and persistent path infrastructure require separate palettes");
    }

    private static String listBody(String source, String name) {
        int declaration = source.indexOf("private static final List<Item> " + name + " = List.of(");
        int end = source.indexOf(");", declaration);
        assertTrue(declaration >= 0 && end > declaration, "missing ordered palette " + name);
        return source.substring(declaration, end);
    }
}
