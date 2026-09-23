package ai.minecivilization.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;

/**
 * Plans a route a citizen can <em>make</em> passable, not merely one that
 * already is.
 *
 * <p>Vanilla path navigation only ever answers "can I walk there right now?".
 * That is why a citizen used to give up on the far side of a ravine or a wall
 * with {@code TARGET_UNREACHABLE}. This planner runs A* over the same cells but
 * with three extra moves that cost blocks and time instead of being impossible:
 * digging through, bridging over, and pillaring up.</p>
 *
 * <p>Cost is tuned so an ordinary walk always wins when one exists: a dig is
 * worth 4.5 walks and a placement 3.5, so a citizen only ever tunnels when
 * going around is genuinely longer. The result is a {@link TerrainPlan} — data,
 * executed later, one block at a time, by the normal mining and placing
 * skills.</p>
 *
 * <p>Pure: the world is reached only through {@link BlockView}, so the whole
 * thing is unit-tested against hand-drawn ravines and walls.</p>
 */
public final class TerrainPlanner {

    // Cost model, in tenths of a "step". Walking is the unit.
    static final int COST_WALK = 10;
    static final int COST_DIAGONAL = 14;
    static final int COST_DIG = 45;
    static final int COST_PLACE = 35;
    /** Dropping is free-ish but mildly discouraged: it is one-way. */
    static final int COST_FALL_PER_BLOCK = 4;
    /**
     * Surcharge on any move that changes height. Without it, descending into a
     * ravine and climbing back out ties with bridging straight across, and the
     * tie breaks arbitrarily — citizens built V-shaped bridges. Small enough to
     * never beat a genuinely shorter route, big enough to settle every tie in
     * favour of staying level.
     */
    static final int COST_CLIMB = 3;

    /** Tuning knobs, all bounded so planning can never stall a server tick. */
    public static final class Options {
        /** Hard ceiling on A* expansions — the safety valve for open terrain. */
        public int maxNodes = 12_000;
        /** Hard ceiling on blocks dug + placed across the whole route. */
        public int maxOps = 64;
        /** Hard ceiling on placements, independently of digs. */
        public int maxPlacements = Integer.MAX_VALUE;
        /** Chebyshev distance to the goal that counts as arrival. */
        public int arrivalRadius = 1;
        /** Allow breaking blocks to get through. */
        public boolean allowDig = true;
        /** Allow placing blocks to bridge/pillar (false when carrying no material). */
        public boolean allowPlace = true;
        /** Longest safe drop, in blocks — 3 is the vanilla no-damage limit. */
        public int maxDrop = 3;
        /** Horizontal half-size of the search box around start and goal. */
        public int searchBox = 64;
        /** Vertical half-size of the search box. */
        public int searchHeight = 40;

        public Options maxOps(int n) {
            this.maxOps = n;
            return this;
        }

        public Options allowPlace(boolean allow) {
            this.allowPlace = allow;
            return this;
        }

        public Options maxPlacements(int n) {
            this.maxPlacements = Math.max(0, n);
            return this;
        }

        public Options arrivalRadius(int r) {
            this.arrivalRadius = r;
            return this;
        }
    }

    private TerrainPlanner() {
    }

    /**
     * Plan a physically realisable route from {@code start} to within
     * {@code options.arrivalRadius} of {@code goal}.
     *
     * @return the plan, or {@code null} when no route exists inside the budget.
     *         An empty (non-null) plan means the citizen already stands at the goal.
     */
    public static TerrainPlan plan(BlockPos start, BlockPos goal,
                                   BlockView view, Options options) {
        if (start == null || goal == null || view == null) return null;
        Options opts = options == null ? new Options() : options;

        BlockPos from = start.immutable();
        if (arrived(from, goal, opts)) {
            return new TerrainPlan(List.of());
        }

        Map<BlockPos, Node> best = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingInt((Node n) -> n.f)
                .thenComparingInt(n -> n.g));

        Node origin = new Node(from, null, TerrainPlan.MoveKind.WALK, List.of(), 0, 0, 0);
        origin.f = heuristic(from, goal);
        open.add(origin);
        best.put(from, origin);

