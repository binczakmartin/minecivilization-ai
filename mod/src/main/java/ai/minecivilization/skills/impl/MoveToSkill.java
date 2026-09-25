package ai.minecivilization.skills.impl;

import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;

/**
 * Move to params.position.
 *
 * <p>Walking is tried first, and when walking genuinely will not do, a way is
 * made: bridge, tunnel, pillar. That escalation lives here rather than in the
 * callers, and that is the point. It used to exist only in the task layer's
 * {@code MOVE} branch, so anything that moved <em>inside</em> another job —
 * fetching wood for a craft, walking to a crop, stepping to a build site —
 * simply reported the target unreachable and gave up. A citizen three seconds
 * old, ten blocks from a tree, would fail six repaths in a fraction of a
 * second and be handed the same impossible job again.</p>
 *
 * <p>Every consumer of movement now gets the same stubbornness for free, and
 * only once per attempt: if making a way also fails, the target really is
 * unreachable and the caller hears so.</p>
 */
public final class MoveToSkill implements CitizenSkill {

    /** Scratch key for the terrain-modifying fallback, if it is needed. */
    private static final String FALLBACK = "moveTo.traverse";

    /**
     * How close counts as arrived, in blocks, unless the task says otherwise.
     *
     * <p>Almost every caller wants to be within arm's reach of something so it
     * can work on it — mine it, place against it, open it. Demanding the exact
     * cell instead meant a citizen standing beside its target kept walking,
     * eventually "failed", and handed the job to terrain modification. Arm's
     * reach is the honest answer to "am I there?", and it is what stops a walk
     * of three blocks turning into a tunnel.</p>
     */
    private static final double DEFAULT_ARRIVAL = 3.0;

    @Override
    public SkillType type() {
        return SkillType.MOVE_TO;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.position != null;
    }

