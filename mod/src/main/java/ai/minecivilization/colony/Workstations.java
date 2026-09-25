package ai.minecivilization.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ai.minecivilization.construction.WorkClaimStore;
import ai.minecivilization.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Sharing out the colony's workbenches.
 *
 * <p>Asking the gazetteer for the nearest crafting table gives every citizen
 * the same answer, which is correct and catastrophic: sixteen citizens all walk
 * to one block, arrive, fail to fit, and report the table unreachable. In one
 * session that produced thirteen hundred identical failures against a single
 * crafting table at {@code -65,63,-12} while the colony did nothing else.</p>
 *
 * <p>The fix is to spread the colony across the stations it owns: hand out the
 * nearest one that is not already crowded, so the second citizen goes to the
 * second table rather than joining a queue at the first.</p>
 *
 * <p>Advisory rather than exclusive, deliberately. A reservation strict enough
 * to turn work away just moves the failure — nine of ten citizens waiting
 * politely for a table that will never come free is no better than ten of ten
 * jammed against it. When everything is busy the nearest is shared, and
 * {@link #isScarce} tells the colony to build another one, which is the only
 * answer that actually ends the queue.</p>
 */
public final class Workstations {

    /**
     * Citizens that may share one workstation.
     *
     * <p>Not one. The problem a claim solves is a queue of sixteen walking to
     * the same block and jamming each other out of arm's reach; three standing
     * round a workbench is a workshop. Setting it to one simply moved the
     * failure: nine of ten citizens then waited politely forever for a table
     * that was never going to come free.</p>
     */
    public static final int CAPACITY = 3;
    /** How long a workstation claim survives without renewal, in ticks. */
    private static final int CLAIM_TTL = 400;
    /** How many of a kind are considered before giving up on finding a free one. */
    private static final int CANDIDATES = 8;

    private Workstations() {
    }

    /**
     * The nearest workstation of this kind this citizen should walk to.
     *
     * <p>Spreads the colony across the stations it owns: the first free one is
     * claimed, so the next citizen is sent to a different table rather than
     * piling onto the same block.</p>
     *
     * <p>Advisory, not a gate. When every station is busy the nearest is
     * returned anyway — a crowded workbench is slow, but a citizen that
     * refuses to work because the table is popular has simply stopped, and a
     * colony with one table and ten citizens would never start. Scarcity is
     * reported separately through {@link #isScarce}, which is what makes the
     * colony build a second table instead of queueing for the first.</p>
     *
     * @return a workstation to use, or null only when the colony has none
     */
    @Nullable
    public static BlockPos claimNearest(ServerLevel level, CitizenEntity citizen,
                                        LandmarkKind kind) {
        if (level == null || citizen == null || kind == null) return null;
        BlockPos from = citizen.blockPosition();
        String owner = String.valueOf(citizen.getIdentity().citizenId);
        long now = level.getGameTime();

        List<BlockPos> candidates = nearest(level, kind, from, CANDIDATES);
        for (BlockPos candidate : candidates) {
            if (WorkClaimStore.claim(level, owner, candidate, claimKind(kind), now,
                    CLAIM_TTL, CAPACITY)) {
                return candidate;
            }
        }
        // All busy: share the nearest rather than stopping.
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** Give up a workstation early — the job finished or was abandoned. */
    public static void release(ServerLevel level, CitizenEntity citizen,
                               LandmarkKind kind, BlockPos pos) {
        if (level == null || citizen == null || pos == null) return;
        WorkClaimStore.release(level, String.valueOf(citizen.getIdentity().citizenId),
                pos, claimKind(kind));
    }

    /**
     * How oversubscribed a kind of workstation is.
     *
     * <p>Citizens per station, rounded up. This is the number that should make
     * a colony decide to build another workbench rather than queue.</p>
     */
    public static int pressure(ServerLevel level, LandmarkKind kind, int population) {
        if (level == null || kind == null) return 0;
        int stations = LandmarkRegistry.get(level).count(kind);
        if (stations <= 0) return population;
        return (population + stations - 1) / stations;
    }

    /** True when the colony plainly needs another one of these. */
    public static boolean isScarce(ServerLevel level, LandmarkKind kind, int population) {
        return pressure(level, kind, population) > CAPACITY;
    }

    // ------------------------------------------------------------------ lookup

    /**
     * The closest few workstations of a kind, nearest first.
     *
     * <p>Only ones that are really there: a remembered block that has since
     * been broken is forgotten on the spot rather than handed out.</p>
     */
    public static List<BlockPos> nearest(ServerLevel level, LandmarkKind kind,
                                         BlockPos from, int limit) {
        LandmarkRegistry registry = LandmarkRegistry.get(level);
        List<BlockPos> found = new ArrayList<>();
        for (LandmarkRegistry.Landmark landmark : registry.all()) {
            if (landmark.kind() != kind) continue;
            found.add(landmark.pos());
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(from)));

        List<BlockPos> usable = new ArrayList<>(Math.min(limit, found.size()));
        for (BlockPos pos : found) {
            if (usable.size() >= limit) break;
            // A landmark in an unloaded chunk is believed rather than checked:
            // "I cannot see it from here" is not "it is gone".
            if (level.isLoaded(pos) && !matches(level, pos, kind)) {
                registry.forget(pos);
                continue;
            }
            usable.add(pos);
        }
        return usable;
    }

    private static boolean matches(ServerLevel level, BlockPos pos, LandmarkKind kind) {
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS
                .getKey(level.getBlockState(pos).getBlock());
        return key != null && LandmarkKind.of(key.toString()) == kind;
    }

    /** Claim namespace, so a furnace claim never collides with a build claim. */
    private static String claimKind(LandmarkKind kind) {
        return "station:" + kind.name();
    }
}
