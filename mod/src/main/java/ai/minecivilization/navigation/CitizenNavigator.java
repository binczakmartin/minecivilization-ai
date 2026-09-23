package ai.minecivilization.navigation;

import ai.minecivilization.config.ModConfig;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.impl.Reachability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.LeavesBlock;
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

    private final CitizenEntity citizen;
    private final PathNavigation navigation;

    private BlockPos target;
    private BlockPos requestedTarget;
    private double targetDist;
    private long lastProgressTime;
    private Vec3 lastProgressPos;
    private int repaths;
    private int stalledTicks;
    private boolean failed;
    private boolean pathsBlocked;

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
        if (failed) return false;
        if (pos.equals(requestedTarget) && navigation.isInProgress()) return true;
        boolean changed = requestedTarget == null || !requestedTarget.equals(pos);
        requestedTarget = pos.immutable();
        target = pos.immutable();
        if (changed) {
            targetDist = 0.9;
            repaths = 0;
            stalledTicks = 0;
        }
        BlockState state = citizen.level().getBlockState(pos);
        boolean solid = !state.getCollisionShape(citizen.level(), pos).isEmpty();

        boolean ok;
        if (solid) {
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

    private boolean moveToAdjacent(BlockPos pos, double speed) {
        boolean any = false;
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos stand = pos.relative(dir);
            if (!citizen.level().getBlockState(stand).getCollisionShape(citizen.level(), stand).isEmpty()) continue;
            if (navigation.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, speed)) {
                targetDist = 2.5; // reaching the adjacent stand counts as arrival
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

    private boolean bodyFree(BlockPos p) {
        return citizen.level().getBlockState(p).getCollisionShape(citizen.level(), p).isEmpty();
    }

    private boolean sturdyFloor(BlockPos p) {
        BlockState s = citizen.level().getBlockState(p);
        return !(s.getBlock() instanceof LeavesBlock)
                && s.isFaceSturdy(citizen.level(), p, Direction.UP);
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

        boolean inRange = pos.distanceTo(new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5)) <= targetDist;
        if (inRange) {
            navigation.stop();
            return;
        }

        if (lastProgressPos != null && pos.distanceToSqr(lastProgressPos) < 0.0016) {
            stalledTicks++;
        } else {
            stalledTicks = 0;
            lastProgressPos = pos;
            lastProgressTime = now;
        }

        if (navigation.isDone() || stalledTicks >= 40 || now - lastProgressTime > ModConfig.MOVE_TIMEOUT_TICKS.get()) {
            // recovery: attempt a fresh path before declaring unreachable
            repaths++;
            stalledTicks = 0;
            lastProgressTime = now;
            if (repaths > ModConfig.MAX_REPATHS.get() || !moveTo(target, citizen.navigationSpeed())) {
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

    public void stop() {
        navigation.stop();
        target = null;
        requestedTarget = null;
        repaths = 0;
        stalledTicks = 0;
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
            repaths = 0;
            stalledTicks = 0;
            failed = false;
            return;
        }
        if (random.nextInt(100) != 0) {
            return; // ~once every 100 ticks on average
        }
        BlockPos origin = citizen.blockPosition();
        BlockPos dest = origin.offset(random.nextInt(17) - 8, 0, random.nextInt(17) - 8);
        if (!citizen.level().getBlockState(dest).getCollisionShape(citizen.level(), dest).isEmpty()) {
            return; // never walk into a wall
        }
        moveTo(dest, 0.6D);
    }
}
