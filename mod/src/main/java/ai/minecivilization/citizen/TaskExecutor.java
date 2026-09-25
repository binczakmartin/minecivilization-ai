package ai.minecivilization.citizen;

import java.util.function.Consumer;

import ai.minecivilization.config.ModConfig;
import ai.minecivilization.crafting.CraftPlan;
import ai.minecivilization.crafting.CraftPlanner;
import ai.minecivilization.crafting.Production;
import ai.minecivilization.crafting.VanillaRecipeSource;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.navigation.PlacementSafety;
import ai.minecivilization.navigation.PlacementSupport;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillRegistry;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Deterministic task → skill sequencing. Each task type runs as a small phase
 * program; every phase runs exactly one skill. The LLM never sees or drives
 * these phases — timeouts, retries and recovery live here.
 */
public final class TaskExecutor {
    public enum Status { RUNNING, COMPLETED, FAILED }

    public static final class Outcome {
        public Status status = Status.RUNNING;
        public SkillFailure failure;
        public SkillType activeSkill = SkillType.IDLE;
        public String progressLabel = "";

        static Outcome running(SkillType type, String label) {
            Outcome o = new Outcome();
            o.activeSkill = type;
            o.progressLabel = label;
            return o;
        }

        static Outcome failed(SkillFailure failure) {
            Outcome o = new Outcome();
            o.status = Status.FAILED;
            o.failure = failure;
            return o;
        }
    }

    private static Outcome done() {
        Outcome o = new Outcome();
        o.status = Status.COMPLETED;
        return o;
    }

    private enum Phase {
        SINGLE, CHECK, DONE,
        GATHER_FIND, GATHER_MOVE, GATHER_MINE, GATHER_PICKUP,
        HARVEST_FIND, HARVEST_MOVE, HARVEST_DO, HARVEST_PICKUP, HARVEST_PLANT,
        FARM_FORAGE, FARM_TILL, FARM_SOW, FARM_SOW_MOVE,
        PLANT_FIND, PLANT_MOVE, PLANT_PLACE
    }

    private CitizenPlan.Task task;
    private CitizenTaskParams params;
    private SkillContext ctx;
    private CitizenSkill activeSkill;
    private Phase phase = Phase.CHECK;
    private int findAttempts;
    /** Bounded recoveries for blocks/drops another worker invalidated. */
    private int recoveryFailures;
    private boolean initialized;
    /**
     * Set once a movement in this task reported TARGET_UNREACHABLE: from then
     * on the task moves with TRAVERSE, which is allowed to bridge, tunnel and
     * pillar its way through. Ordinary walking is tried first every time —
     * terrain is only modified when the world genuinely blocks the way.
     */
    private boolean terrainEscalated;

    /** Widest a block search will look before the citizen tries something else. */
    private static final int MAX_SEARCH_RADIUS = 48;
    /** Searches attempted before the goal is abandoned rather than rescanned. */
    private static final int MAX_FIND_ATTEMPTS = 3;
    /** Contention/drop failures allowed without resetting the phase timeout forever. */
    private static final int MAX_RECOVERY_FAILURES = 3;

    /**
     * The resolved recipe tree for a CRAFT task, and how far through it the
     * citizen is. Null until the first CRAFT tick asks how to obtain the item.
     */
    private CraftPlan craftPlan;
    private int craftStepIndex;
    private int craftReplans;
    private CitizenPlan.Task craftSubTask;
    private TaskExecutor craftRunner;
    /**
     * False inside a craft plan's own steps. The plan is already resolved down
     * to raw materials, so its CRAFT steps run the plain skill — otherwise
     * every step would re-plan its own subtree, forever.
     */
    private boolean resolveCraftTrees = true;

    public CitizenPlan.Task currentTask() {
        return task;
    }

    /** True while a task (and therefore exactly one skill) is in control. */
    public boolean hasActiveTask() {
        return task != null;
    }

    /**
     * The skill actually running, following the chain into sub-executors.
     *
     * <p>A CRAFT that resolves a recipe tree hands the real work to a nested
     * executor, so asking the outer one what it is doing answered "nothing" —
     * and a third of all colony time was therefore filed under "starting a
     * craft" when the citizens were in fact out felling trees for it.</p>
     */
    /**
     * Game time the running skill last did something real.
     *
     * <p>Skills reset their own clock whenever they mine a block, place one,
     * or finish a sub-step, because that is what the timeout is measured
     * against. It is also the only honest answer to "is this citizen working?"
     * — mining, crafting and searching are all done standing perfectly still,
     * so position tells you nothing.</p>
     */
    public long skillProgressAt() {
        if (craftRunner != null) {
            long inner = craftRunner.skillProgressAt();
            if (inner != Long.MIN_VALUE) return inner;
        }
        return ctx == null ? Long.MIN_VALUE : ctx.startGameTime;
    }