        int expanded = 0;
        while (!open.isEmpty() && expanded < opts.maxNodes) {
            Node current = open.poll();
            // stale queue entry: a cheaper route to this cell was already settled
            Node known = best.get(current.pos);
            if (known != null && known.g < current.g) continue;
            expanded++;

            if (arrived(current.pos, goal, opts)) {
                return reconstruct(current);
            }

            for (Move move : moves(current, view, opts, goal, from)) {
                int g = current.g + move.cost;
                int ops = current.opsUsed + move.ops.size();
                if (ops > opts.maxOps) continue;
                int placements = current.placements + countPlacements(move.ops);
                if (placements > opts.maxPlacements) continue;

                Node seen = best.get(move.dest);
                if (seen != null && seen.g <= g) continue;

                Node next = new Node(move.dest, current, move.kind, move.ops, g, ops, placements);
                next.f = g + heuristic(move.dest, goal);
                best.put(move.dest, next);
                open.add(next);
            }
        }
        return null;
    }

    /** Convenience: default options. */
    public static TerrainPlan plan(BlockPos start, BlockPos goal, BlockView view) {
        return plan(start, goal, view, new Options());
    }

    // ------------------------------------------------------------------ goal & heuristic

    /** Shared Chebyshev arrival rule used by the planner and its skill. */
    public static boolean arrivedAt(BlockPos pos, BlockPos goal, int radius) {
        if (pos == null || goal == null) return false;
        int dx = Math.abs(pos.getX() - goal.getX());
        int dy = Math.abs(pos.getY() - goal.getY());
        int dz = Math.abs(pos.getZ() - goal.getZ());
        return Math.max(dx, Math.max(dy, dz)) <= Math.max(0, radius);
    }

    private static boolean arrived(BlockPos pos, BlockPos goal, Options opts) {
        return arrivedAt(pos, goal, opts.arrivalRadius);
    }

    private static int countPlacements(List<TerrainPlan.Op> ops) {
        int count = 0;
        for (TerrainPlan.Op op : ops) {
            if (op.kind == TerrainPlan.OpKind.PLACE) count++;
        }
        return count;
    }

    /**
     * Admissible: every move changes each coordinate by at most one and costs
     * at least {@link #COST_WALK}, so the Chebyshev distance can never
     * overestimate. Keeping it admissible is what makes the planner prefer a
     * plain walk over tunnelling whenever one exists.
     */
    private static int heuristic(BlockPos pos, BlockPos goal) {
        int dx = Math.abs(pos.getX() - goal.getX());
        int dy = Math.abs(pos.getY() - goal.getY());
        int dz = Math.abs(pos.getZ() - goal.getZ());
        return COST_WALK * Math.max(dx, Math.max(dy, dz));
    }

    // ------------------------------------------------------------------ move generation

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static List<Move> moves(Node node, BlockView view, Options opts, BlockPos goal,
                                   BlockPos searchOrigin) {
        List<Move> out = new ArrayList<>(16);
        BlockPos p = node.pos;

        // 1. Cardinal steps at -1 / 0 / +1, each allowed to dig and bridge.
        for (int[] d : CARDINAL) {
            for (int dy = 1; dy >= -1; dy--) {
                Move m = horizontal(p, d[0], dy, d[1], COST_WALK, view, opts, searchOrigin, goal);
                if (m != null) out.add(m);
            }
        }

        // 2. Diagonals, but only as a pure walk: a half-dug corner is not a
        //    shape the citizen can actually squeeze through.
        for (int[] d : DIAGONAL) {
            for (int dy = 1; dy >= -1; dy--) {
                Move m = horizontal(p, d[0], dy, d[1], COST_DIAGONAL, view, opts, searchOrigin, goal);
                if (m == null || !m.ops.isEmpty()) continue;
                if (!cornersClear(p, d[0], d[1], dy, view)) continue;
                out.add(m);
            }
        }

        // 3. Controlled drops of 2..maxDrop onto existing ground.
        for (int[] d : CARDINAL) {
            Move m = fall(p, d[0], d[1], view, opts, searchOrigin, goal);
            if (m != null) out.add(m);
        }

        // 4. Pillar up / dig down — the vertical escapes.
        Move up = pillarUp(p, view, opts, searchOrigin, goal);
        if (up != null) out.add(up);
        Move down = digDown(p, view, opts, searchOrigin, goal);
        if (down != null) out.add(down);

        return out;
    }

    /**
     * A horizontal step, digging body-height obstructions and bridging a
     * missing floor as needed. Returns null when the cell cannot be made
     * walkable inside the current permissions.
     */
    private static Move horizontal(BlockPos p, int dx, int dy, int dz,
                                   int baseCost, BlockView view, Options opts,
                                   BlockPos searchOrigin, BlockPos searchGoal) {
        if (dy < 0 && opts.maxDrop <= 0) return null;
        BlockPos dest = p.offset(dx, dy, dz);
        if (!view.inBounds(dest) || !inSearchBox(searchOrigin, searchGoal, dest, opts)) return null;

        List<TerrainPlan.Op> ops = new ArrayList<>(3);
        int cost = baseCost + (dy != 0 ? COST_CLIMB : 0);

        // Body clearance at the destination: feet and head.
        for (BlockPos cell : new BlockPos[]{dest, dest.above()}) {
            if (view.passable(cell)) continue;
            if (!opts.allowDig || !view.diggable(cell)) return null;
            ops.add(new TerrainPlan.Op(TerrainPlan.OpKind.DIG, cell));
            cost += COST_DIG;
        }

        // Stepping up also needs room above the citizen's current head.
        if (dy > 0) {
            BlockPos jumpRoom = p.above(2);
            if (!view.passable(jumpRoom)) {
                if (!opts.allowDig || !view.diggable(jumpRoom)) return null;
                ops.add(new TerrainPlan.Op(TerrainPlan.OpKind.DIG, jumpRoom));
                cost += COST_DIG;
            }
        }

        // Floor: bridge a gap, or clear-then-bridge a non-sturdy obstruction
        // such as a leaf canopy that cannot be stood on.
        BlockPos floor = dest.below();
        if (!view.sturdy(floor)) {
            if (!view.passable(floor)) {
                if (!opts.allowDig || !view.diggable(floor)) return null;
                ops.add(new TerrainPlan.Op(TerrainPlan.OpKind.DIG, floor));
                cost += COST_DIG;
            }
            if (!opts.allowPlace) return null;
            // A single floating deck block is not a bridge.  The cell below
            // the deck must be real ground; otherwise reject this route rather
            // than handing PlaceBlockSkill a placement it can never survive.
            if (!view.sturdy(floor.below())) return null;
            ops.add(new TerrainPlan.Op(TerrainPlan.OpKind.PLACE, floor));
            cost += COST_PLACE;
        }

        return new Move(dest, kindOf(dy, ops), ops, cost);
    }

    /**
     * Diagonals must not cut a corner: both cells the citizen brushes past have
     * to be free at body height already.
     */
    private static boolean cornersClear(BlockPos p, int dx, int dz, int dy, BlockView view) {
        BlockPos sideX = p.offset(dx, dy, 0);
        BlockPos sideZ = p.offset(0, dy, dz);
        return view.passable(sideX) && view.passable(sideX.above())
                && view.passable(sideZ) && view.passable(sideZ.above());
    }

    /**
     * Step off a ledge and drop 2..maxDrop blocks onto existing ground — never
     * dug, never bridged. A drop of one block is already covered by an ordinary
     * {@code dy = -1} step, so this starts at two.
     */
    private static Move fall(BlockPos p, int dx, int dz, BlockView view, Options opts,
                             BlockPos searchOrigin, BlockPos searchGoal) {
        BlockPos column = p.offset(dx, 0, dz);
        if (!view.inBounds(column)) return null;
        if (!view.passable(column) || !view.passable(column.above())) return null;

        for (int height = 2; height <= opts.maxDrop; height++) {
            BlockPos dest = column.below(height);
            if (!view.inBounds(dest) || !inSearchBox(searchOrigin, searchGoal, dest, opts)) return null;
            // every cell fallen through, landing cell included, must be clear
            boolean clear = true;
            for (int d = 1; d <= height; d++) {
                if (!view.passable(column.below(d))) {
                    clear = false;
                    break;
                }
            }
            if (!clear) return null;
            if (view.sturdy(dest.below())) {
                return new Move(dest, TerrainPlan.MoveKind.FALL, List.of(),
                        COST_WALK + COST_CLIMB + height * COST_FALL_PER_BLOCK);
            }
        }
        return null;
    }

    /** Place a block under one's own feet and rise one level. */
    private static Move pillarUp(BlockPos p, BlockView view, Options opts,
                                 BlockPos searchOrigin, BlockPos searchGoal) {
        if (!opts.allowPlace) return null;
        BlockPos dest = p.above();
        if (!view.inBounds(dest) || !inSearchBox(searchOrigin, searchGoal, dest, opts)) return null;

        List<TerrainPlan.Op> ops = new ArrayList<>(3);
        int cost = COST_WALK + COST_CLIMB + COST_PLACE;

        // The destination body must be clear as well as the new support cell.
        // Previously only the head room was checked, so a solid block at the
        // next level produced a PILLAR_UP plan that could never be entered.
        for (BlockPos body : new BlockPos[]{dest, dest.above()}) {
            if (view.passable(body)) continue;
            if (!opts.allowDig || !view.diggable(body)) return null;
            ops.add(new TerrainPlan.Op(TerrainPlan.OpKind.DIG, body));
            cost += COST_DIG;
        }
        // The support goes into the cell the citizen is standing in.
        ops.add(new TerrainPlan.Op(TerrainPlan.OpKind.PLACE, p));
        return new Move(dest, TerrainPlan.MoveKind.PILLAR_UP, ops, cost);
    }

    /** Mine the block underfoot and descend into it. */
    private static Move digDown(BlockPos p, BlockView view, Options opts,
                                BlockPos searchOrigin, BlockPos searchGoal) {
        if (opts.maxDrop <= 0) return null;
        BlockPos dest = p.below();
        if (!view.inBounds(dest) || !inSearchBox(searchOrigin, searchGoal, dest, opts)) return null;
        // Never dig into thin air: the cell below the destination must hold us.
        if (!view.sturdy(dest.below())) return null;

        if (view.passable(dest)) {
            return new Move(dest, TerrainPlan.MoveKind.STEP_DOWN, List.of(),
                    COST_WALK + COST_CLIMB);
        }
        if (!opts.allowDig || !view.diggable(dest)) return null;
        return new Move(dest, TerrainPlan.MoveKind.DIG_DOWN,
                List.of(new TerrainPlan.Op(TerrainPlan.OpKind.DIG, dest)),
                COST_WALK + COST_CLIMB + COST_DIG);
    }

    private static TerrainPlan.MoveKind kindOf(int dy, List<TerrainPlan.Op> ops) {
        boolean digs = false;
        boolean places = false;
        for (TerrainPlan.Op op : ops) {
            if (op.kind == TerrainPlan.OpKind.DIG) digs = true;
            else places = true;
        }
        if (places) return TerrainPlan.MoveKind.BRIDGE;
        if (digs) return TerrainPlan.MoveKind.DIG_THROUGH;
        if (dy > 0) return TerrainPlan.MoveKind.STEP_UP;
        if (dy < 0) return TerrainPlan.MoveKind.STEP_DOWN;
        return TerrainPlan.MoveKind.WALK;
    }

    private static boolean inSearchBox(BlockPos start, BlockPos goal, BlockPos candidate,
                                       Options opts) {
        // Long journeys are split into short TravelLegs, so either endpoint's
        // box is sufficient. Comparing against the current A* node (the old
        // behaviour) made every adjacent step look valid and turned the box
        // setting into a no-op.
        return near(candidate, start, opts) || near(candidate, goal, opts);
    }

    private static boolean near(BlockPos candidate, BlockPos anchor, Options opts) {
        return Math.abs(candidate.getX() - anchor.getX()) <= opts.searchBox
                && Math.abs(candidate.getZ() - anchor.getZ()) <= opts.searchBox
                && Math.abs(candidate.getY() - anchor.getY()) <= opts.searchHeight;
    }

    // ------------------------------------------------------------------ reconstruction

    private static TerrainPlan reconstruct(Node goal) {
        Deque<TerrainPlan.Step> steps = new ArrayDeque<>();
        Node n = goal;
        while (n != null && n.parent != null) {
            steps.addFirst(new TerrainPlan.Step(n.pos, n.move, n.ops));
            n = n.parent;
        }
        return new TerrainPlan(new ArrayList<>(steps));
    }

    // ------------------------------------------------------------------ internals

    private static final class Node {
        final BlockPos pos;
        final Node parent;
        final TerrainPlan.MoveKind move;
        final List<TerrainPlan.Op> ops;
        final int g;
        final int opsUsed;
        final int placements;
        int f;

        Node(BlockPos pos, Node parent, TerrainPlan.MoveKind move,
             List<TerrainPlan.Op> ops, int g, int opsUsed, int placements) {
            this.pos = pos.immutable();
            this.parent = parent;
            this.move = move;
            this.ops = ops;
            this.g = g;
            this.opsUsed = opsUsed;
            this.placements = placements;
        }
    }

    private static final class Move {
        final BlockPos dest;
        final TerrainPlan.MoveKind kind;
        final List<TerrainPlan.Op> ops;
        final int cost;

        Move(BlockPos dest, TerrainPlan.MoveKind kind, List<TerrainPlan.Op> ops, int cost) {
            this.dest = dest.immutable();
            this.kind = kind;
            this.ops = List.copyOf(ops);
            this.cost = cost;
        }
    }
}
