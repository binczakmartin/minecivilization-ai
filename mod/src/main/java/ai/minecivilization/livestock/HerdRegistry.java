package ai.minecivilization.livestock;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

/** Colony ownership persists across chunk unloads and dimensions. Player pets are separate. */
public final class HerdRegistry extends SavedData {
    public static final String TAG = "minecivilization_animal";
    private final Map<UUID, String> animals = new HashMap<>();
    private final Map<UUID, Birth> births = new HashMap<>();
    private record Birth(String species, long expires) {}
    public static HerdRegistry get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(HerdRegistry::new, HerdRegistry::load, null), "minecivilization_herds");
    }
    public static String species(Animal a) { return ForgeRegistries.ENTITY_TYPES.getKey(a.getType()).toString(); }
    public static boolean owned(Animal a) { return a.getPersistentData().getBoolean(TAG); }
    public void register(Animal a) {
        a.getPersistentData().putBoolean(TAG, true);
        a.setPersistenceRequired();
        record(a.getUUID(), species(a));
    }
    void record(UUID id, String species) {
        if (!species.equals(animals.put(id, species))) setDirty();
    }
    public void remove(UUID id) {
        boolean removed = animals.remove(id) != null;
        if (births.remove(id) != null || removed) setDirty();
    }
    public int count(String species) { return (int) animals.values().stream().filter(species::equals).count(); }
    public int committed(String species, long now) { return count(species) + pending(species, now); }
    public int pending(String species, long now) {
        if (births.values().removeIf(b -> b.expires <= now)) setDirty();
        return (int) births.values().stream().filter(b -> b.species.equals(species)).count();
    }
    public boolean reserveBirth(Animal first, String species, int limit, long now) {
        return reserveBirth(first.getUUID(), species, limit, now);
    }
    boolean reserveBirth(UUID first, String species, int limit, long now) {
        int pending = pending(species, now);
        if (births.containsKey(first) || !HerdPolicy.canBreed(count(species), pending, 2, limit)) return false;
        births.put(first, new Birth(species, now + 1200)); setDirty(); return true;
    }
    public void finishBirth(Animal first, Animal second) {
        births.remove(first.getUUID()); births.remove(second.getUUID()); setDirty();
    }
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        animals.forEach((id, species) -> { CompoundTag t = new CompoundTag(); t.putUUID("id", id); t.putString("species", species); list.add(t); });
        tag.put("animals", list);
        ListTag pending = new ListTag();
        births.forEach((id, birth) -> { CompoundTag t = new CompoundTag(); t.putUUID("id", id); t.putString("species", birth.species); t.putLong("expires", birth.expires); pending.add(t); });
        tag.put("births", pending); return tag;
    }
    public static HerdRegistry load(CompoundTag tag, HolderLookup.Provider provider) {
        HerdRegistry r = new HerdRegistry();
        for (Tag raw : tag.getList("animals", Tag.TAG_COMPOUND)) { CompoundTag t = (CompoundTag) raw; if (t.hasUUID("id")) r.animals.put(t.getUUID("id"), t.getString("species")); }
        for (Tag raw : tag.getList("births", Tag.TAG_COMPOUND)) { CompoundTag t = (CompoundTag) raw; if (t.hasUUID("id")) r.births.put(t.getUUID("id"), new Birth(t.getString("species"), t.getLong("expires"))); }
        return r;
    }
}
