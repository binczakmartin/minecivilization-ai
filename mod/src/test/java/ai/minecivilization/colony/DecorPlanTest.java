package ai.minecivilization.colony;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a district puts its torches, its fire and its flowers. */
class DecorPlanTest {

    private static final int SURFACE = 64;
    private static final ZoneLayout.Bounds PLOT =
            ZoneLayout.bounds(0, 0, new ZoneLayout.Plot(0, 0));

    private static final List<String> FLOWERS =
            List.of("minecraft:poppy", "minecraft:dandelion");

    // ------------------------------------------------------------------ lighting

    @Test
    void torchesCoverTheDistrictCloselyEnoughToStopSpawns() {
        List<DecorPlan.Spot> lights = DecorPlan.lighting(PLOT, SURFACE);

        assertFalse(lights.isEmpty(), "an unlit district spawns mobs in its own streets");
        for (DecorPlan.Spot spot : lights) {
            assertEquals("minecraft:torch", spot.block());
            assertTrue(PLOT.contains(spot.pos().getX(), spot.pos().getZ()),
                    "torch outside the district it lights: " + spot.pos());
        }
    }

    @Test
    void everyPartOfTheDistrictIsWithinReachOfALight() {
        List<DecorPlan.Spot> lights = DecorPlan.lighting(PLOT, SURFACE);

        for (int x = PLOT.minX(); x <= PLOT.maxX(); x++) {
            for (int z = PLOT.minZ(); z <= PLOT.maxZ(); z++) {
                boolean lit = false;
                for (DecorPlan.Spot spot : lights) {
                    int dx = Math.abs(spot.pos().getX() - x);
                    int dz = Math.abs(spot.pos().getZ() - z);
                    if (Math.max(dx, dz) <= DecorPlan.TORCH_SPACING) {
                        lit = true;
                        break;
                    }
                }
                assertTrue(lit, "dark corner at " + x + "," + z);
            }
        }
    }

    @Test
    void noTwoDecorationsFightOverTheSameBlock() {
        List<DecorPlan.Spot> all = DecorPlan.forDistrict(ZoneType.CIVIC, PLOT, SURFACE, FLOWERS);

        Set<BlockPos> seen = new HashSet<>();
        for (DecorPlan.Spot spot : all) {
            assertTrue(seen.add(spot.pos()),
                    "two things planned for " + spot.pos() + " — one would replace the other");
        }
    }

    // ------------------------------------------------------------------ the rest

    @Test
    void theCivicCentreGetsAHearth() {
        List<DecorPlan.Spot> civic = DecorPlan.forDistrict(ZoneType.CIVIC, PLOT, SURFACE, FLOWERS);

        assertTrue(civic.stream().anyMatch(s -> s.block().equals("minecraft:campfire")),
                "a town square with no fire is a car park");
    }

    @Test
    void workingDistrictsAreLitButNotPrettified() {
        List<DecorPlan.Spot> mine = DecorPlan.forDistrict(ZoneType.MINE, PLOT, SURFACE, FLOWERS);

        assertFalse(mine.isEmpty(), "a quarry still needs light");
        for (DecorPlan.Spot spot : mine) {
            assertEquals("minecraft:torch", spot.block(),
                    "nobody plants flower beds around a quarry");
        }
    }

    @Test
    void lightComesBeforeLooks() {
        List<DecorPlan.Spot> civic = DecorPlan.forDistrict(ZoneType.CIVIC, PLOT, SURFACE, FLOWERS);

        assertEquals("minecraft:torch", civic.get(0).block(),
                "lighting keeps citizens alive; it goes down first");
    }

    @Test
    void flowerBedsUseOnlyWhatIsCarried() {
        List<DecorPlan.Spot> beds = DecorPlan.flowerBeds(PLOT, SURFACE, List.of("minecraft:poppy"));

        assertEquals(DecorPlan.FLOWER_BEDS, beds.size());
        for (DecorPlan.Spot spot : beds) {
            assertEquals("minecraft:poppy", spot.block());
        }
        assertTrue(DecorPlan.flowerBeds(PLOT, SURFACE, List.of()).isEmpty(),
                "no flowers carried, no flower beds");
    }

    @Test
    void flowerBedsAreSpreadOutNotStacked() {
        List<DecorPlan.Spot> beds = DecorPlan.flowerBeds(PLOT, SURFACE, FLOWERS);

        Set<BlockPos> positions = new HashSet<>();
        for (DecorPlan.Spot spot : beds) {
            assertTrue(positions.add(spot.pos()), "two beds planned for " + spot.pos());
            assertTrue(PLOT.contains(spot.pos().getX(), spot.pos().getZ()));
        }
    }

    @Test
    void theSameDistrictAlwaysDecoratesTheSameWay() {
        // A citizen interrupted halfway through must resume, not re-scatter.
        assertEquals(
                DecorPlan.forDistrict(ZoneType.CIVIC, PLOT, SURFACE, FLOWERS).toString(),
                DecorPlan.forDistrict(ZoneType.CIVIC, PLOT, SURFACE, FLOWERS).toString());
    }

    @Test
    void flowersAreToldApartFromEverythingElse() {
        assertTrue(DecorPlan.isFlower("minecraft:poppy"));
        assertTrue(DecorPlan.isFlower("minecraft:red_tulip"));
        assertFalse(DecorPlan.isFlower("minecraft:cobblestone"));
        assertFalse(DecorPlan.isFlower("minecraft:torch"));
        assertFalse(DecorPlan.isFlower(null));

        assertEquals(List.of("minecraft:poppy"),
                DecorPlan.flowersAmong(List.of("minecraft:cobblestone", "minecraft:poppy")));
    }
}