    public SkillType activeSkillType() {
        if (craftRunner != null) {
            SkillType inner = craftRunner.activeSkillType();
            if (inner != null) return inner;
        }
        return activeSkill != null ? activeSkill.type() : null;
    }

    public void reset(CitizenPlan.Task newTask) {
        cancel();
        this.task = newTask;
        this.params = CitizenTaskParams.fromTask(newTask);
        this.ctx = null;
        this.phase = initialPhase(newTask);
        this.findAttempts = 0;
        this.recoveryFailures = 0;
        this.initialized = false;
        this.terrainEscalated = false;
        this.craftPlan = null;
        this.craftStepIndex = 0;
        this.craftReplans = 0;
        this.craftSubTask = null;
    }

    /**
     * MOVE_TO normally; TRAVERSE once this task has hit impassable terrain.
     *
     * <p>A task may also ask for terrain modification up front by naming
     * {@code traverse} as its target. The rescue ladder uses that on its upper
     * rungs: once ordinary walking has demonstrably failed several times,
     * spending another attempt proving it again is wasted time.</p>
     */
    private SkillType moveSkill() {
        return terrainEscalated || (task != null && "traverse".equals(task.target))
                ? SkillType.TRAVERSE : SkillType.MOVE_TO;
    }

    /**
     * An unreachable target is a question ("is there a way?"), not a verdict.
     * The first such failure per task escalates to terrain modification instead
     * of bubbling up as a task failure.
     */
    private boolean shouldEscalate(SkillFailure failure) {
        return !terrainEscalated && failure != null
                && "TARGET_UNREACHABLE".equals(failure.code);
    }

    private Phase initialPhase(CitizenPlan.Task t) {
        return switch (t.type) {
            case GATHER -> Phase.CHECK;
            case HARVEST -> t.position == null ? Phase.HARVEST_FIND : Phase.HARVEST_MOVE;
            case PLANT -> Phase.PLANT_FIND;
            default -> Phase.SINGLE;
        };
    }

    public Outcome step(ServerLevel level, CitizenEntity self) {
        if (task == null) {
            return Outcome.failed(new SkillFailure("NO_TASK", "executor has no task", false));
        }
        if (ctx == null) {
            ctx = new SkillContext(self, level, self.getNavigator(), params);
            ctx.timeoutTicks = ModConfig.SKILL_TIMEOUT_TICKS.get();
            ctx.startGameTime = level.getGameTime();
        }

        // Work animations are emitted by the skill that owns the mutation;
        // the executor only sequences tasks and must not fake a swing while a
        // citizen is merely searching or walking.
        Outcome outcome = program(level, self);
        // Per-skill timeout, sized to the job (startPhase() resets the window).
        // A flat budget killed whole-tree felling and terrain traversal while
        // they were visibly making progress.
        if (outcome.status == Status.RUNNING && activeSkill != null
                && ctx.timedOut(level.getGameTime(),
                        activeSkill.type().budgetTicks(ctx.timeoutTicks))) {
            SkillFailure f = SkillFailure.timeout(activeSkill.type() + " exceeded "
                    + activeSkill.type().budgetTicks(ctx.timeoutTicks) + " ticks");
            activeSkill.cancel(ctx);
            activeSkill = null;
            return Outcome.failed(f);
        }
        return outcome;
    }

