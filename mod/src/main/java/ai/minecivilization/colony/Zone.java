package ai.minecivilization.colony;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * One allotted piece of the colony's land, with a purpose.
 *
 * <p>A zone is the answer to "where do I go to do my job?". A lumberjack walks
 * to the managed forest rather than the nearest tree; a farmer works the
 * farmland rather than wherever someone once planted wheat. It also gives a
 * citizen far from home a way to know which way home is.</p>
 *
 * <p>Zones own a column, not a square: everything above ground for the surface
 * trades, and everything below it for a mine.</p>
 */
public final class Zone {

    public final String id;
    public final ZoneType type;
    public final ZoneLayout.Plot plot;
    public final int minX;
    public final int minZ;
    public final int maxX;
    public final int maxZ;
    /** Vertical band the zone owns, inclusive. */
    public int minY;
    public int maxY;
    public String name;
    public long createdAtGameTime;

    /**
     * For a {@link ZoneType#FOREST}: the tree species the colony has found and
     * replants here. Grows as expeditions discover new woods, which is how a
     * plantation ends up mixed rather than a monoculture of whatever grew
     * nearest the spawn.
     */
    public final Set<String> species = new LinkedHashSet<>();

    public Zone(String id, ZoneType type, ZoneLayout.Plot plot,
                int minX, int minZ, int maxX, int maxZ, int minY, int maxY) {
        this.id = id;
        this.type = type;
        this.plot = plot;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.minY = minY;
        this.maxY = maxY;
        this.name = type.label();
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getZ()) && pos.getY() >= minY && pos.getY() <= maxY;
    }

    /** Ignores height — "is this in the zone's footprint?". */
    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public int centerX() {
        return (minX + maxX) / 2;
    }

    public int centerZ() {
        return (minZ + maxZ) / 2;
    }

    /** Working centre of the zone — the spot a citizen heads for. */
    public BlockPos center() {
        return new BlockPos(centerX(), (minY + maxY) / 2, centerZ());
    }

    public double distanceSqrTo(BlockPos pos) {
        // Distance to the footprint, not its centre: standing at the edge of a
        // zone counts as being there.
        int dx = pos.getX() < minX ? minX - pos.getX() : Math.max(0, pos.getX() - maxX);
        int dz = pos.getZ() < minZ ? minZ - pos.getZ() : Math.max(0, pos.getZ() - maxZ);
        return (double) dx * dx + (double) dz * dz;
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** Record a tree species this plantation is stocked with. */
    public boolean recordSpecies(String saplingId) {
        return saplingId != null && !saplingId.isEmpty() && species.add(saplingId);
    }

    @Override
    public String toString() {
        return type + " '" + name + "' [" + minX + "," + minZ + " .. " + maxX + "," + maxZ + "]";
    }
}
