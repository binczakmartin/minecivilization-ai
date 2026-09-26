package ai.minecivilization.navigation;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * Blocks nobody could get to recently, shared by the whole colony.
 *
 * <p>A block search picks the nearest match, and the nearest match does not
 * change because a walk to it failed: the same stone block across a ravine was
 * chosen, failed and chosen again forty-eight times in one session. Anything
 * that proved unreachable is left alone for a few minutes, which is long enough
 * for the search to settle on something else and short enough that a block
 * made reachable by a new bridge is not forgotten forever.</p>
 */
public final class UnreachableMemory {

    /** How long a failed target is avoided, in ticks (three minutes). */
    public static final long FORGET_AFTER = 3600;

    private static final Map<BlockPos, Long> UNTIL = new HashMap<>();

    private UnreachableMemory() {
    }

    public static synchronized void note(BlockPos pos, long now) {
        if (pos == null) return;
        UNTIL.put(pos.immutable(), now + FORGET_AFTER);
        if (UNTIL.size() > 4096) UNTIL.values().removeIf(until -> until <= now);
    }

    public static synchronized boolean isUnreachable(BlockPos pos, long now) {
        Long until = UNTIL.get(pos);
        if (until == null) return false;
        if (until > now) return true;
        UNTIL.remove(pos);
        return false;
    }

    public static synchronized void clear() {
        UNTIL.clear();
    }
}