    private Outcome program(ServerLevel level, CitizenEntity self) {
        if (task == null) return done();
        return switch (task.type) {
            // Short on purpose. Resting is how a citizen breaks out of a failure
            // loop, not a coffee break: ten seconds of standing still, times
            // eleven citizens, is most of a minute of colony time thrown away.
            case IDLE -> single(level, SkillType.IDLE, p -> p.extra.put("idleTicks", "40"));
            case REST -> single(level, SkillType.IDLE, p -> p.extra.put("idleTicks", "30"));
            case MOVE -> {
                if (params.position == null) {
                    yield Outcome.failed(new SkillFailure("INVALID_TASK",
                            "MOVE task without position", false));
                }
                Outcome moved = single(level, moveSkill(), p -> {
                });
                if (moved.status == Status.FAILED && shouldEscalate(moved.failure)) {
                    terrainEscalated = true;
                    initialized = false; // let single() start TRAVERSE from scratch
                    yield Outcome.running(SkillType.TRAVERSE, "clearing a path");
                }
                yield moved;
            }
            case DELIVER -> single(level, SkillType.DELIVER_ITEMS, p -> {
            });
            case WITHDRAW -> {
                if (params.resource == null) {
                    yield Outcome.failed(new SkillFailure("INVALID_TASK",
                            "WITHDRAW without resource", false));
                }
                yield single(level, SkillType.WITHDRAW_ITEM, p -> {
                });
            }
            case BUILD -> single(level, SkillType.BUILD_BLUEPRINT, p -> {
                if ("micro".equals(task.target)) p.extra.put("micro", "true");
            });
            case PLACE -> place(level, self);
            case GATHER -> gather(level, self);
            case HARVEST -> harvest(level, self);
            case PLANT -> single(level, SkillType.CULTIVATE, p -> {});
            case CRAFT -> {
                if (params.resource == null) {
                    yield Outcome.failed(new SkillFailure("INVALID_TASK",
                            "CRAFT without resource", false));
                }
                if (!resolveCraftTrees) {
                    yield single(level, SkillType.CRAFT_ITEM, p -> {
                    });
                }
                yield craftFromRecipeTree(level, self);
            }
            case SMELT -> {
                if (params.resource == null) {
                    yield Outcome.failed(new SkillFailure("INVALID_TASK",
                            "SMELT without resource", false));
                }
                yield single(level, SkillType.SMELT_ITEM, p -> {
                });
            }
            case TEND_LIVESTOCK -> single(level, SkillType.TEND_LIVESTOCK, p -> {});
            case PREPARE_PEN -> single(level, SkillType.PREPARE_PEN, p -> {});
            case TAME_WOLF -> single(level, SkillType.TAME_WOLF, p -> {});
            case COLLECT -> single(level, SkillType.PICKUP_ITEM, p -> {});
            case HERD -> single(level, SkillType.HERD_ANIMAL, p -> {
            });
            case BREED -> single(level, SkillType.BREED_ANIMALS, p -> {
            });
            case DECORATE -> single(level, "minecraft:torch".equals(params.resource) ? SkillType.LIGHT_FARM : SkillType.DECORATE, p -> {
            });
            case HUNT -> single(level, SkillType.HUNT, p -> {
            });
            case MINE_SHAFT -> single(level, SkillType.DIG_MINE, p -> {
            });
            // The last rung of the rescue ladder. It carries the destination it
            // should lean toward in `position`, so a citizen digging out of a
            // cave surfaces nearer the colony than it went in.
            case ESCAPE -> single(level, SkillType.DIG_TO_SURFACE, p -> {
            });
            case SIGN -> single(level, SkillType.PLACE_SIGN, p -> {
            });
            // Road work is ordinary block placement with a different reason, so
            // it reuses the placement machinery rather than duplicating it.
            case ROADWORK -> place(level, self);
            case EXPLORE -> single(level, SkillType.EXPLORE, p -> {
            });
            case INSPECT -> done();
        };
    }

    // ------------------------------------------------------------------ single-skill tasks

    private Outcome single(ServerLevel level, SkillType type,
                           Consumer<CitizenTaskParams> setup) {
        if (activeSkill == null) {
            if (initialized) {
                return done(); // skill finished on a previous call
            }
            setup.accept(params);
            activeSkill = SkillRegistry.create(type);
            if (!activeSkill.canStart(ctx)) {
                activeSkill = null;
                initialized = false;
                ctx.failure = new SkillFailure("PRECONDITION_UNMET",
                        type + " cannot start: its required target or resource is unavailable", true);
                return Outcome.failed(ctx.failure);
            }
            startPhase(type);
            initialized = true;
        }
        SkillResult r = activeSkill.tick(ctx);
        return switch (r) {
            case RUNNING -> Outcome.running(type, activeSkill.progressLabel(ctx));
            case COMPLETED -> {
                activeSkill = null;
                yield done();
            }
            case FAILED -> {
                SkillFailure f = ctx.failure != null ? ctx.failure
                        : new SkillFailure("SKILL_FAILED", type + " failed", true);
                activeSkill.cancel(ctx);
                activeSkill = null;
                ctx.failure = null;
                yield Outcome.failed(f);
            }
        };
    }

    private CitizenSkill startPhase(SkillType type) {
        if (activeSkill == null) {
            activeSkill = SkillRegistry.create(type);
        }
        ctx.failure = null;
        ctx.startGameTime = ctx.level.getGameTime();
        activeSkill.start(ctx);
        return activeSkill;
    }

    // ------------------------------------------------------------------ PLACE

    /**
     * PLACE one carried block: pick the nearest reachable open spot around the
     * citizen (replaceable target, survivable support below, within arm's
     * reach), then run the PLACE_BLOCK skill on it. The search is deterministic
     * (fixed scan order, strict distance improvement) so retries are stable.
     */
    private Outcome place(ServerLevel level, CitizenEntity self) {
        if (params.block == null) {
            return Outcome.failed(new SkillFailure("INVALID_TASK",
                    "PLACE without block", false));
        }
        if (params.position == null) {
            int[] spot = findPlacementSpot(level, self, params.block);
            if (spot == null) {
                return Outcome.failed(SkillFailure.notFound(
                        "no open spot near the citizen for " + params.block));
            }
            params.position = spot;
        }
        return single(level, SkillType.PLACE_BLOCK, p -> {
            if (params.resource != null) {
                var item = ai.minecivilization.inventory.CitizenInventory.itemById(params.resource);
                if (item instanceof net.minecraft.world.item.BlockItem) {
                    p.extra.put("item", params.resource);
                }
            }
        });
    }

