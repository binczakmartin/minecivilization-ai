package ai.minecivilization.citizen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.WorkAnimation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Getting out of water the navigator cannot get out of.
 *
 * <p>A waterfall in a cave caught half a colony: the current pushed each
 * citizen back into the pool, vanilla mob swimming is too weak to fight it,
 * and the navigator re-planned the same hopeless route forever. A player in
 * that pool would look around for the nearest dry ledge — usually round the
 * side of the fall, not through it — and swim hard for it; failing that, cut a
 * step into the rock and climb out. This does exactly that, one small physical
 * move per tick, and hands the citizen back to its work once it stands on dry
 * ground.</p>
 */
final class WaterEscape {

    /** Stuck in water this long (ticks) before the escape takes over. */
    static final int STUCK_TICKS = 100;
    /** How far the search for dry ground looks. */
    private static final int SEARCH = 10;
    /** Longest an escape may take before it is re-planned. */
    private static final int MAX_TICKS = 240;

    private BlockPos anchor;
    private long anchorAt;
    private List<BlockPos> route;
    private int routeIndex;
    private long startedAt;
    private long nextDigAt;
    /** When the swimmer started on the current route cell. */
    private long cellSince;
    /** How long to swim at a cell before helping the body across. */
    private static final int ASSIST_TICKS = 15;

    boolean active() {
        return route != null;
    }

    void reset() {
        anchor = null;
        route = null;
    }

    /** @return true while the escape is driving the citizen this tick */
    boolean tick(ServerLevel level, CitizenEntity self, long now) {
        if (!inWater(level, self) && route == null) {
            anchor = null;
            return false;
        }
        if (route != null) return follow(level, self, now);

        BlockPos here = self.blockPosition();
        if (anchor == null || anchor.distSqr(here) > 4) {
            anchor = here;
            anchorAt = now;
            return false;
        }
        if (now - anchorAt < STUCK_TICKS) return false;

        route = findDryGround(level, self, here);
        routeIndex = 0;
        startedAt = now;
        cellSince = now;
        if (route == null) {
            // No dry ground within reach: make some.
            route = List.of();
        }
        self.getNavigator().stop();
        return true;
    }

    private boolean follow(ServerLevel level, CitizenEntity self, long now) {
        if (now - startedAt > MAX_TICKS) {
            route = null;
            anchor = null;          // start measuring again from here
            return false;
        }
        if (!inWater(level, self) && self.onGround()) {
            route = null;
            anchor = null;
            return false;           // out: back to work
        }
        if (route.isEmpty()) return cutStep(level, self, now);

        BlockPos next = route.get(routeIndex);
        Vec3 goal = new Vec3(next.getX() + 0.5, next.getY() + 0.1, next.getZ() + 0.5);
        Vec3 to = goal.subtract(self.position());
        if (self.blockPosition().equals(next)
                || (to.horizontalDistanceSqr() < 0.3 * 0.3 && Math.abs(to.y) < 0.8)) {
            cellSince = now;
            if (++routeIndex >= route.size()) {
                route = null;
                anchor = null;
                return false;
            }
            return true;
        }
        // The current pins a swimmer against the wall and it cannot lift itself
        // over the lip of the ledge: the diagnostics showed zero sideways speed
        // for minutes on end. Every cell of the route is next to the last one,
        // so when the body has not made it across in a moment, help it over —
        // the same one-block assist that pulls a citizen out of a wall.
        if (now - cellSince > ASSIST_TICKS && bodyFits(level, self, next)) {
            self.setPos(goal.x, next.getY(), goal.z);
            self.setDeltaMovement(Vec3.ZERO);
            self.fallDistance = 0f;
            cellSince = now;
            return true;
        }
        // Swim hard: a steady push toward the next cell, stronger than the
        // current, and upward when the next cell is higher — the swim a player
        // makes by holding forward and jump.
        // Steer the body's own move control at the escape cell too: left alone
        // it kept walking towards the old destination and fought the swim.
        self.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
        Vec3 flat = new Vec3(to.x, 0, to.z);
        Vec3 push = flat.lengthSqr() > 1.0e-4 ? flat.normalize().scale(0.14) : Vec3.ZERO;
        double up = to.y > 0.2 ? 0.2 : (self.isInWater() ? 0.04 : 0.0);
        Vec3 motion = self.getDeltaMovement();
        // Pressed against the ledge it is climbing onto: hop out, the way a
        // swimming player clears the lip of a pool.
        if (self.horizontalCollision && to.y > -0.5) up = 0.42;
        self.setDeltaMovement(push.x, Math.max(motion.y, up), push.z);
        if (to.y > 0.5 && self.onGround()) self.getJumpControl().jump();
        self.getLookControl().setLookAt(goal.x, goal.y + 1.0, goal.z);
        return true;
    }

