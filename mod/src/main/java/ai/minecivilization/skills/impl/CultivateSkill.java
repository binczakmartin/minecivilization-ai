package ai.minecivilization.skills.impl;

import ai.minecivilization.farming.Crops;
import ai.minecivilization.skills.*;
import ai.minecivilization.navigation.SpiralScan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded field work: find a viable plot, approach, prepare and sow real inventory. */
public final class CultivateSkill implements CitizenSkill {
    private final SpiralScan.Cursor scan = new SpiralScan.Cursor();
    private final java.util.Set<BlockPos> rejected = new java.util.HashSet<>();
    private BlockPos origin, target;
    private String seed;
    private int planted;
    private boolean filling;
    public SkillType type() { return SkillType.CULTIVATE; }
    public boolean canStart(SkillContext c) { return true; }
    public void start(SkillContext c) {
        origin = c.citizen.blockPosition();
        seed = c.params.resource == null ? "minecraft:wheat_seeds" : c.params.resource;
    }
    private int hoe(SkillContext c) {
        var inv = c.citizen.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
            if (inv.getItem(i).getItem() instanceof net.minecraft.world.item.HoeItem) return i;
        return -1;
    }
    private boolean viable(SkillContext c, BlockPos ground) {
        if (!c.level.isLoaded(ground) || rejected.contains(ground)) return false;
        BlockPos support = ground.below();
        if (!c.level.getBlockState(support).isFaceSturdy(c.level, support, Direction.UP)) {
            return false;
        }
        BlockPos pos = ground.above();
        if (!c.level.getBlockState(pos).isAir()) return false;
        BlockState crop = Crops.state(seed);
        if (crop == null) return false;
        if (crop.canSurvive(c.level, pos)) return true;
        var soil = c.level.getBlockState(ground);
        return Crops.needsFarmland(seed) && hoe(c) >= 0
            && (soil.is(Blocks.DIRT) || soil.is(Blocks.GRASS_BLOCK) || soil.is(Blocks.DIRT_PATH))
            && c.level.getRawBrightness(pos, 0) >= 8;
    }
    public SkillResult tick(SkillContext c) {
        if (planted >= Math.min(16, Math.max(1, c.params.quantity))) return SkillResult.COMPLETED;
        if (!c.citizen.getInventory().containsAtLeast(seed, 1)) {
            if (planted > 0) return SkillResult.COMPLETED;
            c.fail(SkillFailure.missing("no " + seed + " to sow")); return SkillResult.FAILED;
        }
        if (target == null) {
            int[] offset = new int[3];
            for (int i = 0; i < 1500 && scan.next(24, offset); i++) {
                BlockPos p = origin.offset(offset[0], offset[1], offset[2]);
                if (c.level.isLoaded(p) && !rejected.contains(p)
                        && c.citizen.getInventory().containsAtLeast("minecraft:bucket", 1)
                        && !c.citizen.getInventory().containsAtLeast("minecraft:water_bucket", 1)
                        && c.level.getBlockState(p).is(Blocks.WATER) && c.level.getFluidState(p).isSource()) {
                    target = p; filling = true; break;
                }
                if (viable(c, p)) { target = p; break; }
            }
            if (target == null) {
                if (!scan.exhausted()) return SkillResult.RUNNING;
                if (planted > 0) return SkillResult.COMPLETED;
                c.fail(SkillFailure.notFound("no suitable soil for " + seed)); return SkillResult.FAILED;
            }
        }
        if (!filling && !viable(c, target)) { target = null; return SkillResult.RUNNING; }
        var arrival = SkillNavigation.approach(c, target, 16, "cultivate.walk");
        if (arrival == SkillResult.FAILED) {
            rejected.add(target); target = null; filling = false; c.failure = null; c.navigator.stop();
            return SkillResult.RUNNING;
        }
        if (arrival == SkillResult.RUNNING) return arrival;
        c.navigator.stop();
        if (filling) {
            var inv = c.citizen.getInventory();
            if (inv.containsAtLeast("minecraft:bucket", 1) && c.level.getBlockState(target).is(Blocks.WATER)
                    && c.level.getFluidState(target).isSource() && c.level.setBlock(target, Blocks.AIR.defaultBlockState(), 3)) {
                inv.extract("minecraft:bucket", 1);
                int left = inv.insert(new ItemStack(Items.WATER_BUCKET));
                if (left > 0) c.citizen.spawnAtLocation(new ItemStack(Items.WATER_BUCKET, left));
            }
            rejected.add(target); target = null; filling = false;
            return SkillResult.RUNNING;
        }
        if (Crops.needsFarmland(seed) && !c.level.getBlockState(target).is(Blocks.FARMLAND)) {
            int slot = hoe(c);
            if (slot < 0) { target = null; return SkillResult.RUNNING; }
            if (!c.level.setBlock(target, Blocks.FARMLAND.defaultBlockState(), 3)) {
                rejected.add(target); target = null; return SkillResult.RUNNING;
            }
            ItemStack tool = c.citizen.getInventory().getItem(slot);
            tool.setDamageValue(tool.getDamageValue() + 1);
            if (tool.getDamageValue() >= tool.getMaxDamage()) c.citizen.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        irrigate(c, target);
        c.params.position = new int[]{target.getX(), target.getY(), target.getZ()};
        c.params.extra.put("seed", seed);
        var plant = new PlantCropSkill();
        plant.start(c);
        var result = plant.tick(c);
        rejected.add(target); target = null;
        if (result == SkillResult.COMPLETED) { planted++; c.startGameTime = c.level.getGameTime(); }
        else c.failure = null;
        return SkillResult.RUNNING;
    }
    /** Water only into an enclosed natural-soil basin; never flood a building. */
    private void irrigate(SkillContext c, BlockPos plot) {
        var inv = c.citizen.getInventory();
        if (!Crops.needsFarmland(seed) || !inv.containsAtLeast("minecraft:water_bucket", 1)
                || c.level.dimensionType().ultraWarm()) return;
        for (BlockPos p : BlockPos.betweenClosed(plot.offset(-4, 0, -4), plot.offset(4, 1, 4)))
            if (c.level.isLoaded(p) && c.level.getFluidState(p).is(FluidTags.WATER)) return;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos basin = plot.relative(dir);
            var soil = c.level.getBlockState(basin);
            if (!(soil.is(Blocks.DIRT) || soil.is(Blocks.GRASS_BLOCK))
                    || !c.level.getBlockState(basin.above()).isAir()
                    || !c.level.getBlockState(basin.below()).isFaceSturdy(c.level, basin.below(), Direction.UP)
                    || c.level.getBlockEntity(basin) != null) continue;
            boolean enclosed = true;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos wall = basin.relative(side);
                if (!c.level.getBlockState(wall).isFaceSturdy(c.level, wall, side.getOpposite())) enclosed = false;
            }
            if (!enclosed || !c.level.setBlock(basin, Blocks.WATER.defaultBlockState(), 3)) continue;
            inv.extract("minecraft:water_bucket", 1);
            int remainder = inv.insert(new ItemStack(Items.BUCKET));
            if (remainder > 0) c.citizen.spawnAtLocation(new ItemStack(Items.BUCKET, remainder));
            return;
        }
    }
    public void cancel(SkillContext c) {
        CitizenSkill traversal = c.get("cultivate.walk", (CitizenSkill) null);
        SkillContext sub = c.get("cultivate.walk.ctx", (SkillContext) null);
        if (traversal != null && sub != null) traversal.cancel(sub);
        c.navigator.stop();
    }
    public String progressLabel(SkillContext c) { return "cultivating " + seed + " (" + planted + ")"; }
}
