package ai.minecivilization.construction;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A live construction project. Status lifecycle:
 * PROPOSED → PLANNED → WAITING_FOR_RESOURCES → BUILDING → COMPLETED (| FAILED | PAUSED)
 *
 * <p>Progress is measured by physically placed blocks. No creative pasting.</p>
 */
public final class ConstructionProject {
    public enum Status {
        PROPOSED, PLANNED, WAITING_FOR_RESOURCES, BUILDING, COMPLETED, FAILED, PAUSED
    }

    public final String id;
    public final String name;
    public final String blueprintId;
    /**
     * Blueprint origin in world coordinates. Mutable only so a project that
     * owns no placed blocks yet can be re-homed when the camp anchor moves
     * ({@link ConstructionManager#relocate(ConstructionProject, int, int, int)}).
     * Once a single block stands there the origin is frozen: moving it would
     * strand that block.
     */
    public int originX;
    public int originY;
    public int originZ;
    public Status status = Status.PROPOSED;

    /** Absolute positions ("x,y,z") of blocks already physically placed. */
    public final Set<String> placed = new LinkedHashSet<>();
    /** Absolute positions owned by this project, even if a player changed them. */
    public final Set<String> ownedCells = new LinkedHashSet<>();

    public long createdAtGameTime;

    public ConstructionProject(String id, String name, String blueprintId,
                               int originX, int originY, int originZ) {
        this.id = id;
        this.name = name;
        this.blueprintId = blueprintId;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
    }

    public double progress(Blueprint blueprint) {
        int total = blueprint.blockCount();
        if (total == 0) return 1.0;
        return Math.min(1.0, (double) placed.size() / total);
    }

    public boolean isFinished(Blueprint blueprint) {
        return placed.size() >= blueprint.blockCount();
    }

    public String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
