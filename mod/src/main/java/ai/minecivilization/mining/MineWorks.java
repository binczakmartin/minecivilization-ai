package ai.minecivilization.mining;

import java.util.LinkedHashSet;
import java.util.Set;

import ai.minecivilization.colony.Zone;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.colony.ZoneType;
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
 * The colony's shared mine: one entrance, one stair, dug by everybody.
 *
 * <p>Miners each scratching their own hole find ore by luck and never reach the
 * deep seams, because nobody digs far enough alone. What makes a mine a
 * <em>colony</em> mine is that its progress is shared state: whoever turns up
 * next carries on from where the last one stopped, and all of them use the same
 * lit stair and the same depots.</p>
 *
 * <p>Saved with the world — a mine that forgot its own depth every restart
 * would be dug from the surface forever.</p>
 */
public final class MineWorks extends SavedData {
    private static final String KEY = "minecivilization_mine";

    private boolean founded;
    private int entranceX;
    private int entranceY;
    private int entranceZ;
    /** How deep the stair has actually been cut. */
    private int depth;
    /** Levels whose landing, sign and chest are finished. */
    private final Set<String> levelsDone = new LinkedHashSet<>();
    private boolean entranceMarked;
    private final Set<BlockPos> passages = new LinkedHashSet<>();
    public Set<BlockPos> passages() { return java.util.Collections.unmodifiableSet(passages); }
    public void recordPassage(BlockPos pos) {
        if (passages.size() < 2048 && passages.add(pos.immutable())) setDirty();
    }

    public static MineWorks get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MineWorks::new, MineWorks::load, null), KEY);
    }

    // ------------------------------------------------------------------ the entrance

    public boolean isFounded() {
        return founded;
    }

    @Nullable
    public BlockPos entrance() {
        return founded ? new BlockPos(entranceX, entranceY, entranceZ) : null;
    }

    /**
     * Settle where the mine starts: the middle of the colony's mining district.
     *
     * <p>Chosen once and then kept, because a mine whose entrance moves is two
     * half-dug mines.</p>
     */
    @Nullable
    public BlockPos found(ServerLevel level) {
        if (founded) return entrance();

        Zone district = ZoneManager.get(level)
                .nearest(ZoneType.MINE, ZoneManager.get(level).townCenter(level));
        if (district == null) return null;

        int x = district.centerX();
        int z = district.centerZ();
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        entranceX = x;
        entranceY = y;
        entranceZ = z;
        depth = 0;
        founded = true;
        setDirty();
        return entrance();
    }

    public boolean isEntranceMarked() {
        return entranceMarked;
    }

    public void markEntrance() {
        entranceMarked = true;
        setDirty();
    }

    // ------------------------------------------------------------------ progress

    /** Blocks of stair cut so far. */
    public int depth() {
        return depth;
    }

    /** The height the stair has reached. */
    public int currentY() {
        return entranceY - depth;
    }

    public void deepenBy(int steps) {
        if (steps <= 0) return;
        depth += steps;
        setDirty();
    }

    /** The next depth worth stopping at, or null when the mine is finished. */
    @Nullable
    public MineLayout.Level nextLevel(ServerLevel level) {
        if (!founded) return null;
        for (MineLayout.Level candidate
                : MineLayout.levelsBelow(entranceY, level.getMinBuildHeight())) {
            if (!levelsDone.contains(candidate.ore())) return candidate;
        }
        return null;
    }

    public boolean isLevelDone(MineLayout.Level level) {
        return levelsDone.contains(level.ore());
    }

    public void finishLevel(MineLayout.Level level) {
        if (levelsDone.add(level.ore())) setDirty();
    }

    public int levelsFinished() {
        return levelsDone.size();
    }

    /**
     * Which leg of the stair is being cut — legs alternate direction so the
     * mine folds back on itself instead of running away from the settlement.
     */
    public int legIndex() {
        return levelsDone.size();
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        tag.putLongArray("passages", passages.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putBoolean("founded", founded);
        tag.putInt("x", entranceX);
        tag.putInt("y", entranceY);
        tag.putInt("z", entranceZ);
        tag.putInt("depth", depth);
        tag.putBoolean("entranceMarked", entranceMarked);
        ListTag done = new ListTag();
        for (String ore : levelsDone) done.add(StringTag.valueOf(ore));
        tag.put("levelsDone", done);
        return tag;
    }

    public static MineWorks load(CompoundTag tag,
                                 net.minecraft.core.HolderLookup.Provider registries) {
        MineWorks works = new MineWorks();
        for (long p : tag.getLongArray("passages")) works.passages.add(BlockPos.of(p));
        works.founded = tag.getBoolean("founded");
        works.entranceX = tag.getInt("x");
        works.entranceY = tag.getInt("y");
        works.entranceZ = tag.getInt("z");
        works.depth = tag.getInt("depth");
        works.entranceMarked = tag.getBoolean("entranceMarked");
        ListTag done = tag.getList("levelsDone", Tag.TAG_STRING);
        for (int i = 0; i < done.size(); i++) {
            works.levelsDone.add(done.getString(i));
        }
        return works;
    }
}
