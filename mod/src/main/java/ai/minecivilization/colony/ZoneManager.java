package ai.minecivilization.colony;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ai.minecivilization.construction.ConstructionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * The colony's land registry: which plots are allotted, to what, and where the
 * town centre is.
 *
 * <p>Saved with the world, because a town that forgot its own plan every
 * restart would re-allot the same ground to something else. The town centre is
 * captured the first time land is allotted and then held: the camp anchor may
 * drift as the player moves their bed, but a town does not pick itself up and
 * move because someone rearranged a bedroom.</p>
 */
public final class ZoneManager extends SavedData {
    private static final String KEY = "minecivilization_zones";

    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private boolean centered;
    private int townCenterX;
    private int townCenterY = Integer.MIN_VALUE;
    private int townCenterZ;

    public static ZoneManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ZoneManager::new, ZoneManager::load, null), KEY);
    }

    // ------------------------------------------------------------------ town centre

    /**
     * Where the town is planned from. Follows the camp anchor until the first
     * plot is allotted, then stays put.
     */
    public BlockPos townCenter(ServerLevel level) {
        if (!centered) {
            BlockPos anchor = ConstructionManager.get(level).resolveAnchor(level);
            return new BlockPos(anchor.getX(), anchor.getY(), anchor.getZ());
        }
        if (townCenterY == Integer.MIN_VALUE) {
            townCenterY = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    townCenterX, townCenterZ);
            setDirty();
        }
        return new BlockPos(townCenterX, townCenterY, townCenterZ);
    }

    public boolean hasTownCenter() {
        return centered;
    }

    /** Fix the town centre here — done once, when the first plot is allotted. */
    private void settle(ServerLevel level) {
        if (centered) return;
        BlockPos anchor = ConstructionManager.get(level).resolveAnchor(level);
        townCenterX = anchor.getX();
        townCenterY = anchor.getY();
        townCenterZ = anchor.getZ();
        centered = true;
        setDirty();
    }

    // ------------------------------------------------------------------ queries

    public List<Zone> all() {
        return new ArrayList<>(zones.values());
    }

    public List<Zone> byType(ZoneType type) {
        List<Zone> out = new ArrayList<>();
        for (Zone zone : zones.values()) {
            if (zone.type == type) out.add(zone);
        }
        return out;
    }

    public int count(ZoneType type) {
        int n = 0;
        for (Zone zone : zones.values()) {
            if (zone.type == type) n++;
        }
        return n;
    }

    @Nullable
    public Zone byId(String id) {
        return id == null ? null : zones.get(id);
    }

    /** The zone a position falls inside, or null for open country and streets. */
    @Nullable
    public Zone at(BlockPos pos) {
        for (Zone zone : zones.values()) {
            if (zone.contains(pos)) return zone;
        }
        return null;
    }

    /** Nearest zone of a type to a position — where a trade goes to work. */
    @Nullable
    public Zone nearest(ZoneType type, BlockPos from) {
        Zone best = null;
        double bestDist = Double.MAX_VALUE;
        for (Zone zone : zones.values()) {
            if (zone.type != type) continue;
            double d = zone.distanceSqrTo(from);
            if (d < bestDist) {
                bestDist = d;
                best = zone;
            }
        }
        return best;
    }

    /** How far out the town currently reaches, in blocks from its centre. */
    public int radius() {
        int ring = 0;
        for (Zone zone : zones.values()) {
            ring = Math.max(ring, zone.plot.ring());
        }
        return ZoneLayout.townRadius(ring);
    }

    // ------------------------------------------------------------------ allotment

    /**
     * Allot the next free plot to a new zone of this type.
     *
     * @return the new zone, or null when the town has no room left
     */
    @Nullable
    public Zone create(ServerLevel level, ZoneType type) {
        settle(level);
        BlockPos center = townCenter(level);

        Set<ZoneLayout.Plot> taken = new HashSet<>();
        for (Zone zone : zones.values()) {
            taken.add(zone.plot);
        }
        ZoneLayout.Plot plot = ZoneLayout.allocate(type, taken);
        if (plot == null) return null;

        ZoneLayout.Bounds bounds = ZoneLayout.bounds(center.getX(), center.getZ(), plot);
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                bounds.centerX(), bounds.centerZ());

        int minY;
        int maxY;
        if (type.isUnderground()) {
            // A mine owns the rock under its plot, from the surface to bedrock.
            minY = level.getMinBuildHeight() + 1;
            maxY = surface;
        } else {
            minY = surface - 8;
            maxY = surface + 24;
        }

        Zone zone = new Zone(UUID.randomUUID().toString().substring(0, 8), type, plot,
                bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ(), minY, maxY);
        zone.createdAtGameTime = level.getGameTime();
        zone.name = type.label() + " " + (count(type) + 1);
        zones.put(zone.id, zone);
        setDirty();
        return zone;
    }

    public boolean remove(String id) {
        boolean removed = zones.remove(id) != null;
        if (removed) setDirty();
        return removed;
    }

    /** Persist a change made to a zone's mutable fields (species, name, band). */
    public void touch() {
        setDirty();
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Zone zone : zones.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", zone.id);
            t.putString("type", zone.type.name());
            t.putInt("px", zone.plot.px());
            t.putInt("pz", zone.plot.pz());
            t.putInt("minX", zone.minX);
            t.putInt("minZ", zone.minZ);
            t.putInt("maxX", zone.maxX);
            t.putInt("maxZ", zone.maxZ);
            t.putInt("minY", zone.minY);
            t.putInt("maxY", zone.maxY);
            t.putString("name", zone.name);
            t.putLong("created", zone.createdAtGameTime);
            ListTag speciesList = new ListTag();
            for (String s : zone.species) {
                speciesList.add(StringTag.valueOf(s));
            }
            t.put("species", speciesList);
            list.add(t);
        }
        tag.put("zones", list);
        tag.putBoolean("centered", centered);
        tag.putInt("cx", townCenterX);
        tag.putInt("cy", townCenterY);
        tag.putInt("cz", townCenterZ);
        return tag;
    }

    public static ZoneManager load(CompoundTag tag,
                                   net.minecraft.core.HolderLookup.Provider registries) {
        ZoneManager manager = new ZoneManager();
        ListTag list = tag.getList("zones", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            ZoneType type;
            try {
                type = ZoneType.valueOf(t.getString("type"));
            } catch (IllegalArgumentException ex) {
                continue; // a zone kind removed between versions: drop it, keep the town
            }
            Zone zone = new Zone(t.getString("id"), type,
                    new ZoneLayout.Plot(t.getInt("px"), t.getInt("pz")),
                    t.getInt("minX"), t.getInt("minZ"), t.getInt("maxX"), t.getInt("maxZ"),
                    t.getInt("minY"), t.getInt("maxY"));
            if (t.contains("name")) zone.name = t.getString("name");
            zone.createdAtGameTime = t.getLong("created");
            ListTag speciesList = t.getList("species", Tag.TAG_STRING);
            for (int j = 0; j < speciesList.size(); j++) {
                zone.species.add(speciesList.getString(j));
            }
            manager.zones.put(zone.id, zone);
        }
        manager.centered = tag.getBoolean("centered");
        manager.townCenterX = tag.getInt("cx");
        if (tag.contains("cy")) manager.townCenterY = tag.getInt("cy");
        manager.townCenterZ = tag.getInt("cz");
        return manager;
    }
}
