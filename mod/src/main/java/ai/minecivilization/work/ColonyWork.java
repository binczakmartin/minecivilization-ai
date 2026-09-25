package ai.minecivilization.work;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.citizen.CitizenPlan;
import ai.minecivilization.citizen.Loadout;
import ai.minecivilization.colony.Signage;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.roads.RoadWorks;
import ai.minecivilization.storage.DeliveryPolicy;
import ai.minecivilization.storage.StorageManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the colony knows how to want done.
 *
 * <p>Registering the standard work sources in one place is what makes the
 * priority order real rather than aspirational: eat before you build, build
 * before you gather, gather before you pave. Each source is small, answers only
 * for its own concern, and returns null when it has nothing to say.</p>
 *
 * <p>Adding a new kind of colony work means one more {@code register} call
 * here — nothing in the brain, the executor or the planner has to change.</p>
 */
public final class ColonyWork {

    /** Stock levels a citizen keeps on hand before it goes looking for more. */
    private static final int CARRY_TARGET = 48;
    /** Items in the pack before it is worth a trip to a chest. */
    private static final int DELIVER_AT = 96;
    /** How far a citizen will step to pick something up off the ground. */
    private static final double COLLECT_RADIUS = 12.0;

    private static boolean installed;

    private ColonyWork() {
    }

    /** Install the standard sources. Idempotent — safe on every world load. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;

        // Deliberately no "eat" source. Eating is already a local reflex in
        // CitizenEntity.aiStep and CitizenBrain.tick, and adding a board job
        // for it produced a REST task at the highest priority — which does not
        // eat anything. In one session that was 78 of the colony's 86 completed
        // tasks: the single most common activity in the settlement was standing
        // still on purpose.
        GlobalTaskPool.register("workstations", WorkPriority.TOOLS,
                ColonyWork::workstations);
        // The kit comes before the job. A shepherd with no wheat cannot lead a
        // sheep, a miner with no wood cannot replace a worn pickaxe or make a
        // torch from its own coal, and a builder with no blocks is a
        // spectator — each of which used to show up as a citizen being handed
        // work it had no means to do, failing, and being handed it again.
        GlobalTaskPool.register("loadout", WorkPriority.TOOLS, ColonyWork::loadout);
        GlobalTaskPool.register("tools", WorkPriority.TOOLS, ColonyWork::tools);
        GlobalTaskPool.register("supply-construction", WorkPriority.CONSTRUCTION,
                ColonyWork::supplyConstruction);
        GlobalTaskPool.register("build", WorkPriority.CONSTRUCTION, ColonyWork::build);
        // Food, light, crops and livestock are what make the place a colony
        // rather than a work camp. They used to live in LocalWorkPlanner, which
        // is only consulted when the board has nothing at all to offer — and
        // the board always has something. Registering them here is what brings
        // them back to life: they now compete on priority like everything else
        // instead of being unreachable fallbacks.
        GlobalTaskPool.register("cook", WorkPriority.FOOD, ColonyWork::cook);
        GlobalTaskPool.register("harvest", WorkPriority.FOOD, ColonyWork::harvest);
        GlobalTaskPool.register("collect", WorkPriority.MAINTENANCE, ColonyWork::collect);
        GlobalTaskPool.register("storage", WorkPriority.MAINTENANCE, ColonyWork::storage);
        GlobalTaskPool.register("lighting", WorkPriority.MAINTENANCE, ColonyWork::lighting);
        GlobalTaskPool.register("livestock", WorkPriority.MAINTENANCE, ColonyWork::livestock);
        GlobalTaskPool.register("deliver", WorkPriority.MAINTENANCE, ColonyWork::deliver);
        // Signposting sits with maintenance, not with decoration. A sign costs
        // two planks and is the only way the settlement explains itself — to a
        // player walking through it, and to the citizens who read them back.
        // Left at the bottom of the board it never happened at all: there is
        // always something more urgent than a signpost.
        GlobalTaskPool.register("sign-supply", WorkPriority.MAINTENANCE,
                ColonyWork::signSupply);
        GlobalTaskPool.register("signage", WorkPriority.MAINTENANCE, ColonyWork::signage);
        GlobalTaskPool.register("roads", WorkPriority.IMPROVEMENT, ColonyWork::roads);
        GlobalTaskPool.register("explore", WorkPriority.EXPLORATION, ColonyWork::explore);
    }

    public static synchronized void reset() {
        installed = false;
    }

    // ------------------------------------------------------------------ tools

    /**
     * Make the tool the citizen is most obviously missing.
     *
     * <p>Only when the citizen can actually make it. Offering a wooden axe to
     * a colony with no workbench and no planks put eight of sixteen citizens
     * into the same impossible craft, at a priority that outranks building, for
     * the entire session — every one of them failing with
     * {@code TARGET_UNREACHABLE} and immediately trying again. A job nobody can
     * do is worse than no job, because the board will keep offering it.</p>
     *
     * <p>Unclaimed once it is possible: every citizen needs its own axe, and
     * there is no sense in one citizen's toolmaking blocking another's.</p>
     */
    @Nullable
    private static WorkOffer tools(ServerLevel level, CitizenEntity citizen) {
        if (!canCraftTools(level, citizen)) return null;

        CitizenInventory inventory = citizen.getInventory();
        boolean axe = false;
        boolean pickaxe = false;
        boolean shovel = false;
        boolean hoe = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var item = inventory.getItem(slot).getItem();
            if (item instanceof AxeItem) axe = true;
            else if (item instanceof PickaxeItem) pickaxe = true;
            else if (item instanceof ShovelItem) shovel = true;
            else if (item instanceof HoeItem) hoe = true;
        }
        // Order matters: an axe pays for itself first, then a pickaxe opens up
        // stone, and the rest are conveniences.
        String wanted = !axe ? "minecraft:wooden_axe"
                : !pickaxe ? "minecraft:wooden_pickaxe"
                : !shovel ? "minecraft:wooden_shovel"
                : !hoe ? "minecraft:wooden_hoe" : null;
        if (wanted == null) return null;

