package ai.minecivilization.forestry;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

/**
 * Works out which blocks make up one tree.
 *
 * <p>A lumberjack that mines a single log leaves a floating canopy and walks
 * off — the forest fills with stumps and hovering leaves and never regrows.
 * Felling means taking the whole trunk and its branches, which is a connected
 * region of logs, found by flooding outward from the base.</p>
 *
 * <p>The hard part is not the flood, it is knowing when to stop. Logs are also
 * a building material: a player's cabin is a large connected region of logs,
 * and a lumberjack that "fells" it is a disaster. So a region only counts as a
 * tree if it is <em>leafy</em> — real trees carry a canopy, cabins do not — and
 * the flood is bounded in every direction regardless.</p>
 *
 * <p>Pure: the world arrives as two predicates, so tree shapes are unit tested
 * against hand-drawn oaks, jungles and log cabins.</p>
 */
public final class TreeShape {

    /** Upper bound on one tree — a large jungle tree is around 100 logs. */
    public static final int MAX_LOGS = 220;
    /** How far branches may spread horizontally from the trunk base. */
    public static final int MAX_RADIUS = 8;
    /** How tall a tree may be above its base. */
    public static final int MAX_HEIGHT = 40;
    /** Leaves that must touch the region before it counts as a tree, not a wall. */
    public static final int MIN_LEAVES = 4;
    /** Leaves taken with one tree before the canopy is called done. */
    public static final int MAX_CANOPY = 512;
    /**
     * How far a leaf may sit from the nearest log and still belong to the tree.
     *
     * <p>Vanilla leaves decay beyond distance 7 from a log, so anything further
     * than that was never this tree's. Five is tighter still, which keeps a
     * lumberjack from eating half a jungle through touching canopies.</p>
     */
    public static final int LEAF_REACH = 5;

    private TreeShape() {
    }

    /**
     * The foot of the trunk: walk down from {@code start} while logs continue.
     * Felling starts at the bottom so the citizen is never left mining at head
     * height on top of nothing.
     */
    public static BlockPos baseOf(BlockPos start, Predicate<BlockPos> isLog) {
        BlockPos base = start.immutable();
        for (int drop = 0; drop < MAX_HEIGHT; drop++) {
            BlockPos below = base.below();
            if (!isLog.test(below)) break;
            base = below;
        }
        return base;
    }

    /**
     * Every log of the tree containing {@code start}, in felling order: lowest
     * first, and within a layer nearest the trunk first.
     *
     * <p>Returns just the starting log when the region is not a tree — too
     * large, too wide, or carrying no canopy. Refusing to recognise a cabin as
     * a tree is more important than felling every last jungle giant.</p>
     */
    public static List<BlockPos> collect(BlockPos start, Predicate<BlockPos> isLog,
                                         Predicate<BlockPos> isLeaf) {
        if (start == null || isLog == null || !isLog.test(start)) return List.of();

        BlockPos base = baseOf(start, isLog);
        Set<BlockPos> logs = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        logs.add(base);
        frontier.add(base);

        boolean bounded = true;
        while (!frontier.isEmpty() && logs.size() <= MAX_LOGS) {
            BlockPos current = frontier.poll();
            for (BlockPos neighbour : around(current)) {
                if (logs.contains(neighbour)) continue;
                if (!isLog.test(neighbour)) continue;
                // Only a *log* outside the bounds means the region sprawls past
                // what a tree can be. Ordinary empty cells sit outside the box
                // all the time — every trunk has air beside it and ground below.
                if (!withinBounds(base, neighbour)) {
                    bounded = false;
                    continue;
                }
                logs.add(neighbour);
                frontier.add(neighbour);
            }
        }

        if (logs.size() > MAX_LOGS || !bounded || !isLeafy(logs, isLeaf)) {
            // Not a tree: a cabin wall, a log pile, or something far too big.
            return List.of(start.immutable());
        }

        List<BlockPos> ordered = new ArrayList<>(logs);
        ordered.sort(Comparator
                .comparingInt((BlockPos p) -> p.getY())
                .thenComparingInt(p -> horizontalDistSqr(base, p))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return ordered;
    }

    /**
     * The leaves belonging to a felled tree.
     *
     * <p>Felling only the logs leaves a canopy hanging in the air. Vanilla
     * decay eventually removes the ones it placed, slowly and only within
     * distance seven of a log — and never the persistent ones — so a worked
     * forest fills up with floating green. Taking the canopy down with the
     * trunk is what makes felling leave no trace, and it is also where the
     * saplings for replanting come from.</p>
     *
     * <p>Grown outward from the logs rather than collected by box, so a tree
     * standing against a cliff of leaves takes its own canopy and not the
     * cliff. Bounded by {@link #LEAF_REACH} and {@link #MAX_CANOPY}, because
     * touching canopies in a jungle are otherwise one connected region the
     * size of the biome.</p>
     */
    public static List<BlockPos> canopy(Collection<BlockPos> logs, Predicate<BlockPos> isLeaf) {
        List<BlockPos> ordered = new ArrayList<>();
        if (logs == null || logs.isEmpty() || isLeaf == null) return ordered;

        Set<BlockPos> trunk = new HashSet<>(logs);
        Set<BlockPos> leaves = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();

        // Seed from every leaf touching a log, then grow through the canopy.
        for (BlockPos log : trunk) {
            for (BlockPos neighbour : around(log)) {
                if (trunk.contains(neighbour) || leaves.contains(neighbour)) continue;
                if (!isLeaf.test(neighbour)) continue;
                leaves.add(neighbour);
                frontier.add(neighbour);
            }
        }
        while (!frontier.isEmpty() && leaves.size() < MAX_CANOPY) {
            BlockPos current = frontier.poll();
            for (BlockPos neighbour : around(current)) {
                if (trunk.contains(neighbour) || leaves.contains(neighbour)) continue;
                if (!isLeaf.test(neighbour)) continue;
                if (nearestLogDistance(trunk, neighbour) > LEAF_REACH) continue;
                leaves.add(neighbour);
                frontier.add(neighbour);
            }
        }

        ordered.addAll(leaves);
        ordered.sort(fellingComparator(baseOfAll(trunk)));
        return ordered;
    }

    /**
     * Logs and leaves in one work order: lowest first, trunk before canopy at
     * the same height.
     *
     * <p>Bottom-up because that is the order a citizen can physically reach —
     * it climbs the trunk it has not felled yet. Logs before leaves at a given
     * height because the log is what the next climb stands on.</p>
     */
    public static List<BlockPos> fellingOrder(List<BlockPos> logs, List<BlockPos> leaves) {
        List<BlockPos> out = new ArrayList<>();
        if (logs != null) out.addAll(logs);
        if (leaves == null || leaves.isEmpty()) return out;

        Set<BlockPos> logSet = new HashSet<>(out);
        BlockPos base = baseOfAll(logSet);

        // Every log first, then the canopy — not interleaved by height.
        //
        // Interleaving looks tidier and is much worse: an acacia's canopy sits
        // at the same height as its upper trunk, so a citizen that wanted one
        // log for a sword spent four minutes on a hundred and forty leaves
        // before finishing the tree. Taking the trunk out first means the tree
        // is *down* in seconds, vanilla decay starts on the foliage
        // immediately, and whatever the citizen clears afterwards is a bonus
        // rather than a toll.
        List<BlockPos> canopy = new ArrayList<>();
        for (BlockPos leaf : leaves) {
            if (!logSet.contains(leaf)) canopy.add(leaf);
        }
        canopy.sort(fellingComparator(base));
        out.sort(fellingComparator(base));
        out.addAll(canopy);
        return out;
    }

    /** How many of a felling order are logs — the part that must be finished. */
    public static int logCount(List<BlockPos> order, Set<BlockPos> logs) {
        if (order == null || logs == null) return 0;
        int count = 0;
        for (BlockPos pos : order) {
            if (!logs.contains(pos)) break;
            count++;
        }
        return count;
    }

    private static Comparator<BlockPos> fellingComparator(BlockPos base) {
        return Comparator
                .comparingInt((BlockPos p) -> p.getY())
                .thenComparingInt(p -> horizontalDistSqr(base, p))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ);
    }

