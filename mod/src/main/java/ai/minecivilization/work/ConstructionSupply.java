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
                            int inStore, int carried) {

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
            int carried = citizen.getInventory().count(item);
            if (carried >= needed) continue;          // this citizen can already finish it

            int inStore = stock.getOrDefault(item, 0);
            Shortfall candidate = new Shortfall(project, item, needed - carried, inStore, carried);
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
        // Something the colony makes rather than digs: planks, slabs, stations.
        if (isCrafted(item)) {
            return new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, item, wanted,
                    null, null, shortfall.project().id, null);
        }
        String source = ResourceFamily.sourceBlocks(item).stream().findFirst().orElse(item);
        return new CitizenPlan.Task(CitizenPlan.TaskType.GATHER, item, wanted,
                null, source, shortfall.project().id, null);
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
