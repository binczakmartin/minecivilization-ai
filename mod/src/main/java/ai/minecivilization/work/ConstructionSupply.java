package ai.minecivilization.work;

import java.util.LinkedHashMap;
import java.util.Map;

import ai.minecivilization.citizen.CitizenPlan;
import ai.minecivilization.construction.Blueprint;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.forestry.ResourceFamily;
import ai.minecivilization.storage.SettlementStock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Getting building materials to where a building is being built.
 *
 * <p>This was the missing half of construction, and the reason the colony
 * hoarded instead of building. A project that ran out of planks failed with
 * {@code MISSING_MATERIALS}, the citizen picked something else to do, and
 * nothing in the entire system ever concluded "then somebody should go and get
 * planks". Projects sat at eighty per cent indefinitely while the warehouse
 * filled with logs.</p>
 *
 * <p>The answer is to treat a project's shortfall as ordinary work: find what
 * it still needs, and turn that into a withdrawal if the colony has it, a
 * crafting job if the colony can make it, or a gathering job if it cannot. The
 * builder then finds the material waiting for it.</p>
 */
public final class ConstructionSupply {

    /** How much of one material a supplier fetches in a trip. */
    private static final int BATCH = 32;

    /** What a project is short of. */
    public record Shortfall(ConstructionProject project, String itemId, int needed,
                            int inStore, int carried, Map<String, Integer> citizenLogs,
                            int logsInPack) {

        public Shortfall(ConstructionProject project, String itemId, int needed,
                         int inStore, int carried) {
            this(project, itemId, needed, inStore, carried, Map.of(), 0);
        }

        /** True when the warehouse can cover it and it only needs carrying. */
        public boolean isInStore() {
            return inStore > 0;
        }
    }

    private ConstructionSupply() {
    }

    /**
     * Everything a project still has to place, by item.
     *
     * <p>The bill of materials minus what is already in the wall — which is
     * exactly the quantity a colony should be gathering toward.</p>
     */
    public static Map<String, Integer> remainingMaterials(ServerLevel level,
                                                          ConstructionProject project) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Blueprint blueprint = ConstructionManager.blueprint(project.blueprintId);
        if (blueprint == null) return remaining;