    /** Chebyshev distance from a cell to the nearest log of the tree. */
    private static int nearestLogDistance(Set<BlockPos> logs, BlockPos pos) {
        int best = Integer.MAX_VALUE;
        for (BlockPos log : logs) {
            int d = Math.max(Math.abs(log.getX() - pos.getX()),
                    Math.max(Math.abs(log.getY() - pos.getY()),
                            Math.abs(log.getZ() - pos.getZ())));
            if (d < best) best = d;
            if (best <= 1) break;
        }
        return best;
    }

    /** The lowest, most central log — the stump, for ordering and replanting. */
    private static BlockPos baseOfAll(Set<BlockPos> logs) {
        BlockPos best = null;
        for (BlockPos log : logs) {
            if (best == null || log.getY() < best.getY()) best = log;
        }
        return best == null ? BlockPos.ZERO : best;
    }

    /**
     * A canopy is what separates a tree from a wall of the same blocks. Counted
     * over the whole region rather than per block, so a bare trunk under a
     * shared canopy still qualifies.
     */
    private static boolean isLeafy(Set<BlockPos> logs, Predicate<BlockPos> isLeaf) {
        if (isLeaf == null) return true;
        int leaves = 0;
        for (BlockPos log : logs) {
            for (BlockPos neighbour : around(log)) {
                if (logs.contains(neighbour)) continue;
                if (isLeaf.test(neighbour) && ++leaves >= MIN_LEAVES) return true;
            }
        }
        return false;
    }

    /** The 26 cells around one: trees branch diagonally, so faces are not enough. */
    private static List<BlockPos> around(BlockPos pos) {
        List<BlockPos> out = new ArrayList<>(26);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    out.add(pos.offset(dx, dy, dz));
                }
            }
        }
        return out;
    }

    private static boolean withinBounds(BlockPos base, BlockPos pos) {
        int dy = pos.getY() - base.getY();
        if (dy < 0 || dy > MAX_HEIGHT) return false;
        return Math.abs(pos.getX() - base.getX()) <= MAX_RADIUS
                && Math.abs(pos.getZ() - base.getZ()) <= MAX_RADIUS;
    }

    private static int horizontalDistSqr(BlockPos base, BlockPos pos) {
        int dx = pos.getX() - base.getX();
        int dz = pos.getZ() - base.getZ();
        return dx * dx + dz * dz;
    }
}
