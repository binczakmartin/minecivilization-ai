package ai.minecivilization.colony;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The colony's own record of itself: when it was founded, and who has been
 * born and lost since.
 *
 * <p>Saved with the world because these are facts about the settlement's
 * history, not about this session. A colony that forgot its last birth on
 * every restart would double its population every time the player logged back
 * in.</p>
 */
public final class ColonyData extends SavedData {
    private static final String KEY = "minecivilization_colony";

    private long foundedTick = -1;
    private long lastBirthTick = Long.MIN_VALUE;
    private int births;
    private int deaths;

    public static ColonyData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ColonyData::new, ColonyData::load, null), KEY);
    }

    /** Game time the colony was first recognised, or -1 before then. */
    public long foundedTick() {
        return foundedTick;
    }

    public int births() {
        return births;
    }

    public int deaths() {
        return deaths;
    }

    /**
     * Ticks since the last birth, or {@link Long#MAX_VALUE} when none has
     * happened — a brand-new colony must not be told to wait.
     */
    public long ticksSinceLastBirth(long now) {
        if (lastBirthTick == Long.MIN_VALUE) return Long.MAX_VALUE;
        return Math.max(0, now - lastBirthTick);
    }

    public void found(long now) {
        if (foundedTick >= 0) return;
        foundedTick = now;
        setDirty();
    }

    public void recordBirth(long now) {
        lastBirthTick = now;
        births++;
        setDirty();
    }

    public void recordDeath() {
        deaths++;
        setDirty();
    }

    /** Days of game time since the colony was founded. */
    public long ageInDays(long now) {
        return foundedTick < 0 ? 0 : Math.max(0, (now - foundedTick) / 24_000L);
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        tag.putLong("founded", foundedTick);
        tag.putLong("lastBirth", lastBirthTick);
        tag.putInt("births", births);
        tag.putInt("deaths", deaths);
        return tag;
    }

    public static ColonyData load(CompoundTag tag,
                                  net.minecraft.core.HolderLookup.Provider registries) {
        ColonyData data = new ColonyData();
        data.foundedTick = tag.contains("founded") ? tag.getLong("founded") : -1;
        data.lastBirthTick = tag.contains("lastBirth") ? tag.getLong("lastBirth") : Long.MIN_VALUE;
        data.births = tag.getInt("births");
        data.deaths = tag.getInt("deaths");
        return data;
    }
}
