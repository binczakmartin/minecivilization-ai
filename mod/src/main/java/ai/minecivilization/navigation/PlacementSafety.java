package ai.minecivilization.navigation;

import ai.minecivilization.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Runtime checks shared by every direct worker placement.
 *
 * <p>{@link PlacementSupport} answers whether a block has a physical support.
 * This class answers the separate question that matters once a citizen is
 * standing in the world: would the new block intersect the worker (or another
 * living entity) now?  Keeping that check beside the transaction boundary
 * prevents a wall, floor or workstation from being written into an occupied
 * AABB and then resolved only by chance by vanilla collision.</p>
 */
public final class PlacementSafety {

    private PlacementSafety() {
    }

    /** A single cell is free for a block placement, excluding the worker when asked. */
    public static boolean canOccupy(ServerLevel level, CitizenEntity citizen,
                                    BlockPos pos, boolean allowSelf) {
        if (level == null || pos == null || !level.isLoaded(pos)) return false;
        AABB cell = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
        if (!allowSelf && citizen != null && citizen.getBoundingBox().intersects(cell)) {
            return false;
        }
        if (!level.noCollision(citizen, cell)) return false;
        return !hasOtherLivingEntity(level, citizen, pos);
    }

    /**
     * Both body cells are free and the cell below is a real standing surface.
     *
     * <p>This asks "could somebody move here?", so it rejects a cell that is
     * already occupied. Asked about the cell the citizen is <em>standing
     * in</em> it would therefore always say no, which is a trap two separate
     * call sites fell into and which broke every route the colony planned.
     * Rather than leave the trap open, that case is answered by
     * {@link #canStandHere}, which is the question the caller actually
     * meant.</p>
     */
    public static boolean canStand(ServerLevel level, CitizenEntity citizen, BlockPos feet) {
        if (citizen != null && feet != null && citizen.blockPosition().equals(feet)) {
            return canStandHere(level, citizen, feet);
        }
        if (!canOccupy(level, citizen, feet, false)
                || !canOccupy(level, citizen, feet.above(), false)) {
            return false;
        }
        BlockPos floorPos = feet.below();
        if (!level.isLoaded(floorPos)) return false;
        BlockState floor = level.getBlockState(floorPos);
        return !(floor.getBlock() instanceof LeavesBlock)
                && floor.isFaceSturdy(level, floorPos, Direction.UP);
    }

    /**
     * True when the citizen is standing somewhere a body legitimately fits.
     *
     * <p>Not the same question as {@link #canStand}, and the difference caused
     * one of the worst bugs in the mod. {@code canStand} asks "could somebody
     * move here?", so it rejects a cell an entity already occupies — and the
     * citizen asking about its <em>own</em> feet always occupies them. Every
     * caller that used it to ask "have I arrived?" therefore got a permanent
     * no, and reported the target unreachable while standing next to it. That
     * single confusion produced thirteen hundred failures in one session.</p>
     *
     * <p>This asks only about the world: two cells of head and body room, and
     * something solid underfoot. Who is standing in them is irrelevant, because
     * the answer is about the citizen that is already there.</p>
     */
    public static boolean canStandHere(ServerLevel level, CitizenEntity citizen, BlockPos feet) {
        if (level == null || feet == null || !level.isLoaded(feet)) return false;
        AABB body = new AABB(feet.getX(), feet.getY(), feet.getZ(),
                feet.getX() + 1.0, feet.getY() + 2.0, feet.getZ() + 1.0);
        // noCollision(entity, box) ignores the entity itself, which is exactly
        // the exemption this question needs.
        if (!level.noCollision(citizen, body)) return false;

        // Floating counts as being somewhere. A swimmer has no floor and never
        // will, so demanding one meant a citizen in water was, by this test,
        // nowhere at all — and every route that crossed water failed at its
        // first wet step.
        if (isWater(level, feet) || isWater(level, feet.above())) return true;

        BlockPos floorPos = feet.below();
        if (!level.isLoaded(floorPos)) return false;
        BlockState floor = level.getBlockState(floorPos);
        return !(floor.getBlock() instanceof LeavesBlock)
                && floor.isFaceSturdy(level, floorPos, Direction.UP);
    }

    /** Water a citizen can be in — never lava. */
    public static boolean isWater(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return false;
        var fluid = level.getFluidState(pos);
        return !fluid.isEmpty() && !fluid.is(net.minecraft.tags.FluidTags.LAVA);
    }

    /**
     * Validate the two body cells before a controlled one-block pillar raise.
     *
     * <p>The cells are checked <em>allowing the climber itself</em>, and that
     * is the whole subtlety. A citizen is about a block and four fifths tall,
     * so its head already occupies the cell it is about to rise into. Asking
     * whether that cell is free of all entities therefore always answered no,
     * and pillaring — every pillar, everywhere in the mod — silently never
     * happened. It is why a branch five blocks up was unreachable, why felling
     * always left the top of the tree standing, and why a route that needed to
     * gain height quietly fell back on walking round.</p>
     *
     * <p>What actually matters is that no <em>block</em> and no <em>other</em>
     * body is in the way; both of those are still checked.</p>
     */
    public static boolean canRaiseOne(ServerLevel level, CitizenEntity citizen, BlockPos feet) {
        if (level == null || citizen == null || feet == null
                || !citizen.blockPosition().equals(feet)) {
            return false;
        }
        BlockPos destination = feet.above();
        return canOccupy(level, citizen, destination, true)
                && canOccupy(level, citizen, destination.above(), true);
    }

    /** True when another living entity currently occupies the target cell. */
    public static boolean hasOtherLivingEntity(ServerLevel level, CitizenEntity citizen,
                                                BlockPos pos) {
        AABB cell = new AABB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, cell.inflate(1.0))) {
            if (other != citizen && other.isAlive() && other.getBoundingBox().intersects(cell)) {
                return true;
            }
        }
        return false;
    }
}
