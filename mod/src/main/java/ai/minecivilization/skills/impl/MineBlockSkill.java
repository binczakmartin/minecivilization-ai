package ai.minecivilization.skills.impl;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.navigation.LevelBlockView;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Physically mine one block at params.position over time (hardness-based),
 * then break it with drops. No instant mining, no block-removal cheats.
 */
public final class MineBlockSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.MINE_BLOCK;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.position != null;
    }

    @Override
    public void start(SkillContext context) {
        int[] p = context.params.position;
        context.put("pos", new BlockPos(p[0], p[1], p[2]));
        context.put("progress", 0f);
        BlockPos pos = new BlockPos(p[0], p[1], p[2]);
        BlockState state = context.level.getBlockState(pos);
        if (state.isAir()) {
            context.fail(new SkillFailure("BLOCK_ALREADY_MINED",
                    "Target block no longer exists.", true));
        } else if (state.getDestroySpeed(context.level, pos) < 0f) {
            context.fail(new SkillFailure("UNBREAKABLE_BLOCK",
                    "Target block cannot be mined.", false));
        } else {
            // Capture the exact state found by the search.  A player or another
            // worker can replace a natural block with a building block while
            // the mining progress is running; the old implementation happily
            // destroyed whatever happened to occupy the coordinate at the end.
            context.put("expectedState", state);
        }
    }

    @Override
    public SkillResult tick(SkillContext context) {
        if (context.failure != null) return SkillResult.FAILED;

        BlockPos pos = context.get("pos", (BlockPos) null);
        if (pos == null) {
            context.fail(SkillFailure.notFound("no target position"));
            return SkillResult.FAILED;
        }
        BlockState state = context.level.getBlockState(pos);
        if (state.isAir()) {
            context.fail(new SkillFailure("BLOCK_ALREADY_MINED",
                    "Target block was already mined.", true));
            return SkillResult.FAILED;
        }
        BlockState expected = context.get("expectedState", (BlockState) null);
        if (expected != null && !state.equals(expected)) {
            context.fail(new SkillFailure("TARGET_CHANGED",
                    "the target block changed while the citizen was working", true));
            return SkillResult.FAILED;
        }
        if (!new LevelBlockView(context.level).diggable(pos)
                && !authorizedProjectCell(context, pos)) {
            context.fail(new SkillFailure("UNBREAKABLE_BLOCK",
                    "Target became protected, fluid-filled or unbreakable.", true));
            return SkillResult.FAILED;
        }

        // must be close enough to physically reach
        if (context.citizen.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 20.0) {
            context.fail(SkillFailure.unreachable("mine target out of reach"));
            return SkillResult.FAILED;
        }

        WorkAnimation animation = "chop".equals(context.params.extra.get("workAnimation"))
                ? WorkAnimation.CHOP : WorkAnimation.MINE;
        context.citizen.animateAction(animation, pos);
        float hardness = state.getDestroySpeed(context.level, pos);
        float speed = (float) (1.0 / (Math.max(hardness, 0.05) * 30.0 + 1.0));
        speed *= (1.0f + context.citizen.getSkills().mining * 0.005f); // competence, not magic
        speed *= toolSpeedBonus(context, state);                      // real tools mine faster
        float progress = context.get("progress", 0f) + speed;
        context.put("progress", progress);
        // Swinging at a block is work, and it is done standing perfectly
        // still. Without saying so, a citizen part-way through a block of
        // deepslate looked identical to one that had stopped, and had its job
        // taken away for being motionless.
        context.startGameTime = context.level.getGameTime();

        if (progress >= 1.0f) {
            Block block = state.getBlock();
            boolean dropped = context.level.destroyBlock(pos, true, context.citizen, 0);
            if (!dropped) {
                context.fail(new SkillFailure("BLOCK_BREAK_FAILED",
                        "Block did not drop anything.", true));
                return SkillResult.FAILED;
            }
            context.citizen.getSkills().addXp("mining", 0.05f);
            return SkillResult.COMPLETED;
        }
        return SkillResult.RUNNING;
    }

    private boolean authorizedProjectCell(SkillContext context, BlockPos pos) {
        String projectId = context.params.extra.get("authorizedProject");
        return projectId != null
                && ai.minecivilization.construction.ConstructionManager
                .get(context.level).ownsCell(projectId, pos);
    }

    /**
     * Best correct tool the citizen carries for this block (1.0 bare-handed):
     * a stone pickaxe mines stone several times faster than fists, but the
     * hardness-based progress model still keeps mining physical over time.
     */
    private static float toolSpeedBonus(SkillContext context, BlockState state) {
        var inventory = context.citizen.getInventory();
        float best = 1.0f;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) continue;
            best = Math.max(best, stack.getDestroySpeed(state));
        }
        return best;
    }

    @Override
    public void cancel(SkillContext context) {
    }

    @Override
    public String progressLabel(SkillContext context) {
        float p = context.get("progress", 0f);
        return String.format("mining %.0f%%", Math.min(99f, p * 100f));
    }
}
