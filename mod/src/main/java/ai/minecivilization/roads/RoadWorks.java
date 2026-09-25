package ai.minecivilization.roads;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.citizen.CitizenPlan;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.navigation.PlacementSafety;
import ai.minecivilization.storage.StorageDiscovery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Turning a well-walked route into an actual road.
 *
 * <p>Each grade of {@link RoadGrade} is a different job, and this produces the
 * next few blocks of it for whichever citizen is free. Paving one route is
 * therefore the work of many citizens over many trips, which is both how a
 * colony really builds and how the cost stays bounded: nobody ever takes on
 * "build the road to the mine" as a single task.</p>
 *
 * <p>Only works on ground the citizen can actually see — a road is built by
 * walking it, not by remote control — so a job is always somewhere the worker
 * can reach.</p>
 */
public final class RoadWorks {

    /** Blocks of road laid in one job. Short, so roads interleave with everything else. */
    private static final int JOB_SIZE = 10;
    /** How far from the worker a road cell may be and still be worth going to. */
    private static final int WORK_RADIUS = 48;
    /** Torch spacing on a lit road — bright enough that nothing spawns on it. */
    public static final int TORCH_SPACING = 9;
    /** Half-width of a road surface, in blocks either side of the centre line. */
    public static final int ROAD_HALF_WIDTH = 1;
    /**
     * Ticks between surveys of the road network.
     *
     * <p>Planning a stretch of road walks every waypoint of a route and probes
     * the ground height along it, which is far too much to do once per idle
     * citizen per tick. Roads change slowly; a survey every few seconds is
     * indistinguishable from a continuous one.</p>
     */
    private static final int SURVEY_INTERVAL = 60;

    private static long nextSurveyAt;

    private RoadWorks() {
    }

    /** What the citizen would be doing, for logs and signs. */
    public record Job(Route route, RoadGrade grade, List<CitizenPlan.Task> tasks) {
    }

