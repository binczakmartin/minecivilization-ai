package ai.minecivilization.colony;

import java.util.List;

import ai.minecivilization.architecture.HouseCatalog;
import ai.minecivilization.construction.Blueprint;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.construction.StarterWarehouse;
import ai.minecivilization.construction.TownHall;
import ai.minecivilization.telemetry.ColonyEventLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Deciding what the colony builds next, and where.
 *
 * <p>Left to itself the settlement planned houses and nothing else, in a row
 * beside whichever bed it found, forever. There was no notion that a colony
 * wants a centre before it wants a fourth bedroom, or that a warehouse belongs
 * in the warehouse district rather than wherever the camp happened to start.
 * The result was a village that never became a town.</p>
 *
 * <p>This is the civic building programme: a town hall first, then storage,
 * then workshops, each raised inside the district the land registry already
 * allotted for it. Because the sites come from {@link ZoneManager}, the town
 * that emerges has a shape — a civic centre, housing around it, industry
 * beyond — rather than being a heap of buildings.</p>
 *
 * <p>One project per call, so the town grows steadily and no single tick ever
 * plans a district's worth of construction.</p>
 */
public final class CivicPlanner {

    /** Citizens before the colony takes on its town hall. */
    public static final int TOWN_HALL_POPULATION = 2;
    /** Citizens before workshops are worth the materials. */
    public static final int WORKSHOP_POPULATION = 6;
    /** Active projects at once. More than this and nothing finishes. */
    public static final int MAX_ACTIVE_PROJECTS = 6;

    private CivicPlanner() {
    }

    /**
     * Plan at most one civic building.
     *
     * @return the project created, or null when the colony needs nothing new
     */
    @Nullable
    public static ConstructionProject ensure(ServerLevel level, int population) {
        if (level == null || population <= 0) return null;
        ConstructionManager manager = ConstructionManager.get(level);
        // District markers do not count. They are signposts, not buildings, and
        // there is one per district — a colony of sixteen had twenty-nine of
        // them, which put the active-project count past this ceiling before a
        // single real building existed. The town hall was therefore never
        // planned at all: the most important building in the settlement was
        // crowded out by its own signage.
        if (buildingCount(manager) >= MAX_ACTIVE_PROJECTS) return null;

        ConstructionProject townHall = ensureTownHall(level, manager, population);
        if (townHall != null) return townHall;

        ConstructionProject warehouse = ensureWarehouse(level, manager, population);
        if (warehouse != null) return warehouse;

        return ensureWorkshop(level, manager, population);
    }

    // ------------------------------------------------------------------ the hall

    /**
     * The colony's centre.
     *
     * <p>Raised in the civic district if one has been allotted, and at the town
     * centre itself otherwise — a colony should not have to wait for its land
     * registry to start building.</p>
     */
    @Nullable
    private static ConstructionProject ensureTownHall(ServerLevel level,
                                                      ConstructionManager manager,
                                                      int population) {
        if (population < TOWN_HALL_POPULATION) return null;
        for (ConstructionProject project : manager.all()) {
            if (TownHall.isTownHall(project.blueprintId)) return null;   // one is enough
        }

        Blueprint blueprint = TownHall.create(HouseCatalog.dominantSpecies(level));
        BlockPos site = siteIn(level, ZoneType.CIVIC, blueprint);
        ConstructionProject project = manager.createProject("Town Hall", blueprint.id,
                site.getX(), site.getY(), site.getZ(), level.getGameTime());
        ColonyEventLog.of(level).construction(level, null,
                "the colony began its Town Hall at "
                        + site.getX() + "," + site.getY() + "," + site.getZ());
        ColonyNotifier.milestone(level, "Work has started on the Town Hall.");
        return project;
    }

    // ------------------------------------------------------------------ storage

    /** One warehouse per storage district, because that is what a district is for. */
    @Nullable
    private static ConstructionProject ensureWarehouse(ServerLevel level,
                                                       ConstructionManager manager,
                                                       int population) {
        if (population < TOWN_HALL_POPULATION) return null;
        List<Zone> districts = ZoneManager.get(level).byType(ZoneType.STORAGE);
        if (districts.isEmpty()) return null;

        int existing = 0;
        for (ConstructionProject project : manager.all()) {
            if (StarterWarehouse.ID.equals(project.blueprintId)) existing++;
        }
        if (existing >= districts.size()) return null;

        Blueprint blueprint = ConstructionManager.blueprint(StarterWarehouse.ID);
        if (blueprint == null) return null;
        BlockPos site = siteIn(level, ZoneType.STORAGE, blueprint);
        ConstructionProject project = manager.createProject(
                "Warehouse " + (existing + 1), blueprint.id,
                site.getX(), site.getY(), site.getZ(), level.getGameTime());
        ColonyEventLog.of(level).construction(level, null,
                "a new warehouse was planned at " + site.getX() + "," + site.getZ());
        return project;
    }

