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
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Every sign the colony has put up, and every sign it has read.
 *
 * <p>This is what makes signage more than decoration. A sign is simultaneously
 * a thing in the world and a fact in the colony's memory, and the two stay in
 * step in both directions: when a citizen writes {@code DANGER — DEEP CAVE} on
 * a post, the colony learns to avoid the place; when a <em>player</em> writes
 * the same thing, the colony learns it too, because signs are read back on
 * sight.</p>
 *
 * <p>The practical effect is a settlement a player can steer without a single
 * command: label a spot and the citizens treat it as labelled.</p>
 */
public final class SignRegistry extends SavedData {
    private static final String KEY = "minecivilization_signs";

    /** Cap per kind, so a wall of signs cannot bloat the save. */
    private static final int MAX_PER_KIND = 128;

    /** One remembered sign. */
    public record Marker(SignKind kind, BlockPos pos, String title, String detail,
                         long placedAtGameTime, boolean placedByColony) {

        public String text() {
            return detail == null || detail.isBlank() ? title : title + " — " + detail;
        }
    }

    private final Map<Long, Marker> byPosition = new LinkedHashMap<>();

    public static SignRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SignRegistry::new, SignRegistry::load, null), KEY);
    }

    // ------------------------------------------------------------------ writing

    /** Record a sign the colony just put up. */
    public Marker record(ServerLevel level, BlockPos pos, SignPlan.Text text) {
        return record(level, pos, text.kind(), text.title(), text.detail(), true);
    }

    public Marker record(ServerLevel level, BlockPos pos, SignKind kind,
                         String title, String detail, boolean byColony) {
        if (pos == null || kind == null) return null;
        if (!byPosition.containsKey(pos.asLong()) && count(kind) >= MAX_PER_KIND) return null;
        Marker marker = new Marker(kind, pos.immutable(), title == null ? "" : title,
                detail == null ? "" : detail, level.getGameTime(), byColony);
        byPosition.put(pos.asLong(), marker);
        setDirty();
        return marker;
    }

    /**
     * Look at a sign in the world and remember whatever it says.
     *
     * <p>Only signs whose first line the colony recognises are kept: a player's
     * shopping list is not a fact about the settlement.</p>
     *
     * @return the marker learned, or null when there is no readable sign here
     */
    @Nullable
    public Marker readFromWorld(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            forget(pos);
            return null;
        }
        var messages = sign.getFrontText().getMessages(false);
        if (messages.length == 0) return null;

        String title = messages[0].getString().trim();
        SignKind kind = SignKind.parse(title);
        if (kind == null) return null;

        StringBuilder detail = new StringBuilder();
        for (int i = 1; i < messages.length; i++) {
            String line = messages[i].getString().trim();
            if (line.isEmpty()) continue;
            if (detail.length() > 0) detail.append(' ');
            detail.append(line);
        }
        Marker existing = byPosition.get(pos.asLong());
        boolean byColony = existing != null && existing.placedByColony();
        return record(level, pos, kind, title, detail.toString(), byColony);
    }

    public void forget(BlockPos pos) {
        if (pos != null && byPosition.remove(pos.asLong()) != null) setDirty();
    }

    // ------------------------------------------------------------------ queries

    public boolean hasSignNear(SignKind kind, BlockPos pos, int radius) {
        long limit = (long) radius * radius;
        for (Marker marker : byPosition.values()) {
            if (marker.kind() == kind && marker.pos().distSqr(pos) <= limit) return true;
        }
        return false;
    }

    /** Any sign at all within this radius — used to avoid a forest of posts. */
    public boolean hasAnySignNear(BlockPos pos, int radius) {
        long limit = (long) radius * radius;
        for (Marker marker : byPosition.values()) {
            if (marker.pos().distSqr(pos) <= limit) return true;
        }
        return false;
    }

    @Nullable
    public Marker nearest(SignKind kind, BlockPos from) {
        Marker best = null;
        double bestDist = Double.MAX_VALUE;
        for (Marker marker : byPosition.values()) {
            if (marker.kind() != kind) continue;
            double d = marker.pos().distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = marker;
            }
        }
        return best;
    }

    /** Places the colony has been told to be careful about. */
    public List<Marker> hazards() {
        List<Marker> out = new ArrayList<>();
        for (Marker marker : byPosition.values()) {
            if (marker.kind() == SignKind.DANGER || marker.kind() == SignKind.CAVE) {
                out.add(marker);
            }
        }
        return out;
    }

    /** True when somewhere has been marked dangerous — citizens route around it. */
    public boolean isMarkedDangerous(BlockPos pos, int radius) {
        long limit = (long) radius * radius;
        for (Marker marker : byPosition.values()) {
            if (marker.kind() == SignKind.DANGER && marker.pos().distSqr(pos) <= limit) {
                return true;
            }
        }
        return false;
    }

    public int count(SignKind kind) {
        int n = 0;
        for (Marker marker : byPosition.values()) {
            if (marker.kind() == kind) n++;
        }
        return n;
    }

    public Map<SignKind, Integer> census() {
        Map<SignKind, Integer> out = new EnumMap<>(SignKind.class);
        for (Marker marker : byPosition.values()) {
            out.merge(marker.kind(), 1, Integer::sum);
        }
        return out;
    }

    public List<Marker> all() {
        return new ArrayList<>(byPosition.values());
    }

    public int size() {
        return byPosition.size();
    }

    /** Drop signs whose post is gone, in loaded chunks only. */
    public void prune(ServerLevel level) {
        List<Long> stale = new ArrayList<>();
        for (Map.Entry<Long, Marker> entry : byPosition.entrySet()) {
            BlockPos pos = entry.getValue().pos();
            if (!level.isLoaded(pos)) continue;
            if (!(level.getBlockEntity(pos) instanceof SignBlockEntity)) stale.add(entry.getKey());
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
        for (Marker marker : byPosition.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("kind", marker.kind().name());
            t.putLong("pos", marker.pos().asLong());
            t.putString("title", marker.title());
            t.putString("detail", marker.detail());
            t.putLong("at", marker.placedAtGameTime());
            t.putBoolean("ours", marker.placedByColony());
            list.add(t);
        }
        tag.put("signs", list);
        return tag;
    }

    public static SignRegistry load(CompoundTag tag,
                                    net.minecraft.core.HolderLookup.Provider registries) {
        SignRegistry registry = new SignRegistry();
        ListTag list = tag.getList("signs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            SignKind kind;
            try {
                kind = SignKind.valueOf(t.getString("kind"));
            } catch (IllegalArgumentException ex) {
                continue;   // a kind removed between versions: drop it, keep the rest
            }
            BlockPos pos = BlockPos.of(t.getLong("pos"));
            registry.byPosition.put(pos.asLong(), new Marker(kind, pos,
                    t.getString("title"), t.getString("detail"),
                    t.getLong("at"), t.getBoolean("ours")));
        }
        return registry;
    }
}