    /**
     * The next stretch of road work near this citizen, or null when the network
     * needs nothing it can do right now.
     */
    @Nullable
    public static Job next(ServerLevel level, CitizenEntity citizen) {
        long now = level.getGameTime();
        if (now < nextSurveyAt) return null;
        nextSurveyAt = now + SURVEY_INTERVAL;

        PathMemory memory = PathMemory.get(level);
        Route route = memory.nextToImprove(now);
        if (route == null) return null;
        RoadGrade grade = route.pendingUpgrade();
        if (grade == null) return null;

        List<BlockPos> centreLine = centreLine(level, route);
        if (centreLine.isEmpty()) return null;

        List<CitizenPlan.Task> tasks = switch (grade) {
            case CLEARED -> clearingTasks(level, citizen, centreLine);
            case PAVED -> pavingTasks(level, citizen, centreLine);
            case LIT -> lightingTasks(level, citizen, centreLine);
            case SIGNPOSTED -> signTasks(level, citizen, route);
            case TRACK -> List.of();
        };
        if (tasks.isEmpty()) return null;
        return new Job(route, grade, tasks);
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Every block along the route's centre line, at the height of the ground.
     *
     * <p>Interpolated between waypoints, because waypoints are a sketch and a
     * road is continuous.</p>
     */
    public static List<BlockPos> centreLine(ServerLevel level, Route route) {
        List<BlockPos> line = new ArrayList<>();
        List<BlockPos> points = route.waypoints;
        if (points.size() < 2) return line;

        for (int i = 1; i < points.size(); i++) {
            BlockPos a = points.get(i - 1);
            BlockPos b = points.get(i);
            int steps = (int) Math.max(1, Math.sqrt(a.distSqr(b)));
            if (steps > 64) continue;   // a jump this big is a teleport, not a road
            for (int s = 0; s <= steps; s++) {
                int x = a.getX() + (b.getX() - a.getX()) * s / steps;
                int z = a.getZ() + (b.getZ() - a.getZ()) * s / steps;
                if (!level.isLoaded(new BlockPos(x, level.getMinBuildHeight() + 1, z))) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos ground = new BlockPos(x, y, z);
                if (line.isEmpty() || !line.get(line.size() - 1).equals(ground)) {
                    line.add(ground);
                }
            }
        }
        return line;
    }

    /** Cells either side of the centre line, so a road is wide enough to pass on. */
    private static List<BlockPos> surfaceCells(ServerLevel level, BlockPos centre) {
        List<BlockPos> cells = new ArrayList<>();
        cells.add(centre);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int w = 1; w <= ROAD_HALF_WIDTH; w++) {
                BlockPos side = centre.relative(dir, w);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        side.getX(), side.getZ());
                // Only widen where the ground is already at the road's level:
                // a road should follow terrain, not carve a trench through it.
                if (Math.abs(y - centre.getY()) <= 1) {
                    cells.add(new BlockPos(side.getX(), y, side.getZ()));
                }
            }
        }
        return cells;
    }

    // ------------------------------------------------------------------ the grades

    /** CLEARED: take out whatever a traveller would have to climb over. */
    private static List<CitizenPlan.Task> clearingTasks(ServerLevel level, CitizenEntity citizen,
                                                        List<BlockPos> line) {
        List<CitizenPlan.Task> tasks = new ArrayList<>();
        for (BlockPos ground : line) {
            if (tasks.size() >= JOB_SIZE) break;
            if (!inWorkingRange(citizen, ground)) continue;
            // Head room: a road you have to crouch through is not cleared.
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos cell = ground.above(dy);
                BlockState state = level.getBlockState(cell);
                if (state.isAir() || state.getCollisionShape(level, cell).isEmpty()) continue;
                if (!new ai.minecivilization.navigation.LevelBlockView(level).diggable(cell)) continue;
                if (ConstructionManager.get(level).protectsCell(cell)) continue;
                tasks.add(new CitizenPlan.Task(CitizenPlan.TaskType.GATHER, null, 1, null,
                        blockIdOf(level, cell), null,
                        new int[]{cell.getX(), cell.getY(), cell.getZ()}));
                break;
            }
        }
        return tasks;
    }

    /** PAVED: a proper surface underfoot, and a filled hole where there was none. */
    private static List<CitizenPlan.Task> pavingTasks(ServerLevel level, CitizenEntity citizen,
                                                      List<BlockPos> line) {
        String material = pavingMaterial(citizen);
        if (material == null) return List.of();

        List<CitizenPlan.Task> tasks = new ArrayList<>();
        int budget = citizen.getInventory().count(material);
        for (BlockPos ground : line) {
            if (tasks.size() >= JOB_SIZE || tasks.size() >= budget) break;
            if (!inWorkingRange(citizen, ground)) continue;
            for (BlockPos cell : surfaceCells(level, ground)) {
                if (tasks.size() >= JOB_SIZE || tasks.size() >= budget) break;
                BlockPos surface = cell.below();
                if (!placeable(level, citizen, surface)) continue;
                if (isPaving(level.getBlockState(surface))) continue;
                tasks.add(new CitizenPlan.Task(CitizenPlan.TaskType.ROADWORK, material, 1, null,
                        material, null,
                        new int[]{surface.getX(), surface.getY(), surface.getZ()}));
            }
        }
        return tasks;
    }

    /** LIT: torches at intervals, so nothing spawns on the colony's own roads. */
    private static List<CitizenPlan.Task> lightingTasks(ServerLevel level, CitizenEntity citizen,
                                                        List<BlockPos> line) {
        if (citizen.getInventory().count("minecraft:torch") < 1) return List.of();

        List<CitizenPlan.Task> tasks = new ArrayList<>();
        for (int i = 0; i < line.size(); i += TORCH_SPACING) {
            if (tasks.size() >= JOB_SIZE) break;
            BlockPos ground = line.get(i);
            if (!inWorkingRange(citizen, ground)) continue;
            // Beside the road, not on it: a torch in the middle of a path is
            // something to walk into.
            BlockPos spot = ground.relative(Direction.Plane.HORIZONTAL.iterator().next(),
                    ROAD_HALF_WIDTH + 1);
            spot = new BlockPos(spot.getX(), level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ()),
                    spot.getZ());
            if (!placeable(level, citizen, spot)) continue;
            if (level.getMaxLocalRawBrightness(spot) >= 9) continue;
            tasks.add(new CitizenPlan.Task(CitizenPlan.TaskType.ROADWORK, "minecraft:torch", 1,
                    null, "minecraft:torch", null,
                    new int[]{spot.getX(), spot.getY(), spot.getZ()}));
        }
        return tasks;
    }

    /** SIGNPOSTED: say where the road goes, at both ends. */
    private static List<CitizenPlan.Task> signTasks(ServerLevel level, CitizenEntity citizen,
                                                    Route route) {
        List<CitizenPlan.Task> tasks = new ArrayList<>();
        for (BlockPos end : new BlockPos[]{route.from, route.to}) {
            if (!inWorkingRange(citizen, end)) continue;
            BlockPos far = end.equals(route.from) ? route.to : route.from;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    end.getX(), end.getZ());
            BlockPos post = new BlockPos(end.getX(), y, end.getZ());
            if (!placeable(level, citizen, post)) continue;
            String label = ai.minecivilization.colony.SignPlan.roadSign(level, far);
            tasks.add(new CitizenPlan.Task(CitizenPlan.TaskType.SIGN, label, 1, "ROAD", null,
                    route.id, new int[]{post.getX(), post.getY(), post.getZ()}));
        }
        return tasks;
    }

    // ------------------------------------------------------------------ helpers

    /** What this citizen can pave with, best surface first. */
    @Nullable
    public static String pavingMaterial(CitizenEntity citizen) {
        for (String id : new String[]{"minecraft:gravel", "minecraft:cobblestone",
                "minecraft:stone", "minecraft:coarse_dirt", "minecraft:oak_planks",
                "minecraft:dirt"}) {
            if (citizen.getInventory().count(id) >= 4) return id;
        }
        return null;
    }

    private static boolean isPaving(BlockState state) {
        return state.is(Blocks.GRAVEL) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.STONE) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.OAK_PLANKS)
                || state.is(Blocks.DIRT_PATH);
    }

    private static boolean inWorkingRange(CitizenEntity citizen, BlockPos pos) {
        return pos.distSqr(citizen.blockPosition()) <= (long) WORK_RADIUS * WORK_RADIUS;
    }

    /** Safe to put a block here without burying a citizen or a chest. */
    private static boolean placeable(ServerLevel level, CitizenEntity citizen, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (!state.canBeReplaced() && !isSoftGround(state)) return false;
        if (level.getBlockEntity(pos) != null) return false;
        if (StorageDiscovery.isStorageBlock(level, pos)) return false;
        if (ConstructionManager.get(level).protectsCell(pos)) return false;
        if (!level.getFluidState(pos).isEmpty()) return false;
        return PlacementSafety.canOccupy(level, citizen, pos, false);
    }

    /** Ground a road may be laid straight over. */
    private static boolean isSoftGround(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)
                || state.is(Blocks.SAND) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.DIRT_PATH) || state.is(Blocks.SNOW);
    }

    private static String blockIdOf(ServerLevel level, BlockPos pos) {
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                .getKey(level.getBlockState(pos).getBlock());
        return key == null ? "minecraft:stone" : key.toString();
    }
}
