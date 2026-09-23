package ai.minecivilization.colony;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.construction.Blueprint;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The small post that says what a district is, and the chest that starts it.
 *
 * <p>A settlement of eighteen plots with nothing written on any of them is a
 * settlement nobody can read — not a player walking through it, and not a
 * citizen deciding where to take a load of stone. Every district gets a sign
 * naming it and a double chest beside it, so the place explains itself from
 * inside the world and every district has somewhere to put the materials its
 * first building needs.</p>
 *
 * <p>Both become landmarks the moment they are placed, so the whole colony
 * learns the district's depot at once.</p>
 */
public final class DistrictMarker {

    /** Prefix identifying a marker project among the colony's construction. */
    public static final String ID_PREFIX = "marker_";

    private DistrictMarker(){
    }

    public static boolean isMarker(String blueprintId) {
        return blueprintId != null && blueprintId.startsWith(ID_PREFIX);
    }

    /**
     * A post with a sign on it and a double chest alongside.
     *
     * <p>Two chests side by side merge into one double chest in vanilla, which
     * is what "a chest big enough to start a building" means in practice.</p>
     */
    public static Blueprint blueprint(String species) {
        String wood = species == null ? "oak" : species;
        String id = ID_PREFIX + wood;
        Blueprint existing = ConstructionManager.blueprint(id);
        if (existing != null) return existing;

        List<Blueprint.BlockEntry> entries = new ArrayList<>();
        // The post, then the sign on top of it: a sign needs something under it.
        entries.add(new Blueprint.BlockEntry(0, 0, 0, "minecraft:" + wood + "_log"));
        entries.add(new Blueprint.BlockEntry(0, 1, 0, "minecraft:" + wood + "_sign"));
        // The depot, one block clear of the post so both stay reachable.
        entries.add(new Blueprint.BlockEntry(2, 0, 0, "minecraft:chest"));
        entries.add(new Blueprint.BlockEntry(3, 0, 0, "minecraft:chest"));
        // A torch, because an unlit depot is where things go missing at night.
        entries.add(new Blueprint.BlockEntry(1, 1, 0, "minecraft:torch"));

        Blueprint marker = new Blueprint(id, "District Marker", 4, 2, 1, entries);
        ConstructionManager.registerBlueprint(marker);
        return marker;
    }

    /**
     * Where a district's marker goes: just inside its near corner, clear of
     * whatever gets built in the middle.
     */
    public static BlockPos siteFor(ServerLevel level, Zone zone) {
        int x = zone.minX + 1;
        int z = zone.minZ + 1;
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                x, z);
        return new BlockPos(x, y, z);
    }

    /**
     * Raise the marker for a district, if it has none yet.
     *
     * @return the new project, or null when one already exists or there is no room
     */
    public static ConstructionProject ensureFor(ServerLevel level, Zone zone) {
        ConstructionManager manager = ConstructionManager.get(level);
        for (ConstructionProject existing : manager.all()) {
            if (isMarker(existing.blueprintId) && existing.name.endsWith(zone.id)) {
                return null;   // this district already has one
            }
        }

        String species = ai.minecivilization.architecture.HouseCatalog.dominantSpecies(level);
        Blueprint blueprint = blueprint(species);
        BlockPos site = siteFor(level, zone);

        // The project name carries the district so the sign can be written when
        // the build finishes, and so a second marker is never planned for it.
        ConstructionProject project = manager.createProject(
                Signpost.titleFor(zone.type) + " marker " + zone.id,
                blueprint.id, site.getX(), site.getY(), site.getZ(), level.getGameTime());
        return project;
    }

    /**
     * Write the district's name onto the sign once the post is standing.
     *
     * <p>Called when a marker project completes: a blueprint can place a sign
     * but not say anything on it.</p>
     */
    public static void stamp(ServerLevel level, ConstructionProject project) {
        if (!isMarker(project.blueprintId)) return;

        Zone zone = zoneOf(level, project);
        String title = zone == null ? project.name : Signpost.titleFor(zone.type);
        String detail = zone == null ? "" : zone.name;

        // The sign sits one block above the post at the project origin.
        BlockPos sign = new BlockPos(project.originX, project.originY + 1, project.originZ);
        Signpost.write(level, sign, title, detail);
    }

    private static Zone zoneOf(ServerLevel level, ConstructionProject project) {
        for (Zone zone : ZoneManager.get(level).all()) {
            if (project.name.endsWith(zone.id)) return zone;
        }
        return null;
    }
}