        for (Blueprint.BlockEntry entry : blueprint.entries()) {
            String key = project.key(project.originX + entry.x,
                    project.originY + entry.y, project.originZ + entry.z);
            if (project.placed.contains(key)) continue;
            remaining.merge(entry.itemId(), 1, Integer::sum);
        }
        return remaining;
    }

    /**
     * The single material a project most needs, or null when it has everything.
     *
     * <p>One at a time on purpose: a citizen fetching six things at once ends
     * up carrying a little of each and enough of none.</p>
     */
    @Nullable
    public static Shortfall nextShortfall(ServerLevel level, ConstructionProject project,
                                          CitizenEntity citizen) {
        Map<String, Integer> remaining = remainingMaterials(level, project);
        if (remaining.isEmpty()) return null;

        BlockPos from = citizen.blockPosition();
        Map<String, Integer> stock = SettlementStock.totals(level, from);

        Shortfall best = null;
        for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
            String item = entry.getKey();
            int needed = entry.getValue();
            // Any wood does for a wooden block (see WoodSwap): the house goes up
            // in whatever species the colony actually has.
            int carried = 0;
            int inStore = 0;
            String stored = item;
            int storedBest = 0;
            for (String variant : ai.minecivilization.construction.WoodSwap.variants(item)) {
                carried += citizen.getInventory().count(variant);
                int here = stock.getOrDefault(variant, 0);
                inStore += here;
                if (here > storedBest) {
                    storedBest = here;
                    stored = variant;
                }
            }
            if (carried >= needed) continue;          // this citizen can already finish it
            if (storedBest > 0) item = stored;        // fetch the species that is there
            Shortfall candidate = new Shortfall(project, item, needed - carried, inStore, carried,
                    logsCarried(citizen), logsInPack(citizen));
            // Prefer what the warehouse already holds: a trip to a chest beats
            // a trip to a forest, and unblocks the build sooner.
            if (best == null
                    || (candidate.isInStore() && !best.isInStore())
                    || (candidate.isInStore() == best.isInStore()
                        && candidate.needed() > best.needed())) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Whether a builder could place every remaining block of a project with
     * what it carries plus what the stores hold.
     *
     * <p>The build job used to be offered as soon as <em>one</em> shortfall was
     * in store. The build skill places blocks in blueprint order, though, so a
     * marker whose chest was in the warehouse but whose oak post was nowhere
     * sent builders to fail on "need oak_log" — then to fetch one log, then to
     * spend it on something else, then to fail again. That loop ran 190 times
     * in one session.</p>
     */
    /**
     * Whether a builder could place at least one more block now, from what it
     * carries or the stores hold. Building is incremental: walls go up while
     * the chest for the corner is still being made.
     */
    public static boolean anyMaterialOnHand(ServerLevel level, ConstructionProject project,
                                            CitizenEntity citizen) {
        Map<String, Integer> remaining = remainingMaterials(level, project);
        if (remaining.isEmpty()) return true;
        Map<String, Integer> stock = SettlementStock.totals(level, citizen.blockPosition());
        for (String item : remaining.keySet()) {
            for (String variant : ai.minecivilization.construction.WoodSwap.variants(item)) {
                if (citizen.getInventory().count(variant) > 0
                        || stock.getOrDefault(variant, 0) > 0) return true;
            }
        }
        return false;
    }

    public static boolean materialsOnHand(ServerLevel level, ConstructionProject project,
                                          CitizenEntity citizen) {
        Map<String, Integer> remaining = remainingMaterials(level, project);
        if (remaining.isEmpty()) return true;
        Map<String, Integer> stock = SettlementStock.totals(level, citizen.blockPosition());
        for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
            int have = citizen.getInventory().count(entry.getKey())
                    + stock.getOrDefault(entry.getKey(), 0);
            if (have < entry.getValue()) return false;
        }
        return true;
    }

    /**
     * The job that resolves a shortfall.
     *
     * <p>Three ways to obtain a thing, tried in order of cost: take it from the
     * warehouse, make it, or go and find it. All three produce ordinary tasks,
     * so nothing downstream needs to know this is construction supply.</p>
     */
    public static CitizenPlan.Task taskFor(Shortfall shortfall) {
        String item = shortfall.itemId();
        int wanted = Math.min(BATCH, shortfall.needed());

        if (shortfall.isInStore()) {
            return new CitizenPlan.Task(CitizenPlan.TaskType.WITHDRAW, item,
                    Math.min(wanted, shortfall.inStore()), null, null,
                    shortfall.project().id, null);
        }
        // GATHER and CRAFT quantities mean "end up holding N", so the batch is
        // added to what is already carried. Asking a citizen holding 32 stone
        // to "hold 32 stone" completed instantly — 575 times in a few minutes —
        // while the house it was for still had none.
        wanted += shortfall.carried();
        // Crafted wood is made from the logs the citizen has, whatever the
        // blueprint's species: acacia logs make acacia planks, never oak.
        if (isCrafted(item) && ai.minecivilization.construction.WoodSwap.isWooden(item)) {
            item = ai.minecivilization.construction.WoodSwap.withSpecies(item,
                    carriedSpecies(shortfall.citizenLogs(), item));
        }
        // Wooden pieces are only as many as the wood in the pack. Asking for
        // fifty-nine planks with three logs failed as "missing ingredients"
        // over and over and the cabin never got a wall. With too little wood
        // the job is the wood itself; with some, craft what it makes.
        if (isCrafted(item) && ai.minecivilization.construction.WoodSwap.isWooden(item)) {
            String species = ai.minecivilization.construction.WoodSwap.speciesOf(item);
            int wood = shortfall.citizenLogs().getOrDefault(species, 0);
            double perPiece = planksPerPiece(item);
            int affordable = (int) Math.floor(wood / perPiece);
            int batch = wanted - shortfall.carried();
            if (affordable < Math.min(batch, 4)) {
                String log = "minecraft:" + species + "_log";
                int logs = Math.min(16, Math.max(4, (int) Math.ceil(batch * perPiece / 4.0)));
                return new CitizenPlan.Task(CitizenPlan.TaskType.GATHER, log,
                        logs + shortfall.logsInPack(),
                        null, log, shortfall.project().id, null);
            }
            return new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, item,
                    shortfall.carried() + Math.min(batch, affordable),
                    null, null, shortfall.project().id, null);
        }
        // Something the colony makes rather than digs: planks, slabs, stations.
        if (isCrafted(item)) {
            return new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, item, wanted,
                    null, null, shortfall.project().id, null);
        }
        String source = ResourceFamily.sourceBlocks(item).stream().findFirst().orElse(item);
        if (ResourceFamily.isWood(item)) {
            // Any log will do for a wooden post; only non-wood is fetched exactly.
            return new CitizenPlan.Task(CitizenPlan.TaskType.GATHER, item, wanted,
                    null, source, shortfall.project().id, null);
        }
        // "exact": the blueprint places this very block, so neither the search
        // nor the tally may accept a substitute. Counting any log as an oak log
        // had a citizen with acacia in its pack "finish" fetching an oak post
        // instantly, the shortfall still open, 195 times in one session.
        return new CitizenPlan.Task(CitizenPlan.TaskType.GATHER, item, wanted,
                "exact", source, shortfall.project().id, null);
    }

    /** Planks that go into one piece, roughly (sticks counted as half a plank). */
    static double planksPerPiece(String item) {
        if (item.endsWith("_planks")) return 1.0;
        if (item.endsWith("_slab")) return 0.5;
        if (item.endsWith("_stairs")) return 1.5;
        if (item.endsWith("_fence")) return 1.7;
        if (item.endsWith("_door")) return 2.0;
        if (item.endsWith("_trapdoor")) return 3.0;
        if (item.endsWith("_sign")) return 2.2;
        return 2.0;
    }

    private static int logsInPack(CitizenEntity citizen) {
        int n = 0;
        for (String species : ai.minecivilization.architecture.Palette.SPECIES) {
            n += citizen.getInventory().count("minecraft:" + species + "_log");
        }
        return n;
    }

    private static Map<String, Integer> logsCarried(CitizenEntity citizen) {
        Map<String, Integer> logs = new LinkedHashMap<>();
        for (String species : ai.minecivilization.architecture.Palette.SPECIES) {
            // In planks: what the citizen could turn into planks right now.
            int n = 4 * citizen.getInventory().count("minecraft:" + species + "_log")
                    + citizen.getInventory().count("minecraft:" + species + "_planks");
            if (n > 0) logs.put(species, n);
        }
        return logs;
    }

    /** The species the citizen holds most wood of, or the item's own species. */
    private static String carriedSpecies(Map<String, Integer> logs, String item) {
        String best = ai.minecivilization.construction.WoodSwap.speciesOf(item);
        int most = 0;
        for (var entry : logs.entrySet()) {
            if (entry.getValue() > most) {
                most = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /**
     * Items the colony produces at a workbench rather than finding in the ground.
     *
     * <p>Kept as a suffix test rather than a list, because a colony that learns
     * a new wood should not need a code change to build with it.</p>
     */
    public static boolean isCrafted(String itemId) {
        if (itemId == null) return false;
        return itemId.endsWith("_planks") || itemId.endsWith("_slab")
                || itemId.endsWith("_stairs") || itemId.endsWith("_fence")
                || itemId.endsWith("_door") || itemId.endsWith("_sign")
                || itemId.endsWith("_trapdoor") || itemId.endsWith("_pane")
                || itemId.equals("minecraft:glass") || itemId.equals("minecraft:torch")
                || itemId.equals("minecraft:chest") || itemId.equals("minecraft:furnace")
                || itemId.equals("minecraft:crafting_table")
                || itemId.equals("minecraft:stone_bricks")
                || itemId.equals("minecraft:cobblestone_slab")
                || itemId.equals("minecraft:stone")
                || itemId.equals("minecraft:barrel") || itemId.equals("minecraft:ladder");
    }
}
