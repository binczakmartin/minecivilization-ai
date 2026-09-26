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
        // Planting a carried sapling is a minute's work and it is the colony's
        // future wood: ranked with the tools so it is ever reached at all.
        GlobalTaskPool.register("forest", WorkPriority.TOOLS, ColonyWork::forest);
        GlobalTaskPool.register("workstations", WorkPriority.TOOLS,
                ColonyWork::workstations);
        // The kit comes before the job. A shepherd with no wheat cannot lead a
        // sheep, a miner with no wood cannot replace a worn pickaxe or make a
        // torch from its own coal, and a builder with no blocks is a
        // spectator — each of which used to show up as a citizen being handed
        // work it had no means to do, failing, and being handed it again.
        GlobalTaskPool.register("loadout", WorkPriority.TOOLS, ColonyWork::loadout);
        // Runners: whoever asked for something gets it brought, so builders
        // build instead of walking to the warehouse and back.
        GlobalTaskPool.register("courier", WorkPriority.TOOLS, ColonyWork::courier);
        GlobalTaskPool.register("tools", WorkPriority.TOOLS, ColonyWork::tools);
        // Build before supply: a house is always short of something, so asking
        // "what is missing?" first sent every citizen carrying planks off to
        // fetch more instead of putting the ones it had on the wall.
        // Beds before walls: every bed is a citizen the colony may raise.
        GlobalTaskPool.register("furnish", WorkPriority.CONSTRUCTION, ColonyWork::furnish);
        GlobalTaskPool.register("build", WorkPriority.CONSTRUCTION, ColonyWork::build);
        GlobalTaskPool.register("supply-construction", WorkPriority.CONSTRUCTION,
                ColonyWork::supplyConstruction);
        // Roads make every later trip faster: ranked with construction (after
        // it, and two workers at most) so paths actually get made.
        GlobalTaskPool.register("roads", WorkPriority.CONSTRUCTION, ColonyWork::roads);
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
        // The herd is food, wool, beds and growth: the shepherd's main work,
        // not something left for when nothing else is going on.
        GlobalTaskPool.register("livestock", WorkPriority.FOOD, ColonyWork::livestock);
        GlobalTaskPool.register("deliver", WorkPriority.MAINTENANCE, ColonyWork::deliver);
        // Signposting sits with maintenance, not with decoration. A sign costs
        // two planks and is the only way the settlement explains itself — to a
        // player walking through it, and to the citizens who read them back.
        // Left at the bottom of the board it never happened at all: there is
        // always something more urgent than a signpost.
        GlobalTaskPool.register("sign-supply", WorkPriority.MAINTENANCE,
                ColonyWork::signSupply);
        GlobalTaskPool.register("signage", WorkPriority.MAINTENANCE, ColonyWork::signage);
        GlobalTaskPool.register("mine", WorkPriority.RESOURCES, ColonyWork::mine);

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
        // Iron kit only once there is iron: shears sent shepherds searching
        // for deepslate ore ninety times in an afternoon, with no mine dug.
        int iron = citizen.getInventory().count("minecraft:iron_ingot")
                + ai.minecivilization.storage.SettlementStock.count(level, citizen.blockPosition(),
                        "minecraft:iron_ingot");
        // Torches only from coal or charcoal already to hand: making them
        // by smelting logs into charcoal took a quarter of the colony's time.
        int fuel = citizen.getInventory().count("minecraft:coal") + citizen.getInventory().count("minecraft:charcoal")
                + ai.minecivilization.storage.SettlementStock.count(level, citizen.blockPosition(), "minecraft:coal")
                + ai.minecivilization.storage.SettlementStock.count(level, citizen.blockPosition(), "minecraft:charcoal")
                + ai.minecivilization.storage.SettlementStock.count(level, citizen.blockPosition(), "minecraft:torch");
        Loadout.Need need = Loadout.nextMissing(trade, carried, stocked,
                n -> (!Loadout.needsIron(n.itemId()) || iron >= 2)
                        && (!n.itemId().equals("minecraft:torch") || fuel >= 1));
        if (need == null) return null;

        CitizenPlan.Task task = taskFor(level, citizen, need);
        if (task == null) return null;
        // In the stores and somebody to run it over: ask, and keep working.
        if (task.type == CitizenPlan.TaskType.WITHDRAW && courierAvailable(citizen)) {
            MaterialRequests.post(citizen.getUUID(), need.itemId(), task.quantity,
                    "to work as a " + trade.toLowerCase(java.util.Locale.ROOT), level.getGameTime());
            return null;
        }

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

        // A carried workbench goes down wherever it is needed; with enough wood
        // for one more (four planks, i.e. one log) the craft makes its own.
        return inventory.count("minecraft:crafting_table") > 0
                || planks + logs * 4 >= 9
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
            // A full load in the pack belongs in the stores, where the builders
            // (and the "materials on hand" check) can see it — not another trip.
            if (shortfall.carried() >= 32 && !shortfall.isInStore()
                    && StorageManager.nearest(level, citizen.blockPosition()) != null) {
                return new WorkOffer(WorkPriority.CONSTRUCTION, "",
                        "take " + shortName(shortfall.itemId()) + " to the stores for " + project.name,
                        List.of(new CitizenPlan.Task(CitizenPlan.TaskType.DELIVER, null, -1,
                                null, null, null, null)), Integer.MAX_VALUE);
            }
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
            // Build as soon as anything can be placed: blocks go up one
            // material at a time, as they arrive. Waiting for the whole bill of
            // materials first meant no house ever got past 0%.
            if (!ConstructionSupply.anyMaterialOnHand(level, project, citizen)) continue;
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
    private static synchronized WorkOffer collect(ServerLevel level, CitizenEntity citizen) {
        var box = citizen.getBoundingBox().inflate(COLLECT_RADIUS);
        net.minecraft.world.entity.item.ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;

        long now = level.getGameTime();
        UNREACHABLE_DROPS.values().removeIf(until -> until <= now);
        for (var item : level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, box)) {
            if (!item.isAlive() || item.getItem().isEmpty()) continue;
            // Somewhere nobody could get to a minute ago; still nobody can.
            if (UNREACHABLE_DROPS.containsKey(item.blockPosition())) continue;
            // Floating off down a river is not worth chasing.
            if (item.isInWater() || Math.abs(item.getY() - citizen.getY()) > 4) continue;
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

    /**
     * Spots where a dropped item could not be reached, until when to leave them.
     *
     * <p>Items on a ledge, in a hole or across water were offered again the
     * moment the last attempt failed — 260 failed pick-ups in one session,
     * each after six rounds of path-finding.</p>
     */
    private static final java.util.Map<BlockPos, Long> UNREACHABLE_DROPS = new java.util.HashMap<>();

    /** A pick-up at {@code pos} failed for want of a route. */
    public static synchronized void noteUnreachableDrop(BlockPos pos, long now) {
        if (pos != null) UNREACHABLE_DROPS.put(pos.immutable(), now + 2400);
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
        // Wheat is not food until it is bread: three sheaves, one loaf, at a
        // workbench (a carried one will do). The colony grew wheat and starved.
        int wheat = citizen.getInventory().count("minecraft:wheat");
        if (wheat >= 3) {
            return new WorkOffer(WorkPriority.FOOD, "", "bake bread",
                    List.of(new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, "minecraft:bread",
                            citizen.getInventory().count("minecraft:bread") + wheat / 3,
                            null, null, null, null)), Integer.MAX_VALUE);
        }
        // A furnace somewhere in the world is not a furnace this citizen can
        // cook at: one walked to from a hundred blocks out stood "not moving"
        // with a raw porkchop until the deadlock breaker took the job away.
        BlockPos furnace = ai.minecivilization.colony.LandmarkRegistry.get(level).nearest(
                ai.minecivilization.colony.LandmarkKind.FURNACE, citizen.blockPosition());
        if (furnace == null || furnace.distSqr(citizen.blockPosition()) > 48 * 48) {
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
        if (spot == null || ConstructionManager.get(level).inBuildingPlot(spot)) return null;

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
            return WorkOffer.single(WorkPriority.FOOD, key,
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
        // The colony's fields first: every crop sown is on record, and a small
        // look around adds any field this citizen is standing near.
        if (level.getGameTime() % 20 == citizen.getId() % 20) {
            ai.minecivilization.farming.FieldRegistry.scan(level, citizen.blockPosition(), 8, 3);
        }
        BlockPos ripe = ai.minecivilization.farming.FieldRegistry.nearestRipe(level,
                citizen.blockPosition(), 128);
        if (ripe != null) {
            var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                    .getKey(level.getBlockState(ripe).getBlock());
            String produce = key == null ? null : ai.minecivilization.farming.Crops.produceFor(key.toString());
            if (produce != null) {
                return WorkOffer.single(WorkPriority.FOOD,
                        "crop:" + ripe.getX() + "," + ripe.getY() + "," + ripe.getZ(),
                        "bring in a ripe " + shortName(produce),
                        new CitizenPlan.Task(CitizenPlan.TaskType.HARVEST, produce, 1, null,
                                key.toString(), null,
                                new int[]{ripe.getX(), ripe.getY(), ripe.getZ()}));
            }
        }
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

        // Nothing ripe: sow what the citizen is carrying — with a hoe. Grass
        // does not take seed; without one to till it every sowing ended in
        // "no suitable soil".
        var inventory = citizen.getInventory();
        boolean hoe = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof HoeItem) hoe = true;
        }
        if (!hoe) return null;
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
        // Signs are a settled colony's business. On day one the planks are a
        // sword, a pickaxe and a workbench, and a citizen still missing those
        // spent them on acacia signs instead — thirty-three times in a session.
        if (!kitted(citizen) || StorageManager.nearest(level, citizen.blockPosition()) == null) {
            return null;
        }
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
                // "End up holding" the stock level: asking for three while
                // holding three finished instantly, 96 times in five minutes.
                List.of(new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, item,
                        ai.minecivilization.colony.Signage.SIGN_STOCK,
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
        // Shovel paths are upkeep, done between real jobs; a road's grade
        // (clearing, paving, lighting) is construction.
        boolean shovel = "minecraft:dirt_path".equals(job.tasks().get(0).resource);
        return WorkOffer.shared(shovel ? WorkPriority.MAINTENANCE : WorkPriority.CONSTRUCTION,
                "road:" + job.route().id + ":" + (shovel ? "PATH" : job.grade().name()),
                job.grade().label() + " on the " + job.route().displayName() + " road",
                job.tasks(), 2);
    }

    // ------------------------------------------------------------------ beds

    /**
     * Make beds and put them in homes.
     *
     * <p>Growth needs a bed for every citizen and one to spare, and nothing in
     * the colony ever made or placed a single bed: sixteen citizens, zero beds,
     * growth permanently "held back". A bed needs three wool (the herd) and
     * three planks; it goes into a finished home, in the spot its plan keeps
     * for it, or failing that by the town centre.</p>
     */
    @Nullable
    private static WorkOffer furnish(ServerLevel level, CitizenEntity citizen) {
        int population = CitizenIndex.population();
        int beds = ai.minecivilization.colony.ColonyCensus.beds();
        if (beds >= ai.minecivilization.colony.Population.bedsNeededFor(population)) return null;
        var inventory = citizen.getInventory();

        String bed = null;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(net.minecraft.tags.ItemTags.BEDS)) {
                bed = CitizenInventory.idOf(stack);
                break;
            }
        }
        if (bed != null) {
            BlockPos foot = bedSpot(level);
            if (foot == null) return null;
            return WorkOffer.single(WorkPriority.CONSTRUCTION,
                    "bed:" + foot.getX() + "," + foot.getY() + "," + foot.getZ(),
                    "put a bed in a home",
                    new CitizenPlan.Task(CitizenPlan.TaskType.PLACE, bed, 1, null,
                            bed + "[facing=north,part=foot]", null,
                            new int[]{foot.getX(), foot.getY(), foot.getZ()}));
        }
        var stock = ai.minecivilization.storage.SettlementStock.totals(level, citizen.blockPosition());
        int wool = inventory.count("minecraft:white_wool") + stock.getOrDefault("minecraft:white_wool", 0);
        if (wool < 3) {
            // No herd yet: spiders give string, and four string are a wool.
            // The night's fights furnish the first beds.
            int string = inventory.count("minecraft:string") + stock.getOrDefault("minecraft:string", 0);
            if (string < 4) return null;
            return WorkOffer.shared(WorkPriority.CONSTRUCTION, "bed:wool", "spin spider string into wool for a bed",
                    List.of(new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, "minecraft:white_wool",
                            inventory.count("minecraft:white_wool") + 1, null, null, null, null)), 1);
        }
        return WorkOffer.shared(WorkPriority.CONSTRUCTION, "bed:craft", "make a bed from the herd's wool",
                List.of(new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, "minecraft:white_bed", 1,
                        null, null, null, null)), 1);
    }

    /** A free bed spot (the foot; the head is the cell to its north). */
    @Nullable
    private static BlockPos bedSpot(ServerLevel level) {
        for (ConstructionProject project : ConstructionManager.get(level).all()) {
            if (!ai.minecivilization.architecture.ModularHouse.isHome(project.blueprintId)
                    || project.status != ConstructionProject.Status.COMPLETED) continue;
            int[] spot = ai.minecivilization.architecture.ModularHouse.bedSpot(
                    ai.minecivilization.architecture.ModularHouse.stageOf(project.blueprintId));
            if (spot == null) continue;
            BlockPos foot = new BlockPos(project.originX + spot[0], project.originY + spot[1],
                    project.originZ + spot[2]);
            if (bedFits(level, foot)) return foot;
        }
        // No home ready: by the town centre, where the colony gathers.
        BlockPos centre = ai.minecivilization.colony.ZoneManager.get(level).townCenter(level);
        var manager = ConstructionManager.get(level);
        for (int r = 2; r <= 10; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = centre.getX() + dx;
                    int z = centre.getZ() + dz;
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos foot = new BlockPos(x, y, z);
                    if (manager.inBuildingPlot(foot) || manager.inBuildingPlot(foot.north())) continue;
                    if (bedFits(level, foot)) return foot;
                }
            }
        }
        return null;
    }

    private static boolean bedFits(ServerLevel level, BlockPos foot) {
        BlockPos head = foot.north();
        return level.isLoaded(foot) && level.isLoaded(head)
                && level.getBlockState(foot).canBeReplaced() && level.getBlockState(head).canBeReplaced()
                && level.getFluidState(foot).isEmpty() && level.getFluidState(head).isEmpty()
                && level.getBlockState(foot.below()).isFaceSturdy(level, foot.below(), net.minecraft.core.Direction.UP)
                && level.getBlockState(head.below()).isFaceSturdy(level, head.below(), net.minecraft.core.Direction.UP);
    }

    // ------------------------------------------------------------------ couriers

    /** Trades that run materials and tools to the others. */
    public static boolean isCourier(CitizenEntity citizen) {
        String trade = citizen.getIdentity().profession;
        return "CRAFTER".equals(trade) || "LOGISTICS".equals(trade) || "TRADER".equals(trade);
    }

    private static boolean courierAvailable(CitizenEntity asking) {
        for (CitizenEntity other : CitizenIndex.all()) {
            if (other != asking && other.isAlive() && isCourier(other)) return true;
        }
        return false;
    }

    /**
     * Take the oldest request a co-worker posted, get the thing — out of the
     * stores, or made from what the stores hold (planks from logs) — and put
     * it in the co-worker's hands.
     */
    @Nullable
    private static WorkOffer courier(ServerLevel level, CitizenEntity citizen) {
        if (!isCourier(citizen)) return null;
        var stock = ai.minecivilization.storage.SettlementStock.totals(level, citizen.blockPosition());
        var inventory = citizen.getInventory();
        MaterialRequests.Request request = MaterialRequests.claim(citizen.getUUID(),
                level.getGameTime(), r -> {
                    if (!(level.getEntity(r.requester) instanceof CitizenEntity who)
                            || !who.isAlive() || who.distanceToSqr(citizen) > 160 * 160) return false;
                    return obtainable(r.item, stock, inventory) > 0;
                });
        if (request == null) return null;

        List<CitizenPlan.Task> tasks = new ArrayList<>();
        int carried = 0;
        for (String variant : ai.minecivilization.construction.WoodSwap.variants(request.item)) {
            carried += inventory.count(variant);
        }
        int quantity = request.quantity;
        if (carried < quantity) {
            String stored = null;
            int storedCount = 0;
            for (String variant : ai.minecivilization.construction.WoodSwap.variants(request.item)) {
                int here = stock.getOrDefault(variant, 0);
                if (here > storedCount) {
                    storedCount = here;
                    stored = variant;
                }
            }
            if (stored != null) {
                tasks.add(new CitizenPlan.Task(CitizenPlan.TaskType.WITHDRAW, stored,
                        Math.min(quantity - carried, storedCount), null, null, null, null));
            } else {
                // Make it from what the stores hold: the craft plan fetches the logs.
                tasks.add(new CitizenPlan.Task(CitizenPlan.TaskType.CRAFT, request.item,
                        inventory.count(request.item) + (quantity - carried), null, null, null, null));
            }
        }
        tasks.add(new CitizenPlan.Task(CitizenPlan.TaskType.HANDOVER, request.item, quantity,
                request.requester.toString(), null, null, null));
        String name = level.getEntity(request.requester) instanceof CitizenEntity who
                ? who.getIdentity().name : "a co-worker";
        return new WorkOffer(WorkPriority.TOOLS, "",
                "bring " + quantity + " " + shortName(request.item) + " to " + name + " " + request.reason,
                tasks, Integer.MAX_VALUE);
    }

    /** How much of an item (any wood) the courier could lay hands on. */
    private static int obtainable(String item, java.util.Map<String, Integer> stock,
                                  CitizenInventory inventory) {
        int total = 0;
        for (String variant : ai.minecivilization.construction.WoodSwap.variants(item)) {
            total += stock.getOrDefault(variant, 0) + inventory.count(variant);
        }
        if (total == 0 && item.endsWith("_planks")) {
            // Planks are logs a moment later: the courier's own or the stores'.
            for (String species : ai.minecivilization.architecture.Palette.SPECIES) {
                String log = "minecraft:" + species + "_log";
                total += 4 * (stock.getOrDefault(log, 0) + inventory.count(log));
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ mining

    /**
     * A shift on the colony's shared mine.
     *
     * <p>The mine only ever started when the AI service asked a miner already
     * carrying eight torches and two chests to dig it — a combination that
     * never happened, so four hours of play produced no mine at all. Any miner
     * with a pickaxe now takes a shift; torches and chests are put in when it
     * has them, and the stone and ore it cuts go to the stores like any other
     * load.</p>
     */
    @Nullable
    private static WorkOffer mine(ServerLevel level, CitizenEntity citizen) {
        if (!"MINER".equals(citizen.getIdentity().profession)) return null;
        boolean pickaxe = false;
        var inventory = citizen.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() instanceof PickaxeItem) pickaxe = true;
        }
        if (!pickaxe) return null;
        var works = ai.minecivilization.mining.MineWorks.get(level);
        if (works.found(level) == null || works.nextLevel(level) == null) return null;
        return WorkOffer.shared(WorkPriority.RESOURCES, "mine:shaft",
                "take a shift on the colony mine",
                List.of(new CitizenPlan.Task(CitizenPlan.TaskType.MINE_SHAFT, null, 1,
                        null, null, null, null)), 2);
    }

    // ------------------------------------------------------------------ forestry

    /**
     * Plant the saplings a citizen is carrying in the colony's forest.
     *
     * <p>The colony cut every tree within forty-eight blocks and planted
     * nothing back except a sapling on the odd stump: wood ran out and every
     * house stopped. A lumberjack now plants whatever saplings it carries in a
     * managed forest district, spaced so each grows into a tree; anybody else
     * who picked up a handful does the same.</p>
     */
    @Nullable
    private static WorkOffer forest(ServerLevel level, CitizenEntity citizen) {
        var inventory = citizen.getInventory();
        String sapling = null;
        int carried = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(net.minecraft.tags.ItemTags.SAPLINGS)) continue;
            carried += stack.getCount();
            if (sapling == null) sapling = CitizenInventory.idOf(stack);
        }
        if (sapling == null) return null;
        String trade = citizen.getIdentity().profession;
        boolean forester = "LUMBERJACK".equals(trade) || "FORESTER".equals(trade);
        if (!forester && carried < 4) return null;

        List<BlockPos> spots = plantingSpots(level, citizen, Math.min(4, carried));
        if (spots.isEmpty()) return null;
        // One trip, several saplings: the walk to the forest is the expensive
        // part, and planting one per trip left most of them in the pack.
        List<CitizenPlan.Task> tasks = new ArrayList<>();
        for (BlockPos spot : spots) {
            tasks.add(new CitizenPlan.Task(CitizenPlan.TaskType.PLACE, sapling, 1, null, sapling, null,
                    new int[]{spot.getX(), spot.getY(), spot.getZ()}));
        }
        BlockPos first = spots.get(0);
        return new WorkOffer(WorkPriority.TOOLS, "plant:" + first.getX() + "," + first.getZ(),
                "plant " + tasks.size() + " " + shortName(sapling) + "(s) in the colony forest",
                tasks, 1);
    }

    /**
     * Open soil in the nearest forest district, on a three-block grid and with
     * nothing woody within two blocks — room for a trunk and a canopy.
     */
    private static List<BlockPos> plantingSpots(ServerLevel level, CitizenEntity citizen, int count) {
        BlockPos from = citizen.blockPosition();
        ai.minecivilization.colony.Zone forest = null;
        double best = Double.MAX_VALUE;
        for (var zone : ai.minecivilization.colony.ZoneManager.get(level).all()) {
            if (zone.type != ai.minecivilization.colony.ZoneType.FOREST) continue;
            double d = zone.distanceSqrTo(from);
            if (d < best) {
                best = d;
                forest = zone;
            }
        }
        // A forest district a long walk away is not worth the trip: the one
        // this was aimed at sat 114 blocks off and the walk stalled. Plant
        // where the citizen is instead — around the stumps of what it cut,
        // which is how a forest is kept, as long as it is out of the town.
        int minX, maxX, minZ, maxZ;
        if (forest != null && best <= 64.0 * 64.0) {
            minX = forest.minX + 1;
            maxX = forest.maxX - 1;
            minZ = forest.minZ + 1;
            maxZ = forest.maxZ - 1;
        } else {
            BlockPos centre = ai.minecivilization.colony.ZoneManager.get(level).townCenter(level);
            if (centre.distSqr(from) < 32.0 * 32.0) return List.of();
            minX = from.getX() - 12;
            maxX = from.getX() + 12;
            minZ = from.getZ() - 12;
            maxZ = from.getZ() + 12;
        }
        var manager = ConstructionManager.get(level);
        List<BlockPos> found = new ArrayList<>();
        for (int x = minX - Math.floorMod(minX, 3); x <= maxX; x += 3) {
            for (int z = minZ - Math.floorMod(minZ, 3); z <= maxZ; z += 3) {
                BlockPos column = new BlockPos(x, from.getY(), z);
                if (!level.isLoaded(column)) continue;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos spot = new BlockPos(x, y, z);
                if (!level.getBlockState(spot).canBeReplaced()
                        || !level.getFluidState(spot).isEmpty()
                        || !level.getBlockState(spot.below()).is(net.minecraft.tags.BlockTags.DIRT)) continue;
                if (manager.protectsCell(spot) || manager.inBuildingPlot(spot)) continue;
                if (woodyNearby(level, spot)) continue;
                found.add(spot);
            }
        }
        // The nearest spot, then its nearest neighbours: one short walk.
        if (found.isEmpty()) return found;
        found.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(from)));
        BlockPos anchor = found.get(0);
        found.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(anchor)));
        return new ArrayList<>(found.subList(0, Math.min(count, found.size())));
    }

    private static boolean woodyNearby(ServerLevel level, BlockPos spot) {
        for (BlockPos pos : BlockPos.betweenClosed(spot.offset(-2, 0, -2), spot.offset(2, 3, 2))) {
            var state = level.getBlockState(pos);
            if (state.is(net.minecraft.tags.BlockTags.SAPLINGS) || state.is(net.minecraft.tags.BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
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
        // Nobody goes over the horizon without a weapon and a workbench —
        // and nobody goes at all after dark.
        if (!kitted(citizen)) return null;
        if (ai.minecivilization.citizen.NightPolicy.shelterTime(level.getDayTime(),
                level.isThundering())) return null;
        int population = CitizenIndex.population();
        if (population < 3) return null;
        int explorers = Math.max(1, population / 6);
        List<CitizenPlan.Task> tasks = List.of(new CitizenPlan.Task(
                CitizenPlan.TaskType.EXPLORE, null, 1, null, null, null, null));
        return WorkOffer.shared(WorkPriority.EXPLORATION, "explore",
                "survey the land around the colony", tasks, explorers);
    }

    // ------------------------------------------------------------------ helpers

    /** True once the citizen carries every essential of its trade's kit. */
    private static boolean kitted(CitizenEntity citizen) {
        return Loadout.nextMissing(citizen.getIdentity().profession,
                citizen.getInventory().summary(), false) == null;
    }

    /** Unfinished projects in the colony's order (see ConstructionManager#prioritized). */
    private static List<ConstructionProject> sortedActiveProjects(ServerLevel level,
                                                                  CitizenEntity citizen) {
        return ConstructionManager.get(level).prioritized(level, citizen.blockPosition());
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