    @Override
    public void start(SkillContext context) {
        int[] p = context.params.position;
        BlockPos pos = new BlockPos(p[0], p[1], p[2]);
        context.put("target", pos);
        context.navigator.requestSafeStep();
        context.navigator.moveToSafe(pos, 1.0);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        BlockPos target = context.get("target", (BlockPos) null);

        // Close enough to work from is arrived, whatever navigation thinks.
        // Checked before anything else so a citizen that is already in place
        // never starts, or continues, an expensive route to where it stands.
        if (withinArrival(context, target)) {
            context.navigator.stop();
            clearFallbackSkill(context);
            return SkillResult.COMPLETED;
        }

        // Already making a way through: see that through to the end.
        if (context.get(FALLBACK, (CitizenSkill) null) != null) {
            return tickFallback(context, target);
        }

        context.navigator.tick();
        if (context.navigator.hasFailed()) {
            SkillFailure walked = context.navigator.failure();
            if (ai.minecivilization.config.ModConfig.debug()) {
                com.mojang.logging.LogUtils.getLogger().info(
                        "[WalkTrace] {} at={} target={} dist={} onGround={} — escalating",
                        context.citizen.getIdentity().name, context.citizen.blockPosition(),
                        target,
                        target == null ? -1
                                : (int) Math.sqrt(context.citizen.blockPosition().distSqr(target)),
                        context.citizen.onGround());
            }
            context.navigator.stop();
            return beginFallback(context, target, walked);
        }
        if (context.navigator.isArrived() && !context.navigator.isMoving()) {
            context.navigator.stop();
            return SkillResult.COMPLETED;
        }
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ fallback

    /**
     * Ordinary navigation has run out of ideas; change the terrain instead.
     *
     * <p>The arrival radius is deliberately loose. This is a move, not a
     * placement: standing a block or two from the target is arriving, and
     * insisting on the exact cell is how a citizen ends up trying to occupy
     * the tree it came to chop.</p>
     */
    private SkillResult beginFallback(SkillContext context, BlockPos target,
                                      SkillFailure walked) {
        if (target == null) {
            context.fail(walked == null ? SkillFailure.unreachable("nowhere to walk to") : walked);
            return SkillResult.FAILED;
        }


        ai.minecivilization.citizen.CitizenTaskParams params =
                new ai.minecivilization.citizen.CitizenTaskParams();
        params.position = new int[]{target.getX(), target.getY(), target.getZ()};
        params.extra.put("traverse.arrival", "2");

        SkillContext sub = new SkillContext(context.citizen, context.level,
                context.navigator, params);
        sub.timeoutTicks = context.timeoutTicks;
        sub.startGameTime = context.level.getGameTime();

        CitizenSkill traverse = ai.minecivilization.skills.SkillRegistry
                .create(SkillType.TRAVERSE);
        if (!traverse.canStart(sub)) {
            context.fail(walked == null ? SkillFailure.unreachable("no way through") : walked);
            return SkillResult.FAILED;
        }
        traverse.start(sub);
        context.put(FALLBACK, traverse);
        context.put(FALLBACK + ".ctx", sub);
        // The clock restarts: making a way is honest work, not a stalled walk.
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    private SkillResult tickFallback(SkillContext context, BlockPos target) {
        CitizenSkill traverse = context.get(FALLBACK, (CitizenSkill) null);
        SkillContext sub = context.get(FALLBACK + ".ctx", (SkillContext) null);
        if (traverse == null || sub == null) {
            clearFallback(context);
            return SkillResult.RUNNING;
        }

        SkillResult result = traverse.tick(sub);
        if (result == SkillResult.RUNNING) {
            // Adopt the sub-skill's clock. Making a way resets its own window
            // every time it digs or places a block; without mirroring that,
            // the outer walk times out while the tunnel underneath it is
            // being cut perfectly well.
            context.startGameTime = sub.startGameTime;
            return SkillResult.RUNNING;
        }

        SkillFailure failure = sub.failure;
        traverse.cancel(sub);
        clearFallback(context);

        if (result == SkillResult.COMPLETED) return SkillResult.COMPLETED;
        context.fail(failure != null ? failure
                : SkillFailure.unreachable("could not make a way to " + target));
        return SkillResult.FAILED;
    }

    /** True when the citizen is near enough to the target to call it arrived. */
    private static boolean withinArrival(SkillContext context, BlockPos target) {
        if (target == null) return false;
        if (!context.citizen.onGround()) return false;

        double arrival = DEFAULT_ARRIVAL;
        String configured = context.params.extra.get("move.arrival");
        if (configured != null) {
            try {
                arrival = Math.max(0.5, Math.min(8.0, Double.parseDouble(configured)));
            } catch (NumberFormatException ignored) {
                arrival = DEFAULT_ARRIVAL;
            }
        }
        double distSqr = context.citizen.distanceToSqr(target.getX() + 0.5,
                target.getY() + 0.5, target.getZ() + 0.5);
        return distSqr <= arrival * arrival;
    }

    /** Abandon a running fallback without reporting its failure. */
    private static void clearFallbackSkill(SkillContext context) {
        CitizenSkill traverse = context.get(FALLBACK, (CitizenSkill) null);
        SkillContext sub = context.get(FALLBACK + ".ctx", (SkillContext) null);
        if (traverse != null && sub != null) traverse.cancel(sub);
        clearFallback(context);
    }

    private static void clearFallback(SkillContext context) {
        context.data.remove(FALLBACK);
        context.data.remove(FALLBACK + ".ctx");
    }

    @Override
    public void cancel(SkillContext context) {
        CitizenSkill traverse = context.get(FALLBACK, (CitizenSkill) null);
        SkillContext sub = context.get(FALLBACK + ".ctx", (SkillContext) null);
        if (traverse != null && sub != null) traverse.cancel(sub);
        clearFallback(context);
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        String where = context.params.position[0] + ","
                + context.params.position[1] + "," + context.params.position[2];
        return context.get(FALLBACK, (CitizenSkill) null) != null
                ? "making a way to " + where
                : "moving to " + where;
    }
}