    /**
     * Breadth-first over open cells (water included) for the nearest place
     * with dry feet, a free head and firm ground. Returns the cells to swim
     * through, or null when there is none within {@link #SEARCH} blocks.
     */
    private static List<BlockPos> findDryGround(ServerLevel level, CitizenEntity self, BlockPos from) {
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(from);
        parent.put(from, from);
        int budget = 4000;
        while (!open.isEmpty() && budget-- > 0) {
            BlockPos cell = open.poll();
            if (!cell.equals(from) && dryStand(level, cell)) {
                List<BlockPos> path = new ArrayList<>();
                for (BlockPos at = cell; !at.equals(from); at = parent.get(at)) path.add(0, at);
                return path;
            }
            for (Direction dir : Direction.values()) {
                BlockPos next = cell.relative(dir);
                if (parent.containsKey(next) || !level.isLoaded(next)) continue;
                if (Math.abs(next.getX() - from.getX()) > SEARCH
                        || Math.abs(next.getZ() - from.getZ()) > SEARCH
                        || Math.abs(next.getY() - from.getY()) > SEARCH) continue;
                if (!open(level, next) || !open(level, next.above())) continue;
                parent.put(next, cell);
                open.add(next);
            }
        }
        return null;
    }

    /** The citizen's body fits standing in this cell, with nobody else in it. */
    private static boolean bodyFits(ServerLevel level, CitizenEntity self, BlockPos feet) {
        var box = self.getDimensions(self.getPose()).makeBoundingBox(
                feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        return level.noCollision(self, box);
    }

    /**
     * In the water, or bobbing on it. A swimmer at the surface of a shallow
     * current is out of the water every few ticks by the vanilla test, which
     * kept resetting the "stuck" clock so the escape never started.
     */
    private static boolean inWater(ServerLevel level, CitizenEntity self) {
        if (self.isInWater()) return true;
        if (self.onGround() && level.getFluidState(self.blockPosition()).isEmpty()) return false;
        BlockPos feet = self.blockPosition();
        return !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty();
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static boolean dryStand(ServerLevel level, BlockPos feet) {
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) {
            return false;
        }
        BlockState floor = level.getBlockState(feet.below());
        return !floor.is(BlockTags.LEAVES) && level.getFluidState(feet.below()).isEmpty()
                && floor.isFaceSturdy(level, feet.below(), Direction.UP);
    }

    /**
     * No dry ground in sight: dig one step up into the rock beside the pool,
     * on the side the water is not coming from, and climb into it.
     */
    private boolean cutStep(ServerLevel level, CitizenEntity self, long now) {
        if (now < nextDigAt) return true;
        nextDigAt = now + 10;
        BlockPos feet = self.blockPosition();
        var manager = ai.minecivilization.construction.ConstructionManager.get(level);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos step = feet.relative(side).above();        // one up, beside
            BlockPos head = step.above();
            if (!level.getFluidState(step).isEmpty() || !level.getFluidState(head).isEmpty()) continue;
            if (level.getBlockEntity(step) != null || level.getBlockEntity(head) != null) continue;
            if (manager.protectsCell(step) || manager.protectsCell(head)) continue;
            BlockState floor = level.getBlockState(step.below());
            if (!floor.isFaceSturdy(level, step.below(), Direction.UP)) continue;
            // Clear the body space, then climb in.
            for (BlockPos cell : new BlockPos[]{step, head}) {
                BlockState state = level.getBlockState(cell);
                if (state.getCollisionShape(level, cell).isEmpty()) continue;
                if (state.getDestroySpeed(level, cell) < 0) return true;
                level.destroyBlock(cell, true, self);
                self.animateAction(WorkAnimation.MINE, cell);
                return true;                                      // one block per step
            }
            route = List.of(step);
            routeIndex = 0;
            cellSince = now;
            return true;
        }
        return true;
    }
}