        return new WorkOffer(WorkPriority.TOOLS, "", "make a " + shortName(wanted),
                List.of(new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, wanted, 1,
                        null, null, null, null)), Integer.MAX_VALUE);
    }

    /**
     * Make and place another workbench when the colony is queueing for one.
     *
     * <p>A crafting table is a two-by-two recipe, so it can be made anywhere —
     * which makes it the one bottleneck a colony can always break by itself.
     * Sixteen citizens sharing one table is not a scheduling problem to be
     * solved with cleverer queueing; it is a colony that needs a second
     * table.</p>
     */
    @Nullable
    private static WorkOffer workstations(ServerLevel level, CitizenEntity citizen) {
        int population = CitizenIndex.population();
        var inventory = citizen.getInventory();

        // A furnace before a second workbench. It is the only way to turn a
        // log into charcoal — and therefore the only way a colony with no coal
        // seam ever gets a torch — and the only way to cook what it hunts.
        // Without one the settlement is cold, dark and eating raw meat.
        if (ai.minecivilization.colony.LandmarkRegistry.get(level).count(
                ai.minecivilization.colony.LandmarkKind.FURNACE) == 0) {
            if (inventory.count("minecraft:furnace") > 0) {
                return WorkOffer.shared(WorkPriority.TOOLS, "furnace:place",
                        "set down the colony's first furnace",
                        List.of(new CitizenPlan.Task(CitizenPlan.TaskType.PLACE,
                                "minecraft:furnace", 1, null, "minecraft:furnace",
                                null, null)), 2);
            }
            if (inventory.count("minecraft:cobblestone") >= 8) {
                return WorkOffer.shared(WorkPriority.TOOLS, "furnace:craft",
                        "make the colony a furnace — no furnace means no charcoal "
                                + "and no cooked food",
                        List.of(new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT,
                                "minecraft:furnace", 1, null, null, null, null)), 2);
            }
        }

        if (!ai.minecivilization.colony.Workstations.isScarce(level,
                ai.minecivilization.colony.LandmarkKind.CRAFTING_TABLE, population)) {
            return null;
        }

        // Already carrying one: put it down where it will be used.
        if (inventory.count("minecraft:crafting_table") > 0) {
            return WorkOffer.single(WorkPriority.TOOLS, "",
                    "set down another workbench — the colony is queueing for one",
                    new CitizenPlan.Task(CitizenPlan.TaskType.PLACE,
                            "minecraft:crafting_table", 1, null,
                            "minecraft:crafting_table", null, null));
        }

        int planks = 0;
        for (String species : ai.minecivilization.architecture.Palette.SPECIES) {
            planks += inventory.count("minecraft:" + species + "_planks");
        }
        if (planks < 4) return null;

        return WorkOffer.single(WorkPriority.TOOLS, "",
                "make another workbench — the colony is queueing for one",
                new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT,
                        "minecraft:crafting_table", 1, null, null, null, null));
    }

    /**
     * Fetch or make the next thing this citizen's trade needs on it.
     *
     * <p>Unclaimed: a kit is personal, and one citizen making its own shears
     * has no business blocking another from making theirs.</p>
     */
    @Nullable
    private static WorkOffer loadout(ServerLevel level, CitizenEntity citizen) {
        var carried = citizen.getInventory().summary();
        String trade = citizen.getIdentity().profession;

        // Essentials always; spares only once the colony has stores to spare
        // them from, so a starting camp does not shop for luxuries.
        boolean stocked = StorageManager.nearest(level, citizen.blockPosition()) != null;
        Loadout.Need need = Loadout.nextMissing(trade, carried, stocked);
        if (need == null) return null;

        CitizenPlan.Task task = taskFor(level, citizen, need);
        if (task == null) return null;

        return new WorkOffer(WorkPriority.TOOLS, "",
                "equip " + need.label() + " for work as a "
                        + trade.toLowerCase(java.util.Locale.ROOT),
                List.of(task), Integer.MAX_VALUE);
    }

    /**
     * Turn a kit shortfall into a job.
     *
     * <p>A withdrawal the stores cannot cover falls back to making or finding
     * the thing, so an empty warehouse never leaves a citizen waiting at a
     * chest for something that is not in it.</p>
     */
    @Nullable
    private static CitizenPlan.Task taskFor(ServerLevel level, CitizenEntity citizen,
                                            Loadout.Need need) {
        int wanted = need.quantity();
        Loadout.Source source = need.source();

        if (source == Loadout.Source.WITHDRAW) {
            int inStore = ai.minecivilization.storage.SettlementStock.count(
                    level, citizen.blockPosition(), need.itemId());
            if (inStore <= 0) {
                source = ConstructionSupply.isCrafted(need.itemId())
                        ? Loadout.Source.CRAFT : Loadout.Source.GATHER;
            } else {
                wanted = Math.min(wanted, inStore);
            }
        }

        return switch (source) {
            case CRAFT -> new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, need.itemId(),
                    wanted, null, null, null, null);
            case WITHDRAW -> new CitizenPlan.Task(CitizenPlan.TaskType.WITHDRAW, need.itemId(),
                    wanted, null, null, null, null);
            case GATHER -> {
                String block = ai.minecivilization.forestry.ResourceFamily
                        .sourceBlocks(need.itemId()).stream().findFirst().orElse(need.itemId());
                yield new CitizenPlan.Task(CitizenPlan.TaskType.GATHER, need.itemId(),
                        wanted, null, block, null, null);
            }
        };
    }

    /**
     * Whether a wooden tool is a realistic thing to ask for right now.
     *
     * <p>Every wooden tool is a three-by-three recipe, so it needs both the
     * material and a workbench the colony actually knows about. Both halves
     * matter: planks with no table, or a table with no planks, are equally
     * impossible and equally likely to loop.</p>
     */
    private static boolean canCraftTools(ServerLevel level, CitizenEntity citizen) {
        var inventory = citizen.getInventory();
        boolean material = inventory.count("minecraft:stick") >= 2;
        int planks = 0;
        int logs = 0;
        for (String species : ai.minecivilization.architecture.Palette.SPECIES) {
            planks += inventory.count("minecraft:" + species + "_planks");
            logs += inventory.count("minecraft:" + species + "_log");
        }
        // Two planks make four sticks, so logs and planks both count toward
        // the handle as well as the head.
        if (planks + logs * 4 < 5 && !(material && planks >= 3)) return false;

        return inventory.count("minecraft:crafting_table") > 0
                || ai.minecivilization.colony.LandmarkRegistry.get(level).nearest(
                        ai.minecivilization.colony.LandmarkKind.CRAFTING_TABLE,
                        citizen.blockPosition()) != null;
    }

    // ------------------------------------------------------------------ construction

    /**
     * Fetch what an unfinished project is short of.
     *
     * <p>Claimed per project and material, so four citizens supplying one
     * building fetch four different things instead of four loads of the
     * same.</p>
     */
    @Nullable
    private static WorkOffer supplyConstruction(ServerLevel level, CitizenEntity citizen) {
        for (ConstructionProject project : sortedActiveProjects(level, citizen)) {
            ConstructionSupply.Shortfall shortfall =
                    ConstructionSupply.nextShortfall(level, project, citizen);
            if (shortfall == null) continue;
            return WorkOffer.single(WorkPriority.CONSTRUCTION,
                    "supply:" + project.id + ":" + shortfall.itemId(),
                    "fetch " + shortName(shortfall.itemId()) + " for " + project.name,
                    ConstructionSupply.taskFor(shortfall));
        }
        return null;
    }

    /**
     * Put blocks on a project that has its materials.
     *
     * <p>Shared by up to three citizens: a building goes up visibly faster with
     * a gang on it, and the cell-level claims inside the build skill stop them
     * fighting over the same block.</p>
     */
    @Nullable
    private static WorkOffer build(ServerLevel level, CitizenEntity citizen) {
        for (ConstructionProject project : sortedActiveProjects(level, citizen)) {
            ConstructionSupply.Shortfall shortfall =
                    ConstructionSupply.nextShortfall(level, project, citizen);
            // Only build what this citizen can actually place right now.
            if (shortfall != null && !shortfall.isInStore()) continue;
            return WorkOffer.shared(WorkPriority.CONSTRUCTION, "build:" + project.id,
                    "build " + project.name,
                    List.of(new CitizenPlan.Task(CitizenPlan.TaskType.BUILD, null, -1,
                            "micro", null, project.id, null)), 3);
        }
        return null;
    }

    // ------------------------------------------------------------------ logistics

    /**
     * Give the colony somewhere to put things.
     *
     * <p>Claimed, and that is the whole point. Every citizen can see that the
     * settlement has no chest, so without a claim every citizen independently
     * decides to make one: twelve people mining twelve trees to build twelve
     * chests, standing in the same three blocks. Two is plenty.</p>
     */
    @Nullable
    private static WorkOffer storage(ServerLevel level, CitizenEntity citizen) {
        if (StorageManager.nearest(level, citizen.blockPosition()) != null) return null;

        if (citizen.getInventory().count("minecraft:chest") > 0) {
            return WorkOffer.shared(WorkPriority.MAINTENANCE, "storage:place",
                    "set down the colony's first chest",
                    List.of(new CitizenPlan.Task(CitizenPlan.TaskType.PLACE,
                            "minecraft:chest", 1, null, "minecraft:chest", null, null)), 2);
        }
        return WorkOffer.shared(WorkPriority.MAINTENANCE, "storage:craft",
                "make the colony a chest — it has nowhere to put anything",
                List.of(new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT,
                        "minecraft:chest", 1, null, null, null, null)), 2);
    }

    /**
     * Pick up what is lying on the ground.
     *
     * <p>Mining and felling scatter drops, and anything not collected within
     * five minutes is gone for good. A colony that leaves half its harvest in
     * the grass is doing twice the work for the same result — and the ground
     * around a worked forest ends up carpeted in items, which is also just
     * untidy.</p>
     *
     * <p>Claimed per item so two citizens do not sprint at the same log.</p>
     */
    @Nullable
    private static WorkOffer collect(ServerLevel level, CitizenEntity citizen) {
        var box = citizen.getBoundingBox().inflate(COLLECT_RADIUS);
        net.minecraft.world.entity.item.ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (var item : level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, box)) {
            if (!item.isAlive() || item.getItem().isEmpty()) continue;
            // Leave a stack that is about to be picked up by its own owner.
            if (item.hasPickUpDelay()) continue;
            double d = citizen.distanceToSqr(item);
            if (d < bestDist) {
                bestDist = d;
                best = item;
            }
        }
        if (best == null) return null;

        BlockPos at = best.blockPosition();
        return WorkOffer.single(WorkPriority.MAINTENANCE,
                "drop:" + best.getId(),
                "pick up " + shortName(CitizenInventory.idOf(best.getItem())),
                new CitizenPlan.Task(CitizenPlan.TaskType.COLLECT, null, 1, null, null, null,
                        new int[]{at.getX(), at.getY(), at.getZ()}));
    }

    /** Empty a full pack into the warehouse rather than carrying it around. */
    @Nullable
    private static WorkOffer deliver(ServerLevel level, CitizenEntity citizen) {
        if (StorageManager.nearest(level, citizen.blockPosition()) == null) return null;

        CitizenInventory inventory = citizen.getInventory();
        int depositable = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.isDamageableItem()) continue;
            depositable += DeliveryPolicy.depositable(CitizenInventory.idOf(stack),
                    stack.getCount());
        }
        if (depositable < DELIVER_AT) return null;

        return new WorkOffer(WorkPriority.MAINTENANCE, "", "take " + depositable
                + " item(s) to the warehouse",
                List.of(new CitizenPlan.Task(CitizenPlan.TaskType.DELIVER, null, -1,
                        null, null, null, null)), Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------ the colony

    /** Raw food and what it becomes, in the order a colony meets it. */
    private static final java.util.Map<String, String> COOKABLE = java.util.Map.of(
            "minecraft:beef", "minecraft:cooked_beef",
            "minecraft:porkchop", "minecraft:cooked_porkchop",
            "minecraft:mutton", "minecraft:cooked_mutton",
            "minecraft:chicken", "minecraft:cooked_chicken",
            "minecraft:rabbit", "minecraft:cooked_rabbit",
            "minecraft:cod", "minecraft:cooked_cod",
            "minecraft:salmon", "minecraft:cooked_salmon",
            "minecraft:potato", "minecraft:baked_potato");

    /**
     * Put the raw meat in the fire.
     *
     * <p>Cooking roughly doubles what a carcass is worth and removes the
     * chance of it making the eater ill — raw chicken in particular is food a
     * citizen should never have to choose. It needs a furnace, which is why
     * the colony now builds one before it builds a second workbench.</p>
     */
    @Nullable
    private static WorkOffer cook(ServerLevel level, CitizenEntity citizen) {
        if (ai.minecivilization.colony.LandmarkRegistry.get(level).count(
                ai.minecivilization.colony.LandmarkKind.FURNACE) == 0) {
            return null;
        }
        var inventory = citizen.getInventory();
        for (var entry : COOKABLE.entrySet()) {
            int raw = inventory.count(entry.getKey());
            if (raw <= 0) continue;
            return new WorkOffer(WorkPriority.FOOD, "",
                    "cook " + shortName(entry.getKey()),
                    List.of(new CitizenPlan.Task(CitizenPlan.TaskType.SMELT,
                            entry.getValue(), Math.min(8, raw), null, null, null, null)),
                    Integer.MAX_VALUE);
        }
        return null;
    }

    /**
     * Light a dark corner of the settlement.
     *
     * <p>The single cheapest thing a colony can do for itself: a lit square
     * does not spawn what kills the people standing in it. Claimed per spot,
     * so a dark room does not attract every idle citizen at once.</p>
     */
    @Nullable
    private static WorkOffer lighting(ServerLevel level, CitizenEntity citizen) {
        if (citizen.getInventory().count("minecraft:torch") < 1) return null;

        BlockPos dark = ai.minecivilization.colony.ColonyLighting.find(
                level, citizen.blockPosition());
        if (dark == null) return null;
        BlockPos spot = ai.minecivilization.farming.FarmLighting.torchSpot(level, dark);
        if (spot == null) return null;

        return WorkOffer.single(WorkPriority.MAINTENANCE,
                "light:" + spot.getX() + "," + spot.getY() + "," + spot.getZ(),
                "light a dark corner of the colony",
                new CitizenPlan.Task(CitizenPlan.TaskType.PLACE, "minecraft:torch", 1,
                        null, "minecraft:torch", null,
                        new int[]{spot.getX(), spot.getY(), spot.getZ()}));
    }

    /**
     * Shear, milk, breed and pen the colony's animals.
     *
     * <p>The shepherd's job, and the only route to wool — which is beds, which
     * is population growth. Claimed per job so two shepherds do not chase the
     * same sheep.</p>
     */
    @Nullable
    private static WorkOffer livestock(ServerLevel level, CitizenEntity citizen) {
        if (!"SHEPHERD".equals(citizen.getIdentity().profession)) return null;

        for (CitizenPlan.Task task : ai.minecivilization.livestock.LivestockWork
                .candidates(level, citizen)) {
            String key = "livestock:" + task.type + ":" + task.resource + ":" + task.target;
            return WorkOffer.single(WorkPriority.MAINTENANCE, key,
                    "tend the colony's animals (" + task.type + ")", task);
        }
        return null;
    }

    /**
     * Bring in a ripe crop, or put seed in the ground.
     *
     * <p>Only crops the citizen has actually seen: inventing a harvest for a
     * field that does not exist is how a colony spends an afternoon proving it
     * has no carrots.</p>
     */
    @Nullable
    private static WorkOffer harvest(ServerLevel level, CitizenEntity citizen) {
        for (var entry : citizen.knownResources().entrySet()) {
            String produce = ai.minecivilization.farming.Crops.produceFor(entry.getKey());
            if (produce == null) continue;
            BlockPos pos = entry.getValue();
            if (!level.isLoaded(pos) || pos.distSqr(citizen.blockPosition()) > 64 * 64) continue;

            var state = level.getBlockState(pos);
            var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                    .getKey(state.getBlock());
            if (key == null || !entry.getKey().equals(key.toString())) continue;
            if (!ai.minecivilization.farming.Crops.ripe(state, level, pos)) continue;

            return WorkOffer.single(WorkPriority.FOOD,
                    "crop:" + pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                    "bring in a ripe " + shortName(produce),
                    new CitizenPlan.Task(CitizenPlan.TaskType.HARVEST, produce, 1, null,
                            entry.getKey(), null,
                            new int[]{pos.getX(), pos.getY(), pos.getZ()}));
        }

        // Nothing ripe: sow what the citizen is carrying.
        var inventory = citizen.getInventory();
        for (String seed : ai.minecivilization.farming.Crops.SEEDS.keySet()) {
            if (inventory.count(seed) <= 0) continue;
            return new WorkOffer(WorkPriority.FOOD, "", "plant " + shortName(seed),
                    List.of(new CitizenPlan.Task(CitizenPlan.TaskType.PLANT, seed,
                            Math.min(8, inventory.count(seed)), null, null, null, null)),
                    Integer.MAX_VALUE);
        }
        return null;
    }

    // ------------------------------------------------------------------ improvement

    /**
     * Keep a few signs in the pack.
     *
     * <p>Signposting only happens if somebody is carrying a sign, and nothing
     * in the colony was making any. A citizen with planks and no signs makes
     * some — which costs almost nothing and is what turns the whole signage
     * system from dormant into visible.</p>
     */
    @Nullable
    private static WorkOffer signSupply(ServerLevel level, CitizenEntity citizen) {
        var inventory = citizen.getInventory();
        int signs = 0;
        String wood = null;
        for (String species : ai.minecivilization.architecture.Palette.SPECIES) {
            signs += inventory.count("minecraft:" + species + "_sign");
            if (wood == null && inventory.count("minecraft:" + species + "_planks") >= 6) {
                wood = species;
            }
        }
        if (signs >= ai.minecivilization.colony.Signage.SIGN_STOCK) return null;
        // Only bother when there is actually something to label, otherwise the
        // colony spends its planks on signs it will never plant. The scan is
        // the expensive half, so carrying nothing at all skips it: a citizen
        // with no signs always wants some.
        if (signs > 0 && ai.minecivilization.colony.Signage.next(level, citizen) == null) {
            return null;
        }
        if (wood == null) return null;

        String item = "minecraft:" + wood + "_sign";
        return new WorkOffer(WorkPriority.MAINTENANCE, "", "make signs to label the colony",
                List.of(new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, item, 3,
                        null, null, null, null)), Integer.MAX_VALUE);
    }

    /** Label something nobody has labelled. */
    @Nullable
    private static WorkOffer signage(ServerLevel level, CitizenEntity citizen) {
        CitizenPlan.Task task = Signage.next(level, citizen);
        if (task == null) return null;
        int[] at = task.position;
        return WorkOffer.single(WorkPriority.MAINTENANCE,
                "sign:" + at[0] + "," + at[1] + "," + at[2],
                "put up a sign: " + task.resource, task);
    }

    /** Improve the stretch of road the colony walks most. */
    @Nullable
    private static WorkOffer roads(ServerLevel level, CitizenEntity citizen) {
        RoadWorks.Job job = RoadWorks.next(level, citizen);
        if (job == null) return null;
        return WorkOffer.shared(WorkPriority.IMPROVEMENT,
                "road:" + job.route().id + ":" + job.grade().name(),
                job.grade().label() + " on the " + job.route().displayName() + " road",
                job.tasks(), 2);
    }

    // ------------------------------------------------------------------ exploration

    /**
     * Send somebody out to look around.
     *
     * <p>Rationed hard: exploration is the lowest-value thing a busy colony can
     * do, and a settlement with everyone over the horizon builds nothing. At
     * most one explorer per six citizens, and never the last one at home.</p>
     */
    @Nullable
    private static WorkOffer explore(ServerLevel level, CitizenEntity citizen) {
        int population = CitizenIndex.population();
        if (population < 3) return null;
        int explorers = Math.max(1, population / 6);
        List<CitizenPlan.Task> tasks = List.of(new CitizenPlan.Task(
                CitizenPlan.TaskType.EXPLORE, null, 1, null, null, null, null));
        return WorkOffer.shared(WorkPriority.EXPLORATION, "explore",
                "survey the land around the colony", tasks, explorers);
    }

    // ------------------------------------------------------------------ helpers

    /** Unfinished projects, nearest first — a builder should not cross the town. */
    private static List<ConstructionProject> sortedActiveProjects(ServerLevel level,
                                                                  CitizenEntity citizen) {
        List<ConstructionProject> active = new ArrayList<>();
        for (ConstructionProject project : ConstructionManager.get(level).all()) {
            if (project.status == ConstructionProject.Status.COMPLETED
                    || project.status == ConstructionProject.Status.FAILED) continue;
            active.add(project);
        }
        var from = citizen.blockPosition();
        active.sort(java.util.Comparator.comparingDouble(project ->
                new net.minecraft.core.BlockPos(project.originX, project.originY,
                        project.originZ).distSqr(from)));
        return active;
    }

    /** {@code minecraft:oak_planks} → {@code oak planks}, for readable reasons. */
    public static String shortName(String itemId) {
        if (itemId == null) return "something";
        return itemId.substring(itemId.indexOf(':') + 1).replace('_', ' ');
    }

    /** How much of a resource a citizen tries to keep on hand. */
    public static int carryTarget() {
        return CARRY_TARGET;
    }
}
