package ai.minecivilization.navigation;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terrain planning over hand-drawn worlds — pure geometry, no Minecraft server.
 *
 * <p>Each world is one predicate: "is this cell solid?". Ground is everything at
 * or below y=63, and features are carved or added on top of that.</p>
 */
class TerrainPlannerTest {

    private static final BlockPos STAND = new BlockPos(0, 64, 0);

    /** Flat world: solid up to y=63, air above. */
    private static final Predicate<BlockPos> GROUND = p -> p.getY() <= 63;

    private static World world(Predicate<BlockPos> solid) {
        return new World(solid, p -> false);
    }

    private static TerrainPlanner.Options exact() {
        TerrainPlanner.Options o = new TerrainPlanner.Options();
        o.arrivalRadius = 0;
        return o;
    }

    // ------------------------------------------------------------------ walking

    @Test
    void flatGroundIsPlainWalkingWithNoBlockOperations() {
        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(5, 64, 0),
                world(GROUND), exact());

        assertNotNull(plan, "a flat world is always crossable");
        assertTrue(plan.needsNoModification(), "walking must never cost blocks");
        assertEquals(5, plan.steps().size());
        assertEquals(new BlockPos(5, 64, 0), plan.destination());
        for (TerrainPlan.Step step : plan.steps()) {
            assertEquals(TerrainPlan.MoveKind.WALK, step.move);
        }
    }

    @Test
    void standingOnTheGoalPlansNothing() {
        TerrainPlan plan = TerrainPlanner.plan(STAND, STAND, world(GROUND), exact());

        assertNotNull(plan);
        assertTrue(plan.isEmpty(), "already there: an empty plan, not a failure");
    }

    // ------------------------------------------------------------------ bridging

    @Test
    void aSupportedRavineIsBridgedOneDeckBlockPerGap() {
        // A three-block gap with a solid supporting shelf one block below the
        // deck.  The deck is therefore a real block, not a floating promise.
        World w = world(p -> p.getY() <= 63
                && !(p.getX() >= 2 && p.getX() <= 4 && p.getY() == 63));

        TerrainPlanner.Options bridgeOnly = exact();
        bridgeOnly.maxDrop = 0;
        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(6, 64, 0), w, bridgeOnly);

        assertNotNull(plan, "a citizen carrying blocks can always bridge");
        assertEquals(3, plan.countOps(TerrainPlan.OpKind.PLACE), "one deck block per gap column");
        assertEquals(0, plan.countOps(TerrainPlan.OpKind.DIG), "nothing to dig over a chasm");
        assertEquals(new BlockPos(6, 64, 0), plan.destination());

        // the deck is laid at foot level, directly under the walked cells
        assertEquals(java.util.List.of(
                new BlockPos(2, 63, 0), new BlockPos(3, 63, 0), new BlockPos(4, 63, 0)),
                plan.placements());
    }

    @Test
    void aBottomlessGapIsNotPromisedAsAFloatingBridge() {
        World w = world(p -> p.getY() <= 63 && !(p.getX() >= 2 && p.getX() <= 4));

        assertNull(TerrainPlanner.plan(STAND, new BlockPos(6, 64, 0), w, exact()),
                "a deck with air below it is not a traversable bridge");
    }

    @Test
    void thePlacementBudgetIsIndependentFromDigBudget() {
        World w = world(GROUND);

        assertNull(TerrainPlanner.plan(STAND, new BlockPos(0, 68, 0), w,
                exact().maxPlacements(2)));
        assertNotNull(TerrainPlanner.plan(STAND, new BlockPos(0, 68, 0), w,
                exact().maxPlacements(4)));
    }

    @Test
    void aPillarNeverPlansIntoAnOccupiedBodyCell() {
        // The only open shaft is blocked one level above the citizen and the
        // block is unbreakable, so the planner must not promise a pillar into it.
        // Keep the only vertical shaft open; every neighbouring cell is solid.
        // The unbreakable block in that shaft cannot be entered or removed.
        World shaft = new World(
                p -> ((p.getY() <= 63 || p.getY() >= 64)
                        && !(p.getX() == 0 && p.getZ() == 0))
                        || (p.getX() == 0 && p.getZ() == 0
                                && (p.getY() == 63 || p.getY() == 65)),
                p -> p.getX() == 0 && p.getZ() == 0
                        && (p.getY() == 63 || p.getY() == 65));

        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(0, 68, 0), shaft, exact());
        assertNotNull(plan);
        for (TerrainPlan.Step step : plan.steps()) {
            if (step.move != TerrainPlan.MoveKind.PILLAR_UP) continue;
            assertTrue(shaft.passable(step.feet) && shaft.passable(step.feet.above()),
                    "pillar enters an occupied body cell: " + step);
        }
    }

    @Test
    void arrivalRuleIsChebyshevAtEveryAxis() {
        assertTrue(TerrainPlanner.arrivedAt(new BlockPos(1, 1, 1),
                new BlockPos(0, 0, 0), 1));
        assertFalse(TerrainPlanner.arrivedAt(new BlockPos(2, 1, 1),
                new BlockPos(0, 0, 0), 1));
    }

    @Test
    void withoutMaterialToPlaceAChasmStaysImpassable() {
        World w = world(p -> p.getY() <= 63 && !(p.getX() >= 2 && p.getX() <= 4 && p.getY() == 63));
        TerrainPlanner.Options noPlace = exact().allowPlace(false);
        noPlace.maxDrop = 0;

        assertNull(TerrainPlanner.plan(STAND, new BlockPos(6, 64, 0), w, noPlace),
                "empty-handed, the citizen must report unreachable rather than invent blocks");
    }

    @Test
    void theOperationBudgetIsEnforced() {
        // Ten-wide chasm needs ten deck blocks; the budget allows three.
        World w = world(p -> p.getY() <= 63
                && !(p.getX() >= 2 && p.getX() <= 11 && p.getY() == 63));
        TerrainPlanner.Options bridgeOnly = exact();
        bridgeOnly.maxDrop = 0;

        bridgeOnly.maxOps = 3;
        assertNull(TerrainPlanner.plan(STAND, new BlockPos(13, 64, 0), w, bridgeOnly),
                "a route over budget is no route at all");
        bridgeOnly.maxOps = 16;
        assertNotNull(TerrainPlanner.plan(STAND, new BlockPos(13, 64, 0), w, bridgeOnly),
                "the same crossing succeeds once the budget covers it");
    }

    // ------------------------------------------------------------------ digging

    @Test
    void anEndlessWallIsTunnelledAtBodyHeight() {
        // Wall at x=3, seven blocks tall, running the whole z axis.
        World w = world(p -> GROUND.test(p) || (p.getX() == 3 && p.getY() >= 64 && p.getY() <= 70));

        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(6, 64, 0), w, exact());

        assertNotNull(plan);
        assertEquals(2, plan.countOps(TerrainPlan.OpKind.DIG), "feet and head, nothing more");
        assertEquals(0, plan.countOps(TerrainPlan.OpKind.PLACE));
        assertEquals(new BlockPos(6, 64, 0), plan.destination());
    }

    @Test
    void goingAroundBeatsDiggingWhenItIsShorter() {
        // A single 1x1 pillar of wall — cheap to walk around, expensive to breach.
        World w = world(p -> GROUND.test(p)
                || (p.getX() == 3 && p.getZ() == 0 && p.getY() >= 64 && p.getY() <= 70));

        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(6, 64, 0), w, exact());

        assertNotNull(plan);
        assertTrue(plan.needsNoModification(),
                "the cost model must prefer four extra steps over two dug blocks");
    }

    @Test
    void unbreakableBlocksAreNeverScheduledForDigging() {
        // Wall of bedrock: solid, and explicitly not diggable. The soft ground
        // underneath it is fair game, so the expected answer is a tunnel under
        // the wall — what matters is that no DIG ever targets the wall itself.
        Predicate<BlockPos> wall = p -> p.getX() == 3 && p.getY() >= 64 && p.getY() <= 70;
        World w = new World(p -> GROUND.test(p) || wall.test(p), wall);

        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(6, 64, 0), w, exact());

        assertNotNull(plan, "going under the wall is a legitimate route");
        for (TerrainPlan.Op op : allOps(plan)) {
            if (op.kind == TerrainPlan.OpKind.DIG) {
                assertTrue(w.diggable(op.pos),
                        "planned a dig on an unbreakable block at " + op.pos);
            }
        }
        assertEquals(new BlockPos(6, 64, 0), plan.destination());
    }

    // ------------------------------------------------------------------ vertical

    @Test
    void gainingHeightCostsExactlyOneBlockPerLevel() {
        // In the open, a staircase and a straight pillar cost the same, so the
        // planner may pick either. What must hold is the price: one placed
        // block per level gained, and one level per step.
        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(0, 68, 0),
                world(GROUND), exact());

        assertNotNull(plan);
        assertEquals(4, plan.steps().size(), "four levels, four steps");
        assertEquals(4, plan.countOps(TerrainPlan.OpKind.PLACE));
        assertEquals(0, plan.countOps(TerrainPlan.OpKind.DIG), "open sky needs no digging");
        assertEquals(new BlockPos(0, 68, 0), plan.destination());
    }

    @Test
    void aMissingClimbStepIsBuiltAsAStairWhenTheShelfIsSupported() {
        World w = world(p -> p.getY() <= 63
                || (p.getX() == 0 && p.getY() <= 64));

        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(1, 65, 0), w, exact());

        assertNotNull(plan);
        assertEquals(1, plan.countOps(TerrainPlan.OpKind.PLACE));
        assertEquals(TerrainPlan.MoveKind.STAIR_UP, plan.steps().get(0).move);
        TerrainPlan.Op placement = allOps(plan).stream()
                .filter(op -> op.kind == TerrainPlan.OpKind.PLACE)
                .findFirst().orElseThrow();
        assertEquals(new BlockPos(1, 64, 0), placement.pos);
        assertEquals("stair:east", placement.variant,
                "a one-block climb should become a usable stair, not a floating pillar");
    }

    @Test
    void aOneWideShaftIsClimbedByPillaringUnderneathOneself() {
        // Only the x=0,z=0 column is open above ground: no room to zigzag, so
        // the citizen must place a block into the very cell it stands in.
        World w = world(p -> p.getY() <= 63
                || (!(p.getX() == 0 && p.getZ() == 0) && p.getY() <= 68));

        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(0, 68, 0), w, exact());

        assertNotNull(plan);
        assertEquals(4, plan.steps().size());
        for (TerrainPlan.Step step : plan.steps()) {
            assertEquals(TerrainPlan.MoveKind.PILLAR_UP, step.move);
        }
        // each support goes into the cell the citizen occupied at that moment
        assertEquals(java.util.List.of(
                new BlockPos(0, 64, 0), new BlockPos(0, 65, 0),
                new BlockPos(0, 66, 0), new BlockPos(0, 67, 0)),
                plan.placements());
    }

    @Test
    void descendingIntoTheGroundDigsStraightDown() {
        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(0, 60, 0),
                world(GROUND), exact());

        assertNotNull(plan);
        assertEquals(4, plan.countOps(TerrainPlan.OpKind.DIG));
        assertEquals(0, plan.countOps(TerrainPlan.OpKind.PLACE), "no need to bridge a shaft");
        for (TerrainPlan.Step step : plan.steps()) {
            assertEquals(TerrainPlan.MoveKind.DIG_DOWN, step.move);
        }
    }

    @Test
    void aShortDropIsTakenRatherThanBuilt() {
        // Ledge: everything at x>=3 is three blocks lower.
        World w = world(p -> p.getX() >= 3 ? p.getY() <= 60 : p.getY() <= 63);

        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(5, 61, 0), w, exact());

        assertNotNull(plan);
        assertTrue(plan.needsNoModification(), "falling is free; stairs are not");
        assertTrue(plan.steps().stream().anyMatch(s -> s.move == TerrainPlan.MoveKind.FALL),
                "a 3-block ledge is a controlled drop");
    }

    @Test
    void searchBoxIsMeasuredFromTheJourneyEndpoints() {
        TerrainPlanner.Options narrow = exact();
        narrow.searchBox = 3;
        assertNull(TerrainPlanner.plan(STAND, new BlockPos(10, 64, 0), world(GROUND), narrow),
                "the planner must not leave both endpoint boxes on a long detour");

        TerrainPlanner.Options overlapping = exact();
        overlapping.searchBox = 5;
        assertNotNull(TerrainPlanner.plan(STAND, new BlockPos(10, 64, 0),
                world(GROUND), overlapping));
    }

    // ------------------------------------------------------------------ guarantees

    @Test
    void planningIsDeterministic() {
        World w = world(p -> p.getY() <= 63 && !(p.getX() >= 2 && p.getX() <= 4 && p.getY() == 63));

        TerrainPlan a = TerrainPlanner.plan(STAND, new BlockPos(8, 64, 0), w, exact());
        TerrainPlan b = TerrainPlanner.plan(STAND, new BlockPos(8, 64, 0), w, exact());

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.steps().toString(), b.steps().toString(),
                "identical worlds must yield identical plans — retries have to be stable");
    }

    @Test
    void everyStepIsExactlyOneCellAwayFromThePrevious() {
        World w = world(p -> p.getY() <= 63 && !(p.getX() >= 2 && p.getX() <= 4 && p.getY() == 63));
        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(9, 66, 0), w, exact());

        assertNotNull(plan);
        BlockPos previous = STAND;
        for (TerrainPlan.Step step : plan.steps()) {
            int dx = Math.abs(step.feet.getX() - previous.getX());
            int dz = Math.abs(step.feet.getZ() - previous.getZ());
            int dy = Math.abs(step.feet.getY() - previous.getY());
            assertTrue(dx <= 1 && dz <= 1, "no teleporting sideways: " + previous + " -> " + step.feet);
            int allowedDrop = step.move == TerrainPlan.MoveKind.FALL ? 3 : 1;
            assertTrue(dy <= allowedDrop, "vertical jump too large: " + previous + " -> " + step.feet);
            previous = step.feet;
        }
    }

    @Test
    void anImpossibleGoalReturnsNoPlanInsteadOfLooping() {
        // Goal sealed inside unbreakable stone.
        World w = new World(p -> p.getY() <= 63 || p.getY() >= 70, p -> p.getY() >= 70);

        assertNull(TerrainPlanner.plan(STAND, new BlockPos(0, 80, 0), w, exact()));
    }

    private static java.util.List<TerrainPlan.Op> allOps(TerrainPlan plan) {
        java.util.List<TerrainPlan.Op> out = new java.util.ArrayList<>();
        for (TerrainPlan.Step step : plan.steps()) out.addAll(step.ops);
        return out;
    }

    // ------------------------------------------------------------------ fixture

    /** A world described by two predicates: what is solid, and what cannot be broken. */
    private static final class World implements BlockView {
        private final Predicate<BlockPos> solid;
        private final Predicate<BlockPos> unbreakable;
        private Predicate<BlockPos> water = p -> false;

        World(Predicate<BlockPos> solid, Predicate<BlockPos> unbreakable) {
            this.solid = solid;
            this.unbreakable = unbreakable;
        }

        World flooded(Predicate<BlockPos> where) {
            this.water = where;
            return this;
        }

        @Override
        public boolean swimmable(BlockPos pos) {
            return water.test(pos) && !solid.test(pos);
        }

        @Override
        public boolean passable(BlockPos pos) {
            return !solid.test(pos);
        }

        @Override
        public boolean sturdy(BlockPos pos) {
            return solid.test(pos);
        }

        @Override
        public boolean diggable(BlockPos pos) {
            return solid.test(pos) && !unbreakable.test(pos);
        }

        @Override
        public boolean inBounds(BlockPos pos) {
            return pos.getY() >= -64 && pos.getY() < 320;
        }
    }

    // ------------------------------------------------------------------ any target

    /**
     * Reaching a target however high or deep it is.
     *
     * <p>Written after high branches and buried ore both came back
     * "no route even after digging and bridging" over open, featureless
     * ground. The cause was the estimate pricing a block of climb like a block
     * of walking, so the search fanned out sideways until it ran out of nodes.
     * These pin the behaviour that matters: a route exists, and it is one a
     * citizen survives.</p>
     */
    @Test
    void anyHeightIsReachableOverOpenGround() {
        World open = world(GROUND);
        for (int height = 66; height <= 110; height += 4) {
            TerrainPlanner.Options o = new TerrainPlanner.Options();
            o.arrivalRadius = 2;
            // Enough carried material to climb: the planner's budget, not its
            // geometry, is what should ever stop a climb.
            o.maxOps = 256;
            TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(2, height, 1), open, o);
            assertNotNull(plan, "no route to a target " + (height - 64) + " blocks up");
        }
    }

    @Test
    void anyDepthIsReachableThroughSolidRock() {
        World open = world(GROUND);
        for (int depth = 62; depth >= 10; depth -= 6) {
            TerrainPlanner.Options o = new TerrainPlanner.Options();
            o.arrivalRadius = 2;
            o.maxOps = 256;
            o.searchHeight = 80;
            TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(-3, depth, 1), open, o);
            assertNotNull(plan, "no route to a target at y=" + depth);
        }
    }

    @Test
    void climbingNeverStepsMoreThanOneBlockAtATime() {
        // The whole safety argument for a tall climb: every tread is one block,
        // so there is never a jump a citizen cannot make or a fall it takes
        // damage from.
        World open = world(GROUND);
        TerrainPlanner.Options o = new TerrainPlanner.Options();
        o.arrivalRadius = 1;
        o.maxOps = 256;
        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(0, 90, 0), open, o);
        assertNotNull(plan);

        BlockPos previous = STAND;
        for (TerrainPlan.Step step : plan.steps()) {
            int rise = step.feet.getY() - previous.getY();
            assertTrue(rise <= 1, "climbed " + rise + " blocks in one step");
            previous = step.feet;
        }
    }

    @Test
    void aWalkableHillIsPreferredToBuildingATower() {
        // The climb estimate deliberately overshoots on open ground. It must
        // not make a citizen ignore a perfectly good slope beside it.
        Predicate<BlockPos> hill = p -> {
            if (p.getY() <= 63) return true;
            // A staircase rising one block per step along +x, up to y=75.
            int step = p.getX();
            return step >= 1 && step <= 12 && p.getY() <= 63 + step && Math.abs(p.getZ()) <= 2;
        };
        World slope = world(hill);
        TerrainPlanner.Options o = new TerrainPlanner.Options();
        o.arrivalRadius = 1;
        o.maxOps = 256;
        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(12, 76, 0), slope, o);
        assertNotNull(plan);

        long placements = plan.steps().stream()
                .flatMap(step -> step.ops.stream())
                .filter(op -> op.kind == TerrainPlan.OpKind.PLACE)
                .count();
        assertTrue(placements <= 2,
                "walked up a hill but still placed " + placements + " block(s)");
    }

    // ------------------------------------------------------------------ water

    /**
     * Crossing and leaving water.
     *
     * <p>Ground navigation treats water as having no floor, so a stream read
     * as a wall, a lake as the edge of the world, and a citizen that fell into
     * either could not path out of the cell it was floating in. Swimming is
     * the one medium where every direction is open, including straight up.</p>
     */
    @Test
    void aLakeIsSwumAcrossRatherThanBridged() {
        // A channel carved out of the ground and filled with water.
        Predicate<BlockPos> carved = p -> p.getY() <= 63
                && !(p.getX() >= 3 && p.getX() <= 12 && p.getY() >= 61);
        World lake = world(carved).flooded(p -> p.getX() >= 3 && p.getX() <= 12
                && p.getY() >= 61 && p.getY() <= 63);

        TerrainPlanner.Options o = exact();
        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(16, 64, 0), lake, o);
        assertNotNull(plan, "a citizen must be able to cross water");

        long swum = plan.steps().stream()
                .filter(step -> step.move == TerrainPlan.MoveKind.SWIM).count();
        assertTrue(swum > 0, "crossed the lake without ever swimming");

        long placed = plan.steps().stream()
                .flatMap(step -> step.ops.stream())
                .filter(op -> op.kind == TerrainPlan.OpKind.PLACE)
                .count();
        assertTrue(placed <= 2, "paved the lake instead of swimming it: " + placed + " blocks");
    }

    @Test
    void aCitizenInWaterCanGetOut() {
        // The case that stranded people: standing in a flooded pit with the
        // bank one block up. Nothing to stand on, so every ground rule said no.
        Predicate<BlockPos> pit = p -> p.getY() <= 63 && !(p.getX() == 0 && p.getZ() == 0
                && p.getY() >= 61);
        World flooded = world(pit).flooded(p -> p.getX() == 0 && p.getZ() == 0
                && p.getY() >= 61 && p.getY() <= 63);

        TerrainPlanner.Options o = exact();
        TerrainPlan plan = TerrainPlanner.plan(new BlockPos(0, 61, 0),
                new BlockPos(4, 64, 0), flooded, o);
        assertNotNull(plan, "a citizen in water must be able to climb out");
    }

    @Test
    void swimmingNeedsNoFloorAndBuildsNone() {
        // Open water with no bottom in reach: the route exists anyway.
        World ocean = world(p -> p.getY() <= 30).flooded(p -> p.getY() > 30 && p.getY() <= 63);

        TerrainPlanner.Options o = exact();
        TerrainPlan plan = TerrainPlanner.plan(new BlockPos(0, 62, 0),
                new BlockPos(10, 62, 0), ocean, o);
        assertNotNull(plan, "open water must be crossable");
        for (TerrainPlan.Step step : plan.steps()) {
            assertTrue(step.ops.isEmpty(), "swimming should place and dig nothing");
        }
    }

    @Test
    void lavaIsNeverTreatedAsSwimmable() {
        // swimmable() is water only; a view that reports no water leaves the
        // planner with the ordinary rules, which refuse lava outright.
        World molten = world(GROUND);
        assertFalse(molten.swimmable(new BlockPos(0, 64, 0)));
    }

    @Test
    void dryLandStillWalksRatherThanSwims() {
        World dry = world(GROUND);
        TerrainPlan plan = TerrainPlanner.plan(STAND, new BlockPos(8, 64, 0), dry, exact());
        assertNotNull(plan);
        for (TerrainPlan.Step step : plan.steps()) {
            assertTrue(step.move != TerrainPlan.MoveKind.SWIM,
                    "swam across dry ground");
        }
    }
}
