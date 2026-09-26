package ai.minecivilization.livestock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import ai.minecivilization.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Where the colony has seen wild livestock.
 *
 * <p>Husbandry only started when a shepherd happened to be within forty-eight
 * blocks of a sheep. In a savanna with the herds a few hundred blocks off, it
 * never started at all: no pen, no wool, no beds, no growth. Every citizen now
 * glances around as it works; a sighting anywhere is enough to build the pen
 * and send a shepherd out with a lure to bring the animals home.</p>
 */
public final class AnimalSightings {

    /** A sighting older than a Minecraft day is stale: herds wander. */
    private static final long FORGET_AFTER = 24_000;
    /** How far a glance reaches. */
    private static final int GLANCE = 32;

    private record Sighting(BlockPos pos, long seenAt, int count) {
    }

    /** dimension → species → latest sighting. */
    private static final Map<ResourceKey<Level>, Map<String, Sighting>> SEEN = new HashMap<>();

    private AnimalSightings() {
    }

    /** Called every few seconds per citizen: note any wild livestock in view. */
    public static void glance(ServerLevel level, CitizenEntity citizen) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, BlockPos> where = new HashMap<>();
        for (Animal animal : level.getEntitiesOfClass(Animal.class,
                citizen.getBoundingBox().inflate(GLANCE), a -> a.isAlive() && !a.isBaby())) {
            String species = HerdRegistry.species(animal);
            if (!AnimalHusbandry.isLivestock(species) || HerdRegistry.owned(animal)
                    || animal.isLeashed() || Pens.all(level).stream().anyMatch(p -> Pens.inside(p, animal))) {
                continue;
            }
            counts.merge(species, 1, Integer::sum);
            where.putIfAbsent(species, animal.blockPosition());
        }
        if (counts.isEmpty()) return;
        synchronized (SEEN) {
            var byDimension = SEEN.computeIfAbsent(level.dimension(), k -> new HashMap<>());
            long now = level.getGameTime();
            for (var entry : counts.entrySet()) {
                byDimension.put(entry.getKey(), new Sighting(where.get(entry.getKey()), now, entry.getValue()));
            }
        }
    }

    /** The nearest recent sighting of any livestock, or null. */
    @Nullable
    public static BlockPos nearest(ServerLevel level, BlockPos from) {
        synchronized (SEEN) {
            var byDimension = SEEN.get(level.dimension());
            if (byDimension == null) return null;
            long now = level.getGameTime();
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            Iterator<Sighting> it = byDimension.values().iterator();
            while (it.hasNext()) {
                Sighting s = it.next();
                if (now - s.seenAt() > FORGET_AFTER) {
                    it.remove();
                    continue;
                }
                double d = s.pos().distSqr(from);
                if (d < bestDist) {
                    bestDist = d;
                    best = s.pos();
                }
            }
            return best;
        }
    }

    /** The herd was not there when a shepherd arrived: forget that sighting. */
    public static void forgetNear(ServerLevel level, BlockPos pos) {
        synchronized (SEEN) {
            var byDimension = SEEN.get(level.dimension());
            if (byDimension == null) return;
            byDimension.values().removeIf(s -> s.pos().distSqr(pos) < 24 * 24);
        }
    }

    public static boolean any(ServerLevel level) {
        return nearest(level, BlockPos.ZERO) != null;
    }

    public static void clear() {
        synchronized (SEEN) {
            SEEN.clear();
        }
    }
}
