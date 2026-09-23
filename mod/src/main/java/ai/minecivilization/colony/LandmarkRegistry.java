package ai.minecivilization.colony;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * The colony's gazetteer: every useful place it knows about, shared by everyone.
 *
 * <p>Citizens had no memory of where anything was. Each craft re-scanned the
 * ground for a workbench and each smelt re-scanned for a furnace, so a
 * workstation further away than one search radius may as well not have
 * existed — a citizen could stand in a settlement full of them and report that
 * it could not find one. Worse, it was per-citizen: a furnace one built was
 * invisible to the rest.</p>
 *
 * <p>Knowing where something is does not make it free to reach. Distance is
 * still walked, and walking still escalates to bridging and digging when it has
 * to — this only removes the need to <em>rediscover</em> the settlement's own
 * infrastructure over and over.</p>
 *
 * <p>Saved with the world, because a colony that forgot its own workshops on
 * every restart would rebuild them.</p>
 */
public final class LandmarkRegistry extends SavedData {
    private static final String KEY = "minecivilization_landmarks";

    /** Upper bound per kind, so a redstone farm cannot fill the save file. */
    private static final int MAX_PER_KIND = 256;

    /** Position key to landmark, so one block is one entry however often it is seen. */
    private final Map<Long, Landmark> byPosition = new LinkedHashMap<>();

    /** One remembered place. */
    public record Landmark(LandmarkKind kind, BlockPos pos, long noticedAtGameTime) {
    }

    public static LandmarkRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(LandmarkRegistry::new, LandmarkRegistry::load, null), KEY);
    }

    // ------------------------------------------------------------------ recording

    /**
     * Write down what is at this position, if it is worth remembering.
     *
     * @return true when the colony learned something new
     */
    public boolean notice(ServerLevel level, BlockPos pos) {
        if (pos == null) return false;
        var block = level.getBlockState(pos).getBlock();
        var key = ForgeRegistries.BLOCKS.getKey(block);
        LandmarkKind kind = key == null ? null : LandmarkKind.of(key.toString());

        long id = pos.asLong();
        if (kind == null) {
            // Whatever was here is gone: stop sending citizens to it.
            return byPosition.remove(id) != null && markDirty();
        }
        Landmark existing = byPosition.get(id);
        if (existing != null && existing.kind() == kind) return false;
        if (existing == null && count(kind) >= MAX_PER_KIND) return false;

        byPosition.put(id, new Landmark(kind, pos.immutable(), level.getGameTime()));
        return markDirty();
    }

    /** Forget a place — the block was broken. */
    public void forget(BlockPos pos) {
        if (pos != null && byPosition.remove(pos.asLong()) != null) {
            setDirty();
        }
    }

    private boolean markDirty() {
        setDirty();
        return true;
    }

    // ------------------------------------------------------------------ queries

    /**
     * The nearest remembered place of this kind, or null when the colony has
     * never seen one.
     *
     * <p>Deliberately unbounded in distance: "far away" is a problem for the
     * walk, not for the memory.</p>
     */
    @Nullable
    public BlockPos nearest(LandmarkKind kind, BlockPos from) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Landmark landmark : byPosition.values()) {
            if (landmark.kind() != kind) continue;
            double d = landmark.pos().distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = landmark.pos();
            }
        }
        return best;
    }

    public static BlockPos nearest(ServerLevel level, LandmarkKind kind, BlockPos from) {
        return get(level).nearest(kind, from);
    }

    public int count(LandmarkKind kind) {
        int n = 0;
        for (Landmark landmark : byPosition.values()) {
            if (landmark.kind() == kind) n++;
        }
        return n;
    }

    /** A census of everything the colony knows, for inspection and observations. */
    public Map<LandmarkKind, Integer> census() {
        Map<LandmarkKind, Integer> out = new EnumMap<>(LandmarkKind.class);
        for (Landmark landmark : byPosition.values()) {
            out.merge(landmark.kind(), 1, Integer::sum);
        }
        return out;
    }

    public List<Landmark> all() {
        return new ArrayList<>(byPosition.values());
    }

    /**
     * Drop entries whose block is no longer there.
     *
     * <p>Only checks loaded chunks: "I cannot see it from here" is not "it is
     * gone", and forgetting a workshop because nobody is standing in it would
     * make the colony rebuild it.</p>
     */
    public void prune(ServerLevel level) {
        List<Long> stale = new ArrayList<>();
        for (Map.Entry<Long, Landmark> entry : byPosition.entrySet()) {
            BlockPos pos = entry.getValue().pos();
            if (!level.isLoaded(pos)) continue;
            var key = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
            if (key == null || LandmarkKind.of(key.toString()) != entry.getValue().kind()) {
                stale.add(entry.getKey());
            }
        }
        if (!stale.isEmpty()) {
            stale.forEach(byPosition::remove);
            setDirty();
        }
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Landmark landmark : byPosition.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("kind", landmark.kind().name());
            t.putLong("pos", landmark.pos().asLong());
            t.putLong("at", landmark.noticedAtGameTime());
            list.add(t);
        }
        tag.put("landmarks", list);
        return tag;
    }

    public static LandmarkRegistry load(CompoundTag tag,
                                        net.minecraft.core.HolderLookup.Provider registries) {
        LandmarkRegistry registry = new LandmarkRegistry();
        ListTag list = tag.getList("landmarks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            LandmarkKind kind;
            try {
                kind = LandmarkKind.valueOf(t.getString("kind"));
            } catch (IllegalArgumentException ex) {
                continue;   // a kind removed between versions: drop it, keep the rest
            }
            BlockPos pos = BlockPos.of(t.getLong("pos"));
            registry.byPosition.put(pos.asLong(), new Landmark(kind, pos, t.getLong("at")));
        }
        return registry;
    }
}
