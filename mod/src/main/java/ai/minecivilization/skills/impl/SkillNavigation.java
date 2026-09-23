package ai.minecivilization.skills.impl;

import ai.minecivilization.citizen.CitizenTaskParams;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillRegistry;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;

/**
 * Walking to a block, with the same stubbornness everywhere.
 *
 * <p>Tasks escalate to terrain modification when ordinary navigation gives up,
 * but the skills that walk somewhere <em>inside</em> a task — to a crafting
 * table, to a furnace, to a chest — called the navigator directly and simply
 * failed. In one session that was every remaining
 * {@code Could not reach target} in the log: citizens standing a short walk
 * from their own workbench, reporting it unreachable because a fence or a step
 * was in the way.</p>
 *
 * <p>This gives them the same answer the task layer has: try to walk, and when
 * walking genuinely will not do, bridge or dig a way through.</p>
 */
final class SkillNavigation {

    private SkillNavigation() {
    }

    /**
     * Move within {@code reachSqr} of {@code target}.
     *
     * @param key distinguishes concurrent approaches inside one skill's scratch space
     * @return COMPLETED when in reach, RUNNING while travelling, FAILED when
     *         even terrain modification cannot get there
     */
    static SkillResult approach(SkillContext context, BlockPos target,
                                double reachSqr, String key) {
        if (target == null) {
            context.fail(SkillFailure.notFound("nowhere to walk to"));
            return SkillResult.FAILED;
        }
        double distSqr = context.citizen.distanceToSqr(
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (distSqr <= reachSqr) {
            clear(context, key);
            context.navigator.stop();
            return SkillResult.COMPLETED;
        }

        CitizenSkill traverse = context.get(key, (CitizenSkill) null);
        if (traverse != null) {
            return tickTraverse(context, traverse, key);
        }

        context.navigator.moveTo(target, 1.0);
        context.navigator.tick();
        if (!context.navigator.hasFailed()) {
            return SkillResult.RUNNING;
        }

        // Ordinary navigation has run out of ideas. Make a way instead.
        context.navigator.stop();
        return beginTraverse(context, target, key);
    }

    private static SkillResult beginTraverse(SkillContext context, BlockPos target, String key) {
        CitizenTaskParams params = new CitizenTaskParams();
        params.position = new int[]{target.getX(), target.getY(), target.getZ()};
        params.extra.put("traverse.arrival", "2");

        SkillContext sub = new SkillContext(context.citizen, context.level,
                context.navigator, params);
        sub.timeoutTicks = context.timeoutTicks;
        sub.startGameTime = context.level.getGameTime();

        CitizenSkill traverse = SkillRegistry.create(SkillType.TRAVERSE);
        if (!traverse.canStart(sub)) {
            context.fail(context.navigator.failure());
            return SkillResult.FAILED;
        }
        traverse.start(sub);
        context.put(key, traverse);
        context.put(key + ".ctx", sub);
        return SkillResult.RUNNING;
    }

    private static SkillResult tickTraverse(SkillContext context, CitizenSkill traverse,
                                            String key) {
        SkillContext sub = context.get(key + ".ctx", (SkillContext) null);
        if (sub == null) {
            clear(context, key);
            return SkillResult.RUNNING;
        }
        SkillResult result = traverse.tick(sub);
        if (result == SkillResult.RUNNING) return SkillResult.RUNNING;

        SkillFailure failure = sub.failure;
        traverse.cancel(sub);
        clear(context, key);

        if (result == SkillResult.COMPLETED) {
            // Arrived at the traversal's target; the caller re-checks its reach.
            return SkillResult.RUNNING;
        }
        context.fail(failure != null ? failure
                : SkillFailure.unreachable("could not make a way through"));
        return SkillResult.FAILED;
    }

    private static void clear(SkillContext context, String key) {
        context.data.remove(key);
        context.data.remove(key + ".ctx");
    }
}
