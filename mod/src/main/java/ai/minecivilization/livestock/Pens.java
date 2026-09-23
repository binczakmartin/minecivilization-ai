package ai.minecivilization.livestock;

import java.util.*;
import ai.minecivilization.colony.*;
import ai.minecivilization.construction.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** Only an intact physical fence ring is a pen; a pasture designation is not enough. */
public final class Pens {
    public static BlockPos gate(ConstructionProject p) { return new BlockPos(p.originX + AnimalPen.gateOffsetX(), p.originY, p.originZ + AnimalPen.gateOffsetZ()); }
    public static BlockPos center(ConstructionProject p) { return new BlockPos(p.originX + 4, p.originY, p.originZ + 4); }
    public static AABB interior(ConstructionProject p) { return new AABB(p.originX + 1, p.originY, p.originZ + 1, p.originX + 8, p.originY + 3, p.originZ + 8); }
    public static boolean inside(ConstructionProject p, Animal a) { return interior(p).contains(a.position()); }
    public static boolean intact(ServerLevel level, ConstructionProject p) {
        for (int x = 0; x < AnimalPen.SIZE; x++) for (int z = 0; z < AnimalPen.SIZE; z++) {
            if (x != 0 && z != 0 && x != 8 && z != 8) continue;
            BlockPos at = new BlockPos(p.originX + x, p.originY, p.originZ + z);
            if (!level.isLoaded(at)) return false;
            var state = level.getBlockState(at);
            if (at.equals(gate(p))) { if (!(state.getBlock() instanceof FenceGateBlock)) return false; }
            else if (!(state.getBlock() instanceof FenceBlock)) return false;
            // Raised terrain beside a one-high fence lets animals jump straight out.
            if (!level.getBlockState(at.above()).isAir()
                    && !level.getBlockState(at.above()).is(Blocks.TORCH)) return false;
        }
        return prepared(level, p);
    }
    public static List<ConstructionProject> all(ServerLevel level) {
        return ConstructionManager.get(level).all().stream().filter(p -> AnimalPen.isPen(p.blueprintId)).toList();
    }
    public static ConstructionProject nearest(ServerLevel level, BlockPos from) {
        return all(level).stream().filter(p -> intact(level, p)).min(Comparator.comparingDouble(p -> center(p).distSqr(from))).orElse(null);
    }
    public static List<Animal> animals(ServerLevel level, ConstructionProject pen) {
        return level.getEntitiesOfClass(Animal.class, interior(pen), a -> a.isAlive() && AnimalHusbandry.isLivestock(HerdRegistry.species(a)));
    }
    /** Adopt unowned farm stock already inside our built pen, never named animals or pets. */
    public static void census(ServerLevel level) {
        for (ConstructionProject p : all(level)) if (intact(level, p)) for (Animal a : animals(level, p)) {
            if (!a.hasCustomName() && !a.isLeashed() && !(a instanceof net.minecraft.world.entity.TamableAnimal)) HerdRegistry.get(level).register(a);
        }
    }
    public static boolean natural(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.COARSE_DIRT)
            || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND) || state.is(Blocks.SNOW_BLOCK);
    }
    public static boolean prepared(ServerLevel level, ConstructionProject pen) {
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(pen.originX - 1, pen.originY, pen.originZ - 1),
                new BlockPos(pen.originX + 9, pen.originY, pen.originZ + 10))) {
            if (!level.isLoaded(p) || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return false;
            var state = level.getBlockState(p);
            if (!state.canBeReplaced() && !(state.getBlock() instanceof FenceBlock) && !(state.getBlock() instanceof FenceGateBlock)) return false;
            var above = level.getBlockState(p.above());
            if (!above.isAir() && !above.is(Blocks.TORCH)) return false;
            if (!level.getFluidState(p).isEmpty()) return false;
        }
        return true;
    }
    public static ConstructionProject ensureProject(ServerLevel level, BlockPos from, String wood) {
        if (!all(level).isEmpty()) return all(level).get(0);
        Zone zone = ZoneManager.get(level).nearest(ZoneType.PASTURE, from);
        if (zone == null) return null;
        // Choose a flat, walkable site without overwriting structures or water.
        for (int x = zone.minX + 1; x + 9 <= zone.maxX; x += 2) for (int z = zone.minZ + 1; z + 10 <= zone.maxZ; z += 2) {
            if (!level.hasChunkAt(new BlockPos(x, from.getY(), z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            boolean safe = true;
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(x - 1, y, z - 1), new BlockPos(x + 9, y, z + 10))) {
                if (!level.isLoaded(p)) { safe = false; break; }
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
                if (Math.abs(surface - y) > 2) { safe = false; break; }
                for (int dy = -2; dy <= 2; dy++) {
                    var at = p.offset(0, dy, 0); var state = level.getBlockState(at);
                    if (!level.getFluidState(at).isEmpty() || level.getBlockEntity(at) != null
                            || (!state.canBeReplaced() && !natural(state))) { safe = false; break; }
                }
                if (!safe) break;
            }
            if (safe) return ConstructionManager.get(level).createProject("Colony livestock pen", wood.equals("oak") ? AnimalPen.ID : AnimalPen.ID + "_" + wood, x, y, z, level.getGameTime());
        }
        return null;
    }
    private record Lease(UUID citizen, long until) {}
    private static final Map<ServerLevel, Map<String, Lease>> LEASES = new WeakHashMap<>();
    public static boolean lock(ServerLevel level, ConstructionProject pen, UUID citizen) {
        var locks = LEASES.computeIfAbsent(level, k -> new HashMap<>());
        Lease old = locks.get(pen.id);
        if (old != null && old.until > level.getGameTime() && !old.citizen.equals(citizen)) return false;
        locks.put(pen.id, new Lease(citizen, level.getGameTime() + 100)); return true;
    }
    public static void unlock(ServerLevel level, ConstructionProject pen, UUID citizen) {
        var locks = LEASES.get(level);
        if (locks != null && locks.containsKey(pen.id) && locks.get(pen.id).citizen.equals(citizen)) locks.remove(pen.id);
    }
    public static void gate(ServerLevel level, ConstructionProject pen, boolean open) {
        BlockPos p = gate(pen); var state = level.getBlockState(p);
        if (state.getBlock() instanceof FenceGateBlock) level.setBlock(p, state.setValue(FenceGateBlock.OPEN, open), 3);
    }
    private Pens() {}
}
