package ai.minecivilization.navigation;

import ai.minecivilization.config.ModConfig;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.impl.Reachability;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * Wraps vanilla path navigation. Deterministic recovery:
 * repath on stall, bounded repath attempts, structured
 * TARGET_UNREACHABLE failure. Never calls the LLM because the NPC
 * is stuck behind a tree.
 *
 * <p>Solid targets (a log in a trunk, an ore in a wall) are never walked
 * "into": vanilla ends the path on a nearby node, the arrival test would
 * never pass, and the citizen would stare at the block forever. Instead the
 * navigator lands on a real standable position beside/on/around it
 * ({@link ApproachSearch}).</p>
 */
public final class CitizenNavigator {
    /** Bounded A* attempts per approach fallback — beyond this, declare unreachable. */
    private static final int APPROACH_PATH_ATTEMPTS = 6;
    /** A wall collision gets a few jump/repath attempts before a task timeout. */
    private static final int COLLISION_RECOVERY_TICKS = 6;
    /** Repath sooner than the old 40-tick stall window; workers should feel unstuck. */
    private static final int STALL_REPATH_TICKS = 24;

    private final CitizenEntity citizen;
    private final PathNavigation navigation;

    private BlockPos target;
    private BlockPos requestedTarget;
    private double targetDist;
    private long lastProgressTime;
    private Vec3 lastProgressPos;
    private int repaths;
    private int stalledTicks;
    private int collisionTicks;
    private boolean failed;
    private boolean pathsBlocked;
    /** Whether the current destination is a standing cell, not an interaction block. */
    private boolean requireGround;
    /** Idempotent owner flag for the short MOVE_TO/work-site edge guard. */
    private boolean safeStepRequested;

    public CitizenNavigator(CitizenEntity citizen, PathNavigation navigation) {
        this.citizen = citizen;
        this.navigation = navigation;
    }
    public PathNavigation nav() {
        return navigation;
    }

    /**
     * Begin moving to a block. Solid blocks are approached (stand beside/on
     * them), never entered — see the class doc.
     */
    public boolean moveTo(BlockPos pos, double speed) {
        return moveTo(pos, speed, false);
    }

    /**
     * Move to a real cell the citizen can stand on. Unlike interaction targets,
     * a stand target is not considered arrived while the entity is falling.
     */
    public boolean moveToStand(BlockPos pos, double speed) {
        return moveTo(pos, speed, true);
    }

    /** Choose a stand-cell arrival for open destinations, approach for solid work blocks. */
    public boolean moveToSafe(BlockPos pos, double speed) {
        BlockState state = citizen.level().getBlockState(pos);
        return state.getCollisionShape(citizen.level(), pos).isEmpty()
                ? moveToStand(pos, speed) : moveTo(pos, speed);
    }