    private static int[] findPlacementSpot(ServerLevel level, CitizenEntity self,
                                           String blockId) {
        net.minecraft.world.level.block.state.BlockState state =
                ai.minecivilization.construction.ConstructionManager.parseState(level, blockId);
        if (state == null) return null;
        net.minecraft.core.BlockPos base = self.blockPosition();
        int[] best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (dx == 0 && dz == 0) continue; // never inside the citizen's own column
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > 16.0 || d >= bestDist) continue; // stay within PLACE_BLOCK reach
                    net.minecraft.core.BlockPos pos = base.offset(dx, dy, dz);
                    if (pos.getY() < level.getMinBuildHeight()
                            || pos.getY() >= level.getMaxBuildHeight()) continue;
                    if (!level.getBlockState(pos).canBeReplaced()) continue;
                    if (ai.minecivilization.construction.ConstructionManager.get(level)
                            .protectsCell(pos)) continue;
                    if (!PlacementSafety.canOccupy(level, self, pos, false)) continue;
                    if (!PlacementSupport.canPlace(level, state, pos)) continue;
                    bestDist = d;
                    best = new int[]{pos.getX(), pos.getY(), pos.getZ()};
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ CRAFT

    /**
     * CRAFT, resolved all the way down. Asked for an iron pickaxe with nothing
     * in hand, the citizen works out the whole chain — fell trees, craft planks
     * and sticks, mine ore and coal, smelt ingots, assemble — and executes it
     * step by step. Each step is an ordinary task run by a nested executor, so
     * gathering inside a craft gets the same timeouts, retries and terrain
     * escalation as any other gathering.
     */
    private Outcome craftFromRecipeTree(ServerLevel level, CitizenEntity self) {
        int want = params.quantity > 0 ? params.quantity : 1;
        if (self.getInventory().count(params.resource) >= want) {
            return done();
        }

        if (craftPlan == null) {
            craftPlan = CraftPlanner.plan(params.resource, want,
                    VanillaRecipeSource.inventorySnapshot(self.getInventory()),
                    ai.minecivilization.storage.SettlementStock.totals(level, self.blockPosition()),
                    new VanillaRecipeSource(level));
            if (craftPlan == null) {
                return Outcome.failed(new SkillFailure("NO_RECIPE",
                        "no known way to obtain " + want + "x" + params.resource, true));
            }
            craftStepIndex = 0;
            craftSubTask = null;
            if (craftPlan.isSatisfied()) return done();
        }

        if (craftStepIndex >= craftPlan.size()) {
            // The plan ran out without producing the goal: the world drifted
            // (a tree was taken, a drop was lost). Re-plan once from reality.
            return replanCraft(self, want, null);
        }

        CraftPlan.Step step = craftPlan.steps().get(craftStepIndex);
        if (craftSubTask == null) {
            craftSubTask = taskForCraftStep(step, self);
            craftRunner().reset(craftSubTask);
        }

        Outcome outcome = craftRunner.step(level, self);
        String label = "step " + (craftStepIndex + 1) + "/" + craftPlan.size()
                + " " + step.method + " " + step.item;
        switch (outcome.status) {
            case RUNNING -> {
                return Outcome.running(outcome.activeSkill,
                        label + (outcome.progressLabel.isEmpty() ? "" : " — " + outcome.progressLabel));
            }
            case COMPLETED -> {
                craftStepIndex++;
                craftSubTask = null;
                ctx.startGameTime = level.getGameTime(); // progress: refresh the window
                if (craftStepIndex >= craftPlan.size()) {
                    return self.getInventory().count(params.resource) >= want
                            ? done() : replanCraft(self, want, null);
                }
                return Outcome.running(SkillType.IDLE, label + " done");
            }
            default -> {
                craftSubTask = null;
                return replanCraft(self, want, outcome.failure);
            }
        }
    }

    /**
     * One retry with fresh eyes before giving up: a plan is a hypothesis about
     * a world that other citizens are also changing.
     */
    private Outcome replanCraft(CitizenEntity self, int want, SkillFailure cause) {
        if (++craftReplans > 1) {
            return Outcome.failed(cause != null ? cause : SkillFailure.missing(
                    "could not assemble " + want + "x" + params.resource));
        }
        craftPlan = null;
        craftStepIndex = 0;
        craftSubTask = null;
        if (craftRunner != null) craftRunner.cancel();
        return Outcome.running(SkillType.IDLE, "re-planning the craft");
    }

