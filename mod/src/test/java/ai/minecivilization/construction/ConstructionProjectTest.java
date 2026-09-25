package ai.minecivilization.construction;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project lifecycle bookkeeping: progress is measured by physically placed
 * blocks, never by the LLM claiming it finished something.
 */
class ConstructionProjectTest {

    private static Blueprint threeBlocks() {
        return new Blueprint("p", "P", 1, 1, 1, List.of(
                new Blueprint.BlockEntry(0, 0, 0, "minecraft:stone"),
                new Blueprint.BlockEntry(1, 0, 0, "minecraft:stone"),
                new Blueprint.BlockEntry(2, 0, 0, "minecraft:stone")));
    }

    @Test
    void startsProposedWithNoProgress() {
        ConstructionProject p = new ConstructionProject("p1", "House", "starter_warehouse",
                10, 64, -5);
        assertEquals(ConstructionProject.Status.PROPOSED, p.status);
        assertEquals(0.0, p.progress(threeBlocks()));
        assertFalse(p.isFinished(threeBlocks()));
        assertEquals("10,64,-5", p.key(10, 64, -5));
    }

    @Test
    void progressTracksPlacedBlocks() {
        ConstructionProject p = new ConstructionProject("p1", "House", "blueprint", 0, 0, 0);
        Blueprint bp = threeBlocks();

        p.status = ConstructionProject.Status.BUILDING;
        p.placed.add(p.key(0, 0, 0));
        assertEquals(1.0 / 3.0, p.progress(bp), 1e-9);
        assertFalse(p.isFinished(bp));

        p.placed.add(p.key(1, 0, 0));
        p.placed.add(p.key(2, 0, 0));
        assertEquals(1.0, p.progress(bp));
        assertTrue(p.isFinished(bp));
    }

    @Test
    void placingTheSameBlockTwiceDoesNotInflateProgress() {
        ConstructionProject p = new ConstructionProject("p1", "House", "blueprint", 0, 0, 0);
        Blueprint bp = threeBlocks();
        p.placed.add(p.key(0, 0, 0));
        p.placed.add(p.key(0, 0, 0));
        assertEquals(1.0 / 3.0, p.progress(bp), 1e-9);
    }

    @Test
    void ownershipCanOutliveProgressAfterAPlayerEditsACell() {
        ConstructionProject p = new ConstructionProject("p1", "House", "blueprint", 0, 0, 0);
        p.placed.add("0,0,0");
        p.ownedCells.add("0,0,0");
        p.placed.clear();
        assertTrue(p.ownedCells.contains("0,0,0"));
        assertEquals(0.0, p.progress(threeBlocks()));
    }

    @Test
    void emptyBlueprintIsAlwaysComplete() {
        ConstructionProject p = new ConstructionProject("p1", "Nothing", "empty", 0, 0, 0);
        Blueprint empty = new Blueprint("empty", "Empty", 1, 1, 1, List.of());
        assertEquals(1.0, p.progress(empty));
        assertTrue(p.isFinished(empty));
    }

    @Test
    void fullLifecycleEnumIsStable() {
        // The service mirrors these names; renaming one silently breaks the API.
        assertEquals(List.of("PROPOSED", "PLANNED", "WAITING_FOR_RESOURCES", "BUILDING",
                        "COMPLETED", "FAILED", "PAUSED"),
                List.of(ConstructionProject.Status.values()).stream()
                        .map(Enum::name).toList());
    }
}
