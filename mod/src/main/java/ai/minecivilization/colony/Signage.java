package ai.minecivilization.colony;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.citizen.CitizenPlan;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.storage.StorageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Finding the next thing in the colony that nobody has labelled.
 *
 * <p>A settlement labels itself for the same reason a warehouse does: because
 * the alternative is everybody having to remember. This walks the colony's own
 * records — districts, the town centre, warehouses, mines, active builds,
 * roads, places somebody came to grief — and produces one sign job for the
 * first thing it finds without a post.</p>
 *
 * <p>One job at a time, and only for places near the citizen, so signposting
 * costs a minute here and there rather than becoming a project of its own.</p>
 */
public final class Signage {

    /** How near a sign has to be to count as already labelling something. */
    private static final int LABELLED_RADIUS = 12;
    /** How far a citizen will go out of its way to put a sign up. */
    private static final int WORK_RADIUS = 64;
    /** Signs a citizen keeps on hand before it bothers making more. */
    public static final int SIGN_STOCK = 4;

    private Signage() {
    }

    /**
     * The next sign this citizen should put up, or null when everything nearby
     * is already labelled.
     */
    @Nullable
    public static CitizenPlan.Task next(ServerLevel level, CitizenEntity citizen) {
        if (!carriesSign(citizen)) return null;
        SignRegistry signs = SignRegistry.get(level);
        BlockPos at = citizen.blockPosition();

        // 1) The town centre. A colony with one sign should have this one.
        BlockPos centre = ZoneManager.get(level).townCenter(level);
        if (inRange(at, centre) && !signs.hasSignNear(SignKind.TOWN_HALL, centre, 24)) {
            return signTask(level, centre.offset(2, 0, 0),
                    SignPlan.townHall(ColonyData.get(level).name()));
        }

        // 2) Districts — the town's own zoning made visible.
        List<Zone> zones = ZoneManager.get(level).all();
        for (Zone zone : zones) {
            BlockPos marker = zone.center();
            if (!inRange(at, marker)) continue;
            SignKind kind = SignKind.forZone(zone.type);
            if (signs.hasSignNear(kind, marker, 24)) continue;
            int index = indexOf(zones, zone);
            return signTask(level, marker, SignPlan.district(zone.type, zone.name, index));
        }

        // 3) Warehouses, which is where everyone walks all day.
        for (var node : StorageManager.get(level).all()) {
            BlockPos pos = node.containerPos();
            if (!inRange(at, pos) || signs.hasAnySignNear(pos, LABELLED_RADIUS)) continue;
            return signTask(level, pos.offset(1, 0, 0),
                    new SignPlan.Text(SignKind.WAREHOUSE, SignKind.WAREHOUSE.headline(),
                            node.category == null ? "" : node.category.label()));
        }

        // 4) What is being built right now, with how far along it is.
        for (ConstructionProject project : ConstructionManager.get(level).all()) {
            if (project.status == ConstructionProject.Status.COMPLETED
                    || project.status == ConstructionProject.Status.FAILED) continue;
            BlockPos site = new BlockPos(project.originX, project.originY, project.originZ);
            if (!inRange(at, site) || signs.hasAnySignNear(site, LABELLED_RADIUS)) continue;
            var blueprint = ConstructionManager.blueprint(project.blueprintId);
            int percent = blueprint == null ? 0 : (int) (project.progress(blueprint) * 100);
            return signTask(level, site.offset(-1, 0, -1), SignPlan.project(project.name, percent));
        }

        // 5) The mine, which is the one place a wrong turn is expensive.
        BlockPos mine = ai.minecivilization.mining.MineWorks.get(level).found(level);
        if (mine != null && inRange(at, mine) && !signs.hasSignNear(SignKind.MINE, mine, 24)) {
            return signTask(level, mine.offset(1, 0, 0), SignPlan.mine(1, null, mine.getY()));
        }

        return null;
    }

    /**
     * Mark a spot as dangerous — somewhere a citizen died, or nearly did.
     *
     * <p>Returned as a job rather than written directly, because a warning
     * nobody walked to is a warning nobody can read.</p>
     */
    public static CitizenPlan.Task hazardSign(ServerLevel level, BlockPos where, String what) {
        return signTask(level, where, SignPlan.danger(what));
    }

    // ------------------------------------------------------------------ helpers

    private static CitizenPlan.Task signTask(ServerLevel level, BlockPos near,
                                             SignPlan.Text text) {
        BlockPos post = groundAt(level, near);
        return new CitizenPlan.Task(CitizenPlan.TaskType.SIGN, text.flat(), 1,
                text.kind().name(), null, null,
                new int[]{post.getX(), post.getY(), post.getZ()});
    }

    /** The first free cell above the ground, which is where a post goes. */
    private static BlockPos groundAt(ServerLevel level, BlockPos near) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                near.getX(), near.getZ());
        return new BlockPos(near.getX(), y, near.getZ());
    }

    private static boolean inRange(BlockPos from, BlockPos to) {
        return to != null && from.distSqr(to) <= (long) WORK_RADIUS * WORK_RADIUS;
    }

    private static boolean carriesSign(CitizenEntity citizen) {
        var inventory = citizen.getInventory();
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle",
                "acacia", "dark_oak", "cherry", "mangrove"}) {
            if (inventory.count("minecraft:" + wood + "_sign") > 0) return true;
        }
        return false;
    }

    /** Which of its kind a district is: mine 1, mine 2, and so on. */
    private static int indexOf(List<Zone> zones, Zone zone) {
        int index = 0;
        List<Zone> ofKind = new ArrayList<>();
        for (Zone candidate : zones) {
            if (candidate.type == zone.type) ofKind.add(candidate);
        }
        ofKind.sort(java.util.Comparator.comparingLong(z -> z.createdAtGameTime));
        for (Zone candidate : ofKind) {
            index++;
            if (candidate == zone) return index;
        }
        return 1;
    }
}