    /**
     * A craft step as an ordinary task. Skill quantities mean "end up holding
     * N", while a plan step means "produce N more", so the carried amount is
     * added back in.
     */
    private CitizenPlan.Task taskForCraftStep(CraftPlan.Step step, CitizenEntity self) {
        // WITHDRAW takes exactly what the plan reserved; the others are skills
        // whose quantity means "end up holding N", so the carried amount is
        // added back in.
        int target = step.method == Production.Method.WITHDRAW
                ? step.count
                : self.getInventory().count(step.item) + step.count;
        CitizenPlan.TaskType type = switch (step.method) {
            case MINE -> CitizenPlan.TaskType.GATHER;
            case SMELT -> CitizenPlan.TaskType.SMELT;
            case CRAFT -> CitizenPlan.TaskType.CRAFT;
            // The settlement already owns these: fetch rather than produce.
            // The storage id is left to the skill, which picks the nearest
            // container that actually holds the item right now.
            case WITHDRAW -> CitizenPlan.TaskType.WITHDRAW;
        };
        return new CitizenPlan.Task(type, step.item, target, null, step.block, null, null);
    }

    /** Nested executor for craft-plan steps; never resolves recipe trees itself. */
    private TaskExecutor craftRunner() {
        if (craftRunner == null) {
            craftRunner = new TaskExecutor();
            craftRunner.resolveCraftTrees = false;
        }
        return craftRunner;
    }

    // ------------------------------------------------------------------ GATHER

    private Outcome gather(ServerLevel level, CitizenEntity self) {
        String resource = params.resource;
        if (resource == null) {
            return Outcome.failed(new SkillFailure("INVALID_TASK",
                    "GATHER without resource", false));
        }
        int qty = params.quantity > 0 ? params.quantity : 1;
        int have = countTowards(self, resource);

        switch (phase) {
            case CHECK -> {
                if (have >= qty) return done();
                findAttempts = 0;
                ctx.params.extra.remove("radius");
                if (params.position != null) {
                    BlockPos known = new BlockPos(params.position[0], params.position[1], params.position[2]);
                    if (usableKnownTarget(level, known, resource)) {
                        if (params.block == null) params.block = sourceBlock(resource);
                        aimAtTreeBase(level);
                        phase = Phase.GATHER_MOVE;
                        return beginPhase(moveSkill());
                    }
                    // The remembered target was collected, protected, or changed
                    // by another worker.  Do not repeatedly walk to a stale
                    // coordinate; fall back to the bounded search once.
                    params.position = null;
                }
                return beginFind();
            }
            case GATHER_FIND -> {
                SkillResult r = activeSkill.tick(ctx);
                if (r == SkillResult.COMPLETED) {
                    activeSkill = null;
                    phase = Phase.GATHER_MOVE;
                    aimAtTreeBase(level);
                    return beginPhase(moveSkill());
                }
                if (r == SkillResult.FAILED) {
                    SkillFailure f = ctx.failure;
                    ctx.failure = null;
                    activeSkill = null;
                    findAttempts++;
                    // The radius widens to its cap after a handful of tries;
                    // past that, every retry rescans the identical cube from
                    // the identical spot. Give up and let the brain choose
                    // something else — that loop used to burn eighteen minutes.
                    int radius = Math.min(MAX_SEARCH_RADIUS, 24 + findAttempts * 8);
                    if (findAttempts > MAX_FIND_ATTEMPTS) {
                        return Outcome.failed(f != null ? f : SkillFailure.notFound(resource));
                    }
                    ctx.params.extra.put("radius", String.valueOf(radius));
                    return beginFind();
                }
                return Outcome.running(SkillType.FIND_BLOCK, activeSkill.progressLabel(ctx));
            }
            case GATHER_MOVE -> {
                SkillResult r = activeSkill.tick(ctx);
                if (r == SkillResult.COMPLETED) {
                    activeSkill = null;
                    phase = Phase.GATHER_MINE;
                    return beginPhase(harvestSkillFor(params.block));
                }
                if (r == SkillResult.FAILED) {
                    SkillFailure f = ctx.failure;
                    ctx.failure = null;
                    activeSkill = null;
                    if (shouldEscalate(f)) {
                        // the resource exists but the way there does not — make one
                        terrainEscalated = true;
                        return beginPhase(SkillType.TRAVERSE);
                    }
                    return Outcome.failed(f);
                }
                return Outcome.running(activeSkill.type(), activeSkill.progressLabel(ctx));
            }
            case GATHER_MINE -> {
                SkillResult r = activeSkill.tick(ctx);
                if (r == SkillResult.COMPLETED) {
                    activeSkill = null;
                    recoveryFailures = 0;
                    phase = Phase.GATHER_PICKUP;
                    return beginPhase(SkillType.PICKUP_ITEM);
                }
                if (r == SkillResult.FAILED) {
                    SkillFailure f = ctx.failure;
                    ctx.failure = null;
                    activeSkill = null;
                    if (f != null && "BLOCK_ALREADY_MINED".equals(f.code)) {
                        if (++recoveryFailures > MAX_RECOVERY_FAILURES) {
                            return Outcome.failed(f);
                        }
                        return beginFind();
                    }
                    return Outcome.failed(f);
                }
                return Outcome.running(SkillType.MINE_BLOCK, activeSkill.progressLabel(ctx));
            }
            case GATHER_PICKUP -> {
                SkillResult r = activeSkill.tick(ctx);
                if (r == SkillResult.COMPLETED || r == SkillResult.FAILED) {
                    SkillFailure pickupFailure = ctx.failure;
                    activeSkill = null;
                    ctx.failure = null;
                    if (countTowards(self, resource) >= qty) {
                        recoveryFailures = 0;
                        phase = Phase.DONE;
                        return done();
                    }
                    if (r == SkillResult.FAILED
                            && ++recoveryFailures > MAX_RECOVERY_FAILURES) {
                        return Outcome.failed(pickupFailure != null ? pickupFailure
                                : SkillFailure.notFound("could not collect " + resource));
                    }
                    ctx.params.extra.remove("radius");
                    return beginFind();
                }
                return Outcome.running(SkillType.PICKUP_ITEM, activeSkill.progressLabel(ctx));
            }
            default -> {
                phase = Phase.CHECK;
                return done();
            }
        }
    }

