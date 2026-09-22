package ai.minecivilization.citizen;

import java.util.function.Consumer;

import ai.minecivilization.config.ModConfig;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillRegistry;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
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
        PLANT_FIND, PLANT_PLACE
    }

    private CitizenPlan.Task task;
    private CitizenTaskParams params;
    private SkillContext ctx;
    private CitizenSkill activeSkill;
    private Phase phase = Phase.CHECK;
    private int findAttempts;
    private boolean initialized;

    public CitizenPlan.Task currentTask() {
        return task;
    }

    /** True while a task (and therefore exactly one skill) is in control. */
    public boolean hasActiveTask() {
        return task != null;
    }

    public SkillType activeSkillType() {
        return activeSkill != null ? activeSkill.type() : null;
    }

    public void reset(CitizenPlan.Task newTask) {
        cancel();
        this.task = newTask;
        this.params = CitizenTaskParams.fromTask(newTask);
        this.ctx = null;
        this.phase = initialPhase(newTask);
        this.findAttempts = 0;
        this.initialized = false;
    }

    private Phase initialPhase(CitizenPlan.Task t) {
        return switch (t.type) {
            case GATHER -> Phase.CHECK;
            case HARVEST -> Phase.HARVEST_FIND;
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

        Outcome outcome = program(level, self);

        // uniform per-skill-attempt timeout (startPhase() resets the window)
        if (outcome.status == Status.RUNNING && activeSkill != null
                && ctx.timedOut(level.getGameTime())) {
            SkillFailure f = SkillFailure.timeout(
                    activeSkill.type() + " exceeded " + ctx.timeoutTicks + " ticks");
            activeSkill.cancel(ctx);
            activeSkill = null;
            return Outcome.failed(f);
        }
        return outcome;
    }

    private Outcome program(ServerLevel level, CitizenEntity self) {
        if (task == null) return done();
        return switch (task.type) {
            case IDLE -> single(level, SkillType.IDLE, p -> p.extra.put("idleTicks", "200"));
            case REST -> single(level, SkillType.IDLE, p -> p.extra.put("idleTicks", "120"));
            case MOVE -> {
                if (params.position == null) {
                    yield Outcome.failed(new SkillFailure("INVALID_TASK",
                            "MOVE task without position", false));
                }
                yield single(level, SkillType.MOVE_TO, p -> {
                });
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
            });
            case GATHER -> gather(level, self);
            case HARVEST -> harvest(level, self);
            case PLANT -> plant(level, self);
            case CRAFT -> {
                if (params.resource == null) {
                    yield Outcome.failed(new SkillFailure("INVALID_TASK",
                            "CRAFT without resource", false));
                }
                yield single(level, SkillType.CRAFT_ITEM, p -> {
                });
            }
            case SMELT -> {
                if (params.resource == null) {
                    yield Outcome.failed(new SkillFailure("INVALID_TASK",
                            "SMELT without resource", false));
                }
                yield single(level, SkillType.SMELT_ITEM, p -> {
                });
            }
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
                initialized = true;
                return done();
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

    // ------------------------------------------------------------------ GATHER

    private Outcome gather(ServerLevel level, CitizenEntity self) {
        String resource = params.resource;
        if (resource == null) {
            return Outcome.failed(new SkillFailure("INVALID_TASK",
                    "GATHER without resource", false));
        }
        int qty = params.quantity > 0 ? params.quantity : 1;
        int have = self.getInventory().count(resource);

        switch (phase) {
            case CHECK -> {
                if (have >= qty) return done();
                findAttempts = 0;
                ctx.params.extra.remove("radius");
                return beginFind();
            }
            case GATHER_FIND -> {
                SkillResult r = activeSkill.tick(ctx);
                if (r == SkillResult.COMPLETED) {
                    activeSkill = null;
                    phase = Phase.GATHER_MOVE;
                    return beginPhase(SkillType.MOVE_TO);
                }
                if (r == SkillResult.FAILED) {
                    SkillFailure f = ctx.failure;
                    ctx.failure = null;
                    activeSkill = null;
                    findAttempts++;
                    if (findAttempts > qty * 4 + 8) {
                        return Outcome.failed(f != null ? f : SkillFailure.notFound(resource));
                    }
                    ctx.params.extra.put("radius",
                            String.valueOf(Math.min(48, 24 + findAttempts * 4)));
                    return beginFind();
                }
                return Outcome.running(SkillType.FIND_BLOCK, activeSkill.progressLabel(ctx));
            }
            case GATHER_MOVE -> {
                SkillResult r = activeSkill.tick(ctx);
                if (r == SkillResult.COMPLETED) {
                    activeSkill = null;
                    phase = Phase.GATHER_MINE;
                    return beginPhase(SkillType.MINE_BLOCK);
                }
                if (r == SkillResult.FAILED) {
                    SkillFailure f = ctx.failure;
                    ctx.failure = null;
                    activeSkill = null;
                    return Outcome.failed(f);
                }
                return Outcome.running(SkillType.MOVE_TO, activeSkill.progressLabel(ctx));
            }
            case GATHER_MINE -> {
                SkillResult r = activeSkill.tick(ctx);
                if (r == SkillResult.COMPLETED) {
                    activeSkill = null;
                    phase = Phase.GATHER_PICKUP;
                    return beginPhase(SkillType.PICKUP_ITEM);
                }
                if (r == SkillResult.FAILED) {
                    SkillFailure f = ctx.failure;
                    ctx.failure = null;
                    activeSkill = null;
                    if (f != null && "BLOCK_ALREADY_MINED".equals(f.code)) {
                        return beginFind();
                    }
                    return Outcome.failed(f);
                }
                return Outcome.running(SkillType.MINE_BLOCK, activeSkill.progressLabel(ctx));
            }
            case GATHER_PICKUP -> {
                SkillResult r = activeSkill.tick(ctx);
                if (r == SkillResult.COMPLETED || r == SkillResult.FAILED) {
                    activeSkill = null;
                    ctx.failure = null;
                    if (self.getInventory().count(resource) >= qty) {
                        phase = Phase.DONE;
                        return done();
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

    private Outcome beginFind() {
        if (params.block == null && params.resource != null) {
            var item = ai.minecivilization.inventory.CitizenInventory.itemById(params.resource);
            var block = net.minecraft.world.level.block.Block.byItem(item);
            if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                params.block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block).toString();
            }
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
        if (params.block == null) params.block = "minecraft:wheat";
        if (activeSkill == null) {
            switch (phase) {
                case HARVEST_FIND -> {
                    params.extra.put("maxAge", "true");
                    return beginFindPhase(SkillType.FIND_BLOCK);
                }
                case HARVEST_MOVE -> {
                    return beginPhase(SkillType.MOVE_TO);
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
                params.block = "minecraft:wheat";
                return beginFindPhase(SkillType.FIND_BLOCK);
            }
            if (phase == Phase.HARVEST_PLANT) {
                return done(); // soft: harvest itself succeeded
            }
            return Outcome.failed(f);
        }
        // COMPLETED — advance the harvest program
        switch (phase) {
            case HARVEST_FIND -> {
                phase = Phase.HARVEST_MOVE;
                return beginPhase(SkillType.MOVE_TO);
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
                if (self.getInventory().containsAtLeast("minecraft:wheat_seeds", 1)) {
                    phase = Phase.HARVEST_PLANT;
                    params.extra.put("seed", "minecraft:wheat_seeds");
                    params.block = "minecraft:farmland";
                    params.position = null;
                    return beginFindPhase(SkillType.FIND_BLOCK);
                }
                phase = Phase.DONE;
                return done();
            }
            case HARVEST_PLANT -> {
                phase = Phase.DONE;
                return done();
            }
            default -> {
                return done();
            }
        }
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
                params.block = "minecraft:farmland";
                activeSkill = SkillRegistry.create(SkillType.FIND_BLOCK);
                if (!activeSkill.canStart(ctx)) {
                    activeSkill = null;
                    return Outcome.failed(SkillFailure.notFound("no farmland available"));
                }
                ctx.startGameTime = ctx.level.getGameTime();
                activeSkill.start(ctx);
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
        activeSkill = null;
        task = null;
        initialized = false;
        findAttempts = 0;
    }
}
