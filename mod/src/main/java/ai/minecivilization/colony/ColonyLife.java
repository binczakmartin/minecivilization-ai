package ai.minecivilization.colony;

import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.registry.ModEntities;
import ai.minecivilization.storage.SettlementStock;
import ai.minecivilization.storage.StorageManager;
import ai.minecivilization.storage.StorageNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Births, deaths, and the growth of the settlement.
 *
 * <p>A colony grows when it can plainly afford to: real food comes out of the
 * warehouse, and a bed has to be standing empty for the newcomer. Both costs
 * are deliberate. Food ties growth to farming actually working, and the bed
 * ties it to housing — which needs wool, which needs livestock — so a colony
 * that reaches twenty citizens has genuinely built something rather than
 * ticked a timer.</p>
 *
 * <p>The judgement itself lives in {@link Population}, which is pure and
 * tested; this class only does the world's half: counting, paying and
 * spawning.</p>
 */
public final class ColonyLife {

    /** Ticks between checks — a birth is not an urgent event. */
    private static final int CHECK_INTERVAL = 200;

    private static int tickCounter;

    private ColonyLife() {
    }

    public static void reset() {
        tickCounter = 0;
    }

    /** Called every server tick; does real work occasionally. */
    public static void tick(ServerLevel level) {
        if (level == null) return;
        if (++tickCounter % CHECK_INTERVAL != 0) return;

        int population = CitizenIndex.population();
        if (population <= 0) return;

        ColonyData data = ColonyData.get(level);
        data.found(level.getGameTime());

        // "No beds" before the first survey completes is ignorance, not a fact.
        if (!ColonyCensus.hasSurveyed()) return;

        BlockPos center = ZoneManager.get(level).townCenter(level);
        int food = foodInStore(level, center);
        int beds = ColonyCensus.beds();
        long sinceLastBirth = data.ticksSinceLastBirth(level.getGameTime());

        if (!Population.canReproduce(population, food, beds, sinceLastBirth)) return;
        if (!payForBirth(level, center)) return;   // the food vanished between check and spend

        CitizenEntity child = spawnChild(level, center);
        if (child == null) return;

        data.recordBirth(level.getGameTime());
        ColonyNotifier.birth(level, child.getIdentity().name, CitizenIndex.population());
    }

    /**
     * Why the colony is not growing, for {@code /mciv colony}. Null when it is
     * about to.
     */
    @Nullable
    public static String growthBlocker(ServerLevel level) {
        int population = CitizenIndex.population();
        if (population <= 0) return "no citizens";
        if (!ColonyCensus.hasSurveyed()) return "still surveying the town";
        BlockPos center = ZoneManager.get(level).townCenter(level);
        return Population.blocker(population, foodInStore(level, center), ColonyCensus.beds(),
                ColonyData.get(level).ticksSinceLastBirth(level.getGameTime()));
    }

    /** Edible items held in the settlement's registered containers. */
    public static int foodInStore(ServerLevel level, BlockPos from) {
        int total = 0;
        for (StorageNode node : StorageManager.get(level).active(level)) {
            Container container = SettlementStock.containerAt(level, node);
            if (container == null) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && stack.has(DataComponents.FOOD)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ the cost

    /**
     * Take the birth's food out of the warehouse. All or nothing: a partial
     * charge would quietly drain the stores without producing a citizen.
     */
    private static boolean payForBirth(ServerLevel level, BlockPos center) {
        int owed = Population.FOOD_COST;
        if (foodInStore(level, center) < owed) return false;

        for (StorageNode node : StorageManager.get(level).active(level)) {
            Container container = SettlementStock.containerAt(level, node);
            if (container == null) continue;
            for (int slot = 0; slot < container.getContainerSize() && owed > 0; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !stack.has(DataComponents.FOOD)) continue;
                int take = Math.min(stack.getCount(), owed);
                stack.shrink(take);
                if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
                container.setChanged();
                owed -= take;
            }
            if (owed <= 0) break;
        }
        return owed <= 0;
    }

    @Nullable
    private static CitizenEntity spawnChild(ServerLevel level, BlockPos center) {
        CitizenEntity child = ModEntities.CITIZEN.get().create(level);
        if (child == null) return null;

        BlockPos spot = surfaceNear(level, center);
        child.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        child.finalizeSpawn(level, level.getCurrentDifficultyAt(spot),
                MobSpawnType.BREEDING, null);
        return level.addFreshEntity(child) ? child : null;
    }

    /** Somewhere standable on the surface just off the town centre. */
    private static BlockPos surfaceNear(ServerLevel level, BlockPos center) {
        var random = level.getRandom();
        for (int attempt = 0; attempt < 8; attempt++) {
            int x = center.getX() + random.nextInt(7) - 3;
            int z = center.getZ() + random.nextInt(7) - 3;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()) {
                return candidate;
            }
        }
        return new BlockPos(center.getX(),
                level.getHeight(Heightmap.Types.WORLD_SURFACE, center.getX(), center.getZ()),
                center.getZ());
    }
}