    /**
     * Walk to the foot of the tree, not to the log we happened to spot.
     *
     * <p>The block search finds whatever log it sees first, which is routinely
     * halfway up a trunk. Walking to <em>that</em> means climbing eighteen
     * blocks of thin air — and a citizen carrying nothing to build with simply
     * reported the tree unreachable and gave up, which is exactly what filled
     * the logs with TARGET_UNREACHABLE. The base is on the ground, so it is
     * reachable on foot, and felling works upward from there anyway.</p>
     */
    private void aimAtTreeBase(ServerLevel level) {
        if (params.position == null) return;
        if (!ai.minecivilization.forestry.TreeSpecies.isTrunk(params.block)) return;

        net.minecraft.core.BlockPos found = new net.minecraft.core.BlockPos(
                params.position[0], params.position[1], params.position[2]);
        net.minecraft.core.BlockPos base = ai.minecivilization.forestry.TreeShape.baseOf(found,
                pos -> level.getBlockState(pos).is(net.minecraft.tags.BlockTags.LOGS));
        params.position = new int[]{base.getX(), base.getY(), base.getZ()};
    }

    /**
     * How a block is taken. Trunks are felled whole — taking one log off a tree
     * leaves a stump under a floating canopy and the forest never regrows —
     * while everything else is mined a block at a time.
     */
    private static SkillType harvestSkillFor(String blockId) {
        return ai.minecivilization.forestry.TreeSpecies.isTrunk(blockId)
                ? SkillType.FELL_TREE : SkillType.MINE_BLOCK;
    }

    /**
     * How much of a request the citizen has already satisfied, substitutes
     * included. Sent for oak and coming home with spruce has to count as
     * progress, or the citizen registers none and sets straight back out.
     */
    private static int countTowards(CitizenEntity self, String resource) {
        int total = 0;
        for (String id : ai.minecivilization.forestry.ResourceFamily.equivalentItems(resource)) {
            total += self.getInventory().count(id);
        }
        return total;
    }

    private boolean usableKnownTarget(ServerLevel level, BlockPos pos, String resource) {
        if (!level.isLoaded(pos) || level.getBlockEntity(pos) != null) return false;
        if (!new ai.minecivilization.navigation.LevelBlockView(level).diggable(pos)) return false;
        String actual = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                .getKey(level.getBlockState(pos).getBlock()).toString();
        return ai.minecivilization.forestry.ResourceFamily.sourceBlocks(resource).contains(actual);
    }

    private static String sourceBlock(String resource) {
        var sources = ai.minecivilization.forestry.ResourceFamily.sourceBlocks(resource);
        return sources.isEmpty() ? resource : sources.getFirst();
    }

    private Outcome beginFind() {
        if (params.block == null && params.resource != null) {
            params.block = sourceBlock(params.resource);
        }
        activeSkill = SkillRegistry.create(SkillType.FIND_BLOCK);
        if (!activeSkill.canStart(ctx)) {
            activeSkill = null;
            return Outcome.failed(SkillFailure.notFound(
                    "no mineable block for " + params.resource));
        }
        ctx.failure = null;
        ctx.startGameTime = ctx.level.getGameTime();
        activeSkill.start(ctx);
        phase = Phase.GATHER_FIND;
        return Outcome.running(SkillType.FIND_BLOCK, "searching");
    }

