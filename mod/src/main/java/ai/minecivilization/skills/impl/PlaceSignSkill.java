package ai.minecivilization.skills.impl;

import ai.minecivilization.colony.LandmarkRegistry;
import ai.minecivilization.colony.SignKind;
import ai.minecivilization.colony.SignPlan;
import ai.minecivilization.colony.SignRegistry;
import ai.minecivilization.colony.Signpost;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.navigation.PlacementSafety;
import ai.minecivilization.navigation.PlacementSupport;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import ai.minecivilization.telemetry.ColonyEventLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * PLACE_SIGN — put up a post that says what somewhere is.
 *
 * <p>Two blocks, not one: a fence post to stand on and a sign on top, because
 * a sign placed straight onto grass is at ankle height and reads as litter. The
 * text goes on both faces, the colony remembers it in {@link SignRegistry}, and
 * the event goes into the feed — so labelling a district is simultaneously a
 * physical act, a memory, and a line a player sees.</p>
 *
 * <p>Idempotent: a post already standing here with the right words is a success,
 * not a reason to place a second one.</p>
 */
public final class PlaceSignSkill implements CitizenSkill {

    private static final double REACH_SQR = 20.0;

    /** Wood the colony can make posts and signs from, in preference order. */
    private static final String[] WOODS = {
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove"
    };

    private SignPlan.Text text;
    private BlockPos post;

    @Override
    public SkillType type() {
        return SkillType.PLACE_SIGN;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.position != null;
    }

    @Override
    public void start(SkillContext context) {
        int[] p = context.params.position;
        post = new BlockPos(p[0], p[1], p[2]);
        text = parse(context);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        ServerLevel level = context.level;
        if (post == null || text == null) {
            context.fail(SkillFailure.notFound("no sign to write"));
            return SkillResult.FAILED;
        }

        BlockPos signAt = post.above();
        // Somebody got here first — including this citizen on an earlier tick.
        if (level.getBlockEntity(signAt) instanceof net.minecraft.world.level.block.entity.SignBlockEntity) {
            return write(context, signAt);
        }

        SkillResult arrival = SkillNavigation.approach(context, post, REACH_SQR, "sign.walk");
        if (arrival == SkillResult.FAILED) {
            context.fail(SkillFailure.unreachable("could not reach the signpost site"));
            return SkillResult.FAILED;
        }
        if (arrival == SkillResult.RUNNING) return SkillResult.RUNNING;

        String wood = availableWood(context);
        if (wood == null) {
            context.fail(SkillFailure.missing("no sign to put up"));
            return SkillResult.FAILED;
        }

        if (!ensurePost(context, wood)) return SkillResult.RUNNING;
        if (!place(context, signAt, "minecraft:" + wood + "_sign")) {
            context.fail(SkillFailure.missing("could not stand the sign up"));
            return SkillResult.FAILED;
        }
        return write(context, signAt);
    }

    // ------------------------------------------------------------------ placing

    /**
     * Make sure there is something for the sign to sit on.
     *
     * <p>Where the ground is already solid, that is the post. Where it is not,
     * a fence is put down first — which is also what makes a colony's signs
     * look deliberate rather than dropped.</p>
     */
    private boolean ensurePost(SkillContext context, String wood) {
        ServerLevel level = context.level;
        BlockState existing = level.getBlockState(post);
        if (!existing.canBeReplaced()) {
            return existing.isFaceSturdy(level, post, Direction.UP)
                    || existing.getBlock() instanceof net.minecraft.world.level.block.FenceBlock;
        }
        for (String id : new String[]{"minecraft:" + wood + "_fence",
                "minecraft:oak_fence", "minecraft:cobblestone", "minecraft:" + wood + "_log"}) {
            if (context.citizen.getInventory().count(id) > 0 && place(context, post, id)) {
                return true;
            }
        }
        // Nothing to raise a post with: settle for the ground under the target,
        // which is a lower sign but still a sign.
        return level.getBlockState(post.below()).isFaceSturdy(level, post.below(), Direction.UP);
    }

    private boolean place(SkillContext context, BlockPos pos, String blockId) {
        ServerLevel level = context.level;
        if (ConstructionManager.get(level).protectsCell(pos)) return false;
        if (!level.getBlockState(pos).canBeReplaced()) return false;

        CitizenInventory inventory = context.citizen.getInventory();
        if (!inventory.containsAtLeast(blockId, 1)) return false;

        BlockState state = ConstructionManager.parseState(level, blockId);
        if (state == null || !PlacementSupport.canPlace(level, state, pos)
                || !PlacementSafety.canOccupy(level, context.citizen, pos, false)) return false;

        inventory.extract(blockId, 1);
        if (!level.setBlock(pos, state, 3)) {
            inventory.insert(new net.minecraft.world.item.ItemStack(
                    CitizenInventory.itemById(blockId)));
            return false;
        }
        context.citizen.animateAction(WorkAnimation.BUILD, pos);
        context.citizen.onBlockPlaced(blockId);
        return true;
    }

    /** Write the words, tell the colony, and tell the player. */
    private SkillResult write(SkillContext context, BlockPos signAt) {
        ServerLevel level = context.level;
        if (!Signpost.write(level, signAt, text.title(), text.detail())) {
            context.fail(SkillFailure.notFound("the sign vanished before it could be written"));
            return SkillResult.FAILED;
        }
        SignRegistry.get(level).record(level, signAt, text);
        LandmarkRegistry.get(level).notice(level, signAt);
        ColonyEventLog.of(level).organisation(level,
                context.citizen.getIdentity().name + " put up a sign: " + text.flat()
                        + " at " + signAt.getX() + "," + signAt.getZ());
        context.citizen.getSkills().addXp("building", 0.02f);
        return SkillResult.COMPLETED;
    }

    // ------------------------------------------------------------------ text

    /**
     * The words this task wants on the sign.
     *
     * <p>Carried as {@code resource} (the whole line) and {@code target} (the
     * kind), so a sign job is an ordinary task with no special plumbing.</p>
     */
    private static SignPlan.Text parse(SkillContext context) {
        String label = context.params.resource;
        SignKind kind = null;
        if (context.params.target != null) {
            try {
                kind = SignKind.valueOf(context.params.target);
            } catch (IllegalArgumentException ignored) {
                kind = null;
            }
        }
        if (label == null || label.isBlank()) {
            return kind == null ? null : new SignPlan.Text(kind, kind.headline(), "");
        }
        // "ROAD -> IRON MINE" splits on the arrow; anything else on an em dash.
        int arrow = label.indexOf("->");
        if (arrow > 0) {
            String head = label.substring(0, arrow).trim();
            String tail = label.substring(arrow).trim();
            return new SignPlan.Text(kind == null ? SignKind.ROAD : kind, head, tail);
        }
        int dash = label.indexOf('—');
        if (dash < 0) dash = label.indexOf(" - ");
        if (dash > 0) {
            return new SignPlan.Text(kind == null ? SignKind.NOTICE : kind,
                    label.substring(0, dash).trim(),
                    label.substring(dash + 1).trim());
        }
        return new SignPlan.Text(kind == null ? SignKind.NOTICE : kind, label, "");
    }

    /** Wood the citizen is actually carrying a sign of. */
    private static String availableWood(SkillContext context) {
        var inventory = context.citizen.getInventory();
        for (String wood : WOODS) {
            if (inventory.count("minecraft:" + wood + "_sign") > 0) return wood;
        }
        return null;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        return text == null ? "signposting" : "signposting: " + text.title();
    }
}