    private boolean moveTo(BlockPos pos, double speed, boolean standTarget) {
        if (failed) return false;
        this.requireGround = standTarget;
        if (standTarget && (!bodyFree(pos) || !sturdyFloor(pos.below()))) {
            return false;
        }
        if (pos.equals(requestedTarget) && navigation.isInProgress()) return true;
        boolean changed = requestedTarget == null || !requestedTarget.equals(pos);
        requestedTarget = pos.immutable();
        target = pos.immutable();
        if (changed) {
            targetDist = 0.9;
            repaths = 0;
            stalledTicks = 0;
            collisionTicks = 0;
        }
        BlockState state = citizen.level().getBlockState(pos);
        boolean solid = !state.getCollisionShape(citizen.level(), pos).isEmpty();

        boolean ok;
        if (solid && !standTarget) {
            // stand adjacent (arrival = up to 2.5 blocks from the centre) or,
            // failing that, on the nearest standable spot around it
            ok = moveToAdjacent(pos, speed);
            if (!ok) ok = moveToApproach(pos, speed);
        } else {
            ok = navigation.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed);
        }
        if (!ok) {
            repaths++;
            if (repaths >= ModConfig.MAX_REPATHS.get()) {
                failed = true;
                return false;
            }
            return false;
        }
        lastProgressTime = citizen.level().getGameTime();
        lastProgressPos = citizen.position();
        return true;
    }

    /** Request the scoped crouch used for a normal task move. */
    public void requestSafeStep() {
        if (!safeStepRequested) {
            safeStepRequested = true;
            citizen.setTraversalSneak(true);
        }
    }

    public void releaseSafeStep() {
        if (safeStepRequested) {
            safeStepRequested = false;
            citizen.setTraversalSneak(false);
        }
    }

    private boolean moveToAdjacent(BlockPos pos, double speed) {
        // Already there? Vanilla refuses to build a path to the cell you are
        // standing in, so every candidate came back "unpathable" and the
        // navigator concluded the target was unreachable — while the citizen
        // was touching it. It then handed the job to terrain modification,
        // which is how a third of the colony's day went into tunnelling
        // towards blocks a step away.
        if (alreadyBeside(pos)) {
            target = citizen.blockPosition();
            requireGround = true;
            targetDist = 1.2;
            navigation.stop();
            return true;
        }
        boolean any = false;
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos stand = pos.relative(dir);
            if (!bodyFree(stand) || !sturdyFloor(stand.below())) continue;
            if (navigation.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, speed)) {
                // Keep the arrival point on a real standable cell, not the
                // solid block the worker is trying to interact with.
                target = stand.immutable();
                requireGround = true;
                targetDist = 1.2;
                any = true;
                break;
            }
        }
        return any;
    }

    /**
     * Ring fallback: try standable positions beside/on/around the target in a
     * deterministic order until one is actually pathable. On success the
     * arrival test tracks the *stand*, not the (solid, unreachable) target.
     */
    private boolean moveToApproach(BlockPos pos, double speed) {
        int pathAttempts = 0;
        Predicate<BlockPos> bodyFree = this::bodyFree;
        Predicate<BlockPos> sturdyFloor = this::sturdyFloor;
        for (BlockPos stand : ApproachSearch.candidatesAround(pos)) {
            if (!Reachability.isStandable(stand, bodyFree, sturdyFloor)) continue;
            if (++pathAttempts > APPROACH_PATH_ATTEMPTS) break;
            if (navigation.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, speed)) {
                target = stand;
                requireGround = true;
                // The fallback stand becomes the path's real target. Leaving the
                // solid block in requestedTarget made every stall look like a
                // brand-new destination and reset repaths to zero, defeating
                // the bound exactly when the ring fallback was needed most.
                requestedTarget = stand;
                targetDist = 1.2;
                return true;
            }
        }
        return false;
    }

    /**
     * True when the citizen is already standing somewhere it can work on
     * {@code pos} from: next to it, at most a block above or below, on real
     * ground.
     */
    private boolean alreadyBeside(BlockPos pos) {
        if (!citizen.onGround()) return false;
        BlockPos at = citizen.blockPosition();
        int dx = Math.abs(at.getX() - pos.getX());
        int dy = Math.abs(at.getY() - pos.getY());
        int dz = Math.abs(at.getZ() - pos.getZ());
        if (dx > 1 || dz > 1 || dy > 1) return false;
        return bodyFree(at) && sturdyFloor(at.below());
    }

    private boolean bodyFree(BlockPos p) {
        if (!(citizen.level() instanceof ServerLevel level)) return false;
        LevelBlockView view = new LevelBlockView(level);
        return view.inBounds(p) && view.passable(p) && view.passable(p.above());
    }

    private boolean sturdyFloor(BlockPos p) {
        return citizen.level() instanceof ServerLevel level
                && new LevelBlockView(level).sturdy(p);
    }

    public boolean moveTo(Entity entity, double speed) {
        // Moving animals still use the same bounded stall/repath accounting.
        // Calling this every tick must not reset failure detection every tick.
        BlockPos pos = entity.blockPosition();
        if (requestedTarget != null && requestedTarget.distSqr(pos) <= 4 && navigation.isInProgress()) return !failed;
        return moveTo(pos, speed);
    }

    /** Call once per tick while a MOVE skill is active. */
    public void tick() {
        if (target == null || failed) return;
        long now = citizen.level().getGameTime();
        Vec3 pos = citizen.position();

        if (isArrived()) {
            navigation.stop();
            collisionTicks = 0;
            return;
        }

        boolean madeProgress = lastProgressPos == null
                || pos.distanceToSqr(lastProgressPos) >= 0.0016;
        if (citizen.horizontalCollision && !madeProgress) {
            collisionTicks++;
            if (collisionTicks >= COLLISION_RECOVERY_TICKS) {
                // A worker can be pressed into a wall by a path node or by a
                // neighbour.  A small physical jump is preferable to waiting
                // for the full skill timeout; the repath below still handles
                // genuinely unreachable geometry.
                citizen.getJumpControl().jump();
                if (citizen.onGround()) {
                    citizen.setDeltaMovement(citizen.getDeltaMovement().add(0.0, 0.18, 0.0));
                }
                collisionTicks = 0;
                lastProgressPos = null;
            }
        } else if (madeProgress || !citizen.horizontalCollision) {
            collisionTicks = 0;
        }

        if (!madeProgress) {
            stalledTicks++;
        } else {
            stalledTicks = 0;
            lastProgressPos = pos;
            lastProgressTime = now;
        }

        if (navigation.isDone() || stalledTicks >= STALL_REPATH_TICKS
                || now - lastProgressTime > ModConfig.MOVE_TIMEOUT_TICKS.get()) {
            // recovery: attempt a fresh path before declaring unreachable
            repaths++;
            stalledTicks = 0;
            collisionTicks = 0;
            lastProgressTime = now;
            if (repaths > ModConfig.MAX_REPATHS.get()
                    || !moveTo(target, citizen.navigationSpeed(), requireGround)) {
                if (repaths > ModConfig.MAX_REPATHS.get()) {
                    failed = true;
                    navigation.stop();
                }
            }
        }
    }

    public boolean isInRange() {
        if (target == null) return true;
        Vec3 pos = citizen.position();
        return pos.distanceTo(new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5)) <= targetDist;
    }

    /**
     * True when the current path target has been reached well enough to work
     * from.
     *
     * <p>"Well enough" used to mean standing in the exact block. Vanilla
     * navigation stops when it is near, not on, so a completed path routinely
     * left the citizen one block short and this said it had not arrived. The
     * navigator then repathed six times, gave up, and the walk escalated to
     * digging a tunnel — which is how a third of the colony's entire day came
     * to be spent making ways through terrain it could simply have walked
     * over.</p>
     *
     * <p>Arriving next to the spot is arriving. The cell still has to be a
     * real place to stand, so nothing accepts a citizen hovering over a
     * ravine.</p>
     */
    public boolean isArrived() {
        if (!isInRange()) return false;
        if (!requireGround || target == null) return true;
        if (!citizen.onGround()) return false;

        BlockPos at = citizen.blockPosition();
        if (at.equals(target)) return bodyFree(target) && sturdyFloor(target.below());

        // Next to it, at the same level, and standing on something real.
        int dx = Math.abs(at.getX() - target.getX());
        int dy = Math.abs(at.getY() - target.getY());
        int dz = Math.abs(at.getZ() - target.getZ());
        if (dx > 1 || dz > 1 || dy > 1) return false;
        return bodyFree(at) && sturdyFloor(at.below());
    }

    public boolean isMoving() {
        return navigation.isInProgress();
    }

    public boolean hasFailed() {
        return failed;
    }

    public SkillFailure failure() {
        return SkillFailure.unreachable(
                "Could not reach target after " + ModConfig.MAX_REPATHS.get() + " repaths.");
    }

    /** Stop the current path but keep its target for the owning skill to re-evaluate. */
    public void halt() {
        navigation.stop();
        collisionTicks = 0;
        stalledTicks = 0;
    }

    public void stop() {
        navigation.stop();
        releaseSafeStep();
        target = null;
        requestedTarget = null;
        requireGround = false;
        repaths = 0;
        stalledTicks = 0;
        collisionTicks = 0;
        failed = false;
    }

    public BlockPos currentTarget() {
        return target;
    }

    public Path currentPath() {
        return navigation.getPath();
    }

    // ------------------------------------------------------------------ idle & blocking

    /** True while a task drives movement — idle wandering must stay out of the way. */
    public void setBlockedPaths(boolean blocked) {
        this.pathsBlocked = blocked;
    }

    public boolean hasBlockedPaths() {
        return this.pathsBlocked;
    }

    /**
     * Passive idle wander while no task is running. Cheap, deterministic-ish,
     * never calls the AI service and never leaves the citizen mining air.
     */
    public void tickIdle(net.minecraft.util.RandomSource random) {
        if (pathsBlocked || navigation.isInProgress()) {
            return;
        }
        if (target != null) {
            // previous wander finished (or failed): reset so a new one may start
            target = null;
            requestedTarget = null;
            requireGround = false;
            repaths = 0;
            stalledTicks = 0;
            collisionTicks = 0;
            failed = false;
            releaseSafeStep();
            return;
        }
        if (random.nextInt(100) != 0) {
            return; // ~once every 100 ticks on average
        }
        BlockPos origin = citizen.blockPosition();
        BlockPos dest = origin.offset(random.nextInt(17) - 8, 0, random.nextInt(17) - 8);
        if (!bodyFree(dest) || !sturdyFloor(dest.below())) {
            return; // never walk into a wall or off an unsupported cell
        }
        moveTo(dest, 0.6D);
    }
}