    private Outcome beginPhase(SkillType type) {
        startPhase(type);
        return Outcome.running(type, "");
    }

    // ------------------------------------------------------------------ HARVEST

    private Outcome harvest(ServerLevel level, CitizenEntity self) {
        if (task.resource != null && params.quantity > 0
                && self.getInventory().count(task.resource) >= params.quantity) {
            return done();
        }
        if (params.block == null) params.block = task.resource == null ? "minecraft:wheat" : task.resource;
        if (activeSkill == null) {
            switch (phase) {
                case HARVEST_FIND -> {
                    params.extra.put("maxAge", "true");
                    return beginFindPhase(SkillType.FIND_BLOCK);
                }
                case HARVEST_MOVE -> {
                    return beginPhase(moveSkill());
                }
                case HARVEST_DO -> {
                    return beginPhase(SkillType.HARVEST_CROP);
                }
                case HARVEST_PICKUP -> {
                    return beginPhase(SkillType.PICKUP_ITEM);
                }
                case HARVEST_PLANT -> {
                    params.block = "minecraft:farmland";
                    params.extra.remove("maxAge");
                    params.position = null;
                    return beginFindPhase(SkillType.FIND_BLOCK);
                }
                case FARM_FORAGE -> {
                    params.resource = "minecraft:wheat_seeds";
                    params.quantity = 6;
                    return beginPhase(SkillType.FORAGE);
                }
                case FARM_TILL -> {
                    params.resource = "minecraft:wheat_seeds";
                    params.quantity = 6;
                    return beginPhase(SkillType.CULTIVATE);
                }
                case FARM_SOW -> {
                    params.extra.remove("maxAge");
                    params.block = "minecraft:farmland";
                    params.extra.put("seed", "minecraft:wheat_seeds");
                    params.position = null;
                    return beginFindPhase(SkillType.FIND_BLOCK);
                }
                case FARM_SOW_MOVE -> {
                    return beginPhase(moveSkill());
                }
                default -> {
                    phase = Phase.HARVEST_FIND;
                    return beginFindPhase(SkillType.FIND_BLOCK);
                }
            }
        }

        SkillResult r = activeSkill.tick(ctx);
        SkillType type = activeSkill.type();
        if (r == SkillResult.RUNNING) {
            return Outcome.running(type, activeSkill.progressLabel(ctx));
        }
        SkillFailure f = ctx.failure;
        ctx.failure = null;
        activeSkill = null;
        if (r == SkillResult.FAILED) {
            if (f != null && ("CROP_GONE".equals(f.code) || "BLOCK_ALREADY_MINED".equals(f.code))) {
                phase = Phase.HARVEST_FIND;
                params.extra.put("maxAge", "true");
                if (params.block == null) {
                    params.block = task.resource == null ? "minecraft:wheat" : task.resource;
                }
                return beginFindPhase(SkillType.FIND_BLOCK);
            }
            if (phase == Phase.HARVEST_PLANT) {
                return done(); // soft: harvest itself succeeded
            }
            if (phase == Phase.HARVEST_MOVE && shouldEscalate(f)) {
                terrainEscalated = true;
                return beginPhase(SkillType.TRAVERSE);
            }
            // No crop anywhere is not a failure, it is a colony without a farm
            // yet. Rather than reporting "no reachable wheat" forever, the
            // citizen goes and makes one: seeds out of the long grass, ground
            // turned over, seeds sown.
            if (phase == Phase.HARVEST_FIND && isNothingFound(f) && "minecraft:wheat".equals(params.block)) {
                phase = Phase.FARM_FORAGE;
                return harvest(level, self);
            }
            if (phase == Phase.FARM_FORAGE) {
                return Outcome.failed(f); // nothing to forage either: genuinely stuck
            }
            // Tilling and sowing used to report success on failure, which is
            // how a colony ended up with a field and nothing growing in it: the
            // task "completed" having planted nothing, over and over. A citizen
            // with no hoe genuinely cannot start a field, and saying so lets the
            // policy go and make one.
            if (phase == Phase.FARM_TILL || phase == Phase.FARM_SOW
                    || phase == Phase.FARM_SOW_MOVE) {
                return Outcome.failed(f != null ? f : SkillFailure.missing(
                        "could not start a field (no hoe, or no ground to turn)"));
            }
            return Outcome.failed(f);
        }
        // COMPLETED — advance the harvest program
        switch (phase) {
            case HARVEST_FIND -> {
                phase = Phase.HARVEST_MOVE;
                return beginPhase(moveSkill());
            }
            case HARVEST_MOVE -> {
                phase = Phase.HARVEST_DO;
                return beginPhase(SkillType.HARVEST_CROP);
            }
            case HARVEST_DO -> {
                phase = Phase.HARVEST_PICKUP;
                return beginPhase(SkillType.PICKUP_ITEM);
            }
            case HARVEST_PICKUP -> {
                int wanted = task.quantity > 0 ? task.quantity : 1;
                boolean enough = task.resource != null
                        && self.getInventory().count(task.resource) >= wanted;
                if (!enough) {
                    // One harvested plant used to complete the whole task. If a
                    // field yields one carrot at a time, that produced a fresh
                    // search after every crop and looked like endless circling.
                    phase = Phase.HARVEST_FIND;
                    params.extra.put("maxAge", "true");
                    params.position = null;
                    return beginFindPhase(SkillType.FIND_BLOCK);
                }
                String seed = ai.minecivilization.farming.Crops.seedFor(params.block);
                if (seed != null && !seed.equals("minecraft:sugar_cane") && !seed.equals("minecraft:sweet_berries")
                        && self.getInventory().containsAtLeast(seed, 1)) {
                    phase = Phase.HARVEST_PLANT;
                    params.extra.put("seed", seed);
                    params.position[1]--;
                    return beginPhase(SkillType.PLANT_CROP);
                }
                phase = Phase.DONE;
                return done();
            }
            case HARVEST_PLANT -> {
                phase = Phase.DONE;
                return done();
            }
            case FARM_FORAGE -> {
                phase = Phase.FARM_TILL;
                return harvest(level, self);
            }
            case FARM_TILL -> {
                return done();
            }
            case FARM_SOW -> {
                // FIND_BLOCK located the new farmland. Walk to it first —
                // planting is a reach action, and the tilled ground is usually
                // several blocks away in the farm district.
                phase = Phase.FARM_SOW_MOVE;
                return beginPhase(moveSkill());
            }
            case FARM_SOW_MOVE -> {
                phase = Phase.HARVEST_PLANT;
                return beginPhase(SkillType.PLANT_CROP);
            }
            default -> {
                return done();
            }
        }
    }