    // ------------------------------------------------------------------ industry

    /**
     * A workshop: for now the same shell as a warehouse, sited in the
     * industrial district.
     *
     * <p>Reusing the shell is deliberate — a second building the colony can
     * actually finish is worth more than a bespoke one it cannot.</p>
     */
    @Nullable
    private static ConstructionProject ensureWorkshop(ServerLevel level,
                                                      ConstructionManager manager,
                                                      int population) {
        if (population < WORKSHOP_POPULATION) return null;
        List<Zone> districts = ZoneManager.get(level).byType(ZoneType.INDUSTRIAL);
        if (districts.isEmpty()) return null;

        int existing = 0;
        for (ConstructionProject project : manager.all()) {
            if (project.name.startsWith("Workshop")) existing++;
        }
        if (existing >= districts.size()) return null;

        Blueprint blueprint = ConstructionManager.blueprint(StarterWarehouse.ID);
        if (blueprint == null) return null;
        BlockPos site = siteIn(level, ZoneType.INDUSTRIAL, blueprint);
        ConstructionProject project = manager.createProject(
                "Workshop " + (existing + 1), blueprint.id,
                site.getX(), site.getY(), site.getZ(), level.getGameTime());
        ColonyEventLog.of(level).construction(level, null,
                "a new workshop was planned at " + site.getX() + "," + site.getZ());
        return project;
    }

    // ------------------------------------------------------------------ siting

    /**
     * A build site inside a district, clear of anything already standing there.
     *
     * <p>Falls back to the town centre when the district does not exist yet, so
     * construction is never blocked on zoning.</p>
     */
    public static BlockPos siteIn(ServerLevel level, ZoneType type, Blueprint blueprint) {
        ZoneManager zones = ZoneManager.get(level);
        BlockPos centre = zones.townCenter(level);
        Zone zone = zones.nearest(type, centre);
        BlockPos anchor = zone == null ? centre : new BlockPos(zone.centerX(), 0, zone.centerZ());

        ConstructionManager manager = ConstructionManager.get(level);
        int width = blueprint.footprintWidth();
        int depth = blueprint.footprintDepth();

        // Spiral outward from the district centre until a plot is free. The
        // step is the building's own size, so plots tile rather than overlap.
        for (int ring = 0; ring <= 4; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = anchor.getX() + dx * (width + 3);
                    int z = anchor.getZ() + dz * (depth + 3);
                    if (isClear(manager, blueprint, x, z)) {
                        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                x + width / 2, z + depth / 2);
                        return new BlockPos(x - blueprint.minX, y, z - blueprint.minZ);
                    }
                }
            }
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                anchor.getX(), anchor.getZ());
        return new BlockPos(anchor.getX(), y, anchor.getZ());
    }

    /** No existing project's footprint overlaps this plot. */
    private static boolean isClear(ConstructionManager manager, Blueprint blueprint,
                                   int x, int z) {
        int width = blueprint.footprintWidth();
        int depth = blueprint.footprintDepth();
        for (ConstructionProject other : manager.all()) {
            Blueprint theirs = ConstructionManager.blueprint(other.blueprintId);
            if (theirs == null) continue;
            int ox = other.originX + theirs.minX;
            int oz = other.originZ + theirs.minZ;
            boolean apart = x + width + 2 <= ox
                    || ox + theirs.footprintWidth() + 2 <= x
                    || z + depth + 2 <= oz
                    || oz + theirs.footprintDepth() + 2 <= z;
            if (!apart) return false;
        }
        return true;
    }

    /** Unfinished projects that are actually buildings, markers excluded. */
    private static int buildingCount(ConstructionManager manager) {
        int active = 0;
        for (ConstructionProject project : manager.all()) {
            if (project.status == ConstructionProject.Status.COMPLETED
                    || project.status == ConstructionProject.Status.FAILED) continue;
            if (DistrictMarker.isMarker(project.blueprintId)) continue;
            active++;
        }
        return active;
    }

    /** The colony's town hall, if it has one standing or under way. */
    @Nullable
    public static ConstructionProject townHall(ServerLevel level) {
        for (ConstructionProject project : ConstructionManager.get(level).all()) {
            if (TownHall.isTownHall(project.blueprintId)) return project;
        }
        return null;
    }
}