    /** A search that came back empty, as opposed to one that went wrong. */
    private static boolean isNothingFound(SkillFailure failure) {
        return failure != null && "TARGET_NOT_FOUND".equals(failure.code);
    }

    private Outcome beginFindPhase(SkillType type) {
        activeSkill = SkillRegistry.create(type);
        if (!activeSkill.canStart(ctx)) {
            activeSkill = null;
            if (phase == Phase.HARVEST_PLANT) {
                return done(); // soft
            }
            return Outcome.failed(SkillFailure.notFound("nothing to search for"));
        }
        ctx.failure = null;
        ctx.startGameTime = ctx.level.getGameTime();
        activeSkill.start(ctx);
        return Outcome.running(type, "searching");
    }

    // ------------------------------------------------------------------ PLANT

    private Outcome plant(ServerLevel level, CitizenEntity self) {
        if (activeSkill == null) {
            if (phase == Phase.PLANT_FIND) {
                params.extra.remove("maxAge");
                params.block = "minecraft:farmland";
                activeSkill = SkillRegistry.create(SkillType.FIND_BLOCK);
                if (!activeSkill.canStart(ctx)) {
                    activeSkill = null;
                    return Outcome.failed(SkillFailure.notFound("no farmland available"));
                }
                ctx.startGameTime = ctx.level.getGameTime();
                activeSkill.start(ctx);
            } else if (phase == Phase.PLANT_MOVE) {
                return beginPhase(moveSkill());
            } else {
                activeSkill = SkillRegistry.create(SkillType.PLANT_CROP);
                ctx.startGameTime = ctx.level.getGameTime();
                activeSkill.start(ctx);
            }
        }
        SkillResult r = activeSkill.tick(ctx);
        SkillType type = activeSkill.type();
        if (r == SkillResult.RUNNING) {
            return Outcome.running(type, activeSkill.progressLabel(ctx));
        }
        SkillFailure f = ctx.failure;
        ctx.failure = null;
        activeSkill = null;
        if (r == SkillResult.FAILED) {
            return Outcome.failed(f != null ? f
                    : new SkillFailure("PLANT_FAILED", "planting failed", true));
        }
        if (type == SkillType.FIND_BLOCK) {
            phase = Phase.PLANT_MOVE;
            return beginPhase(moveSkill());
        }
        if (phase == Phase.PLANT_MOVE) {
            phase = Phase.PLANT_PLACE;
            return beginPhase(SkillType.PLANT_CROP);
        }
        phase = Phase.DONE;
        return done();
    }

    public void cancel() {
        if (activeSkill != null && ctx != null) {
            activeSkill.cancel(ctx);
        }
        if (craftRunner != null) {
            craftRunner.cancel();
        }
        activeSkill = null;
        task = null;
        initialized = false;
        findAttempts = 0;
        recoveryFailures = 0;
        craftPlan = null;
        craftSubTask = null;
    }
}
