package ai.minecivilization.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * A route that a citizen can physically realise: a sequence of one-block steps,
 * each optionally preceded by the block operations that make the step possible
 * (dig the wall, bridge the gap, pillar up).
 *
 * <p>The plan is data only. {@link ai.minecivilization.skills.impl.TraverseSkill}
 * executes it one operation at a time with the normal mining/placing skills, so
 * nothing here bypasses the "no free blocks" rule.</p>
 */
public final class TerrainPlan {

    /** What a citizen does to a single cell before stepping. */
    public enum OpKind {
        /** Break the block occupying the cell (drops go to the ground). */
        DIG,
        /** Put a carried block into the cell (bridge deck, pillar support). */
        PLACE
    }

    /** How the citizen gets from the previous cell into this one. */
    public enum MoveKind {
        /** Ordinary walk onto existing sturdy ground. */
        WALK,
        /** Walk up one block. */
        STEP_UP,
        /** Build a supported stair in the adjacent floor cell and step onto it. */
        STAIR_UP,
        /** Walk down one block. */
        STEP_DOWN,
        /** Controlled drop of 2–3 blocks onto existing ground. */
        FALL,
        /** Deck placed over a gap, then walked onto. */
        BRIDGE,
        /** Blocks removed at body height, then walked through. */
        DIG_THROUGH,
        /** Swum through water — no floor needed, and up counts as a direction. */
        SWIM,
        /** Block placed under one's own feet to gain a level. */
        PILLAR_UP,
        /** Block removed under one's own feet to descend. */
        DIG_DOWN
    }

    public static final class Op {
        public final OpKind kind;
        public final BlockPos pos;
        /** Optional physical variant, e.g. a stair facing for a built step. */
        public final String variant;

        public Op(OpKind kind, BlockPos pos) {
            this(kind, pos, "");
        }

        public Op(OpKind kind, BlockPos pos, String variant) {
            this.kind = kind;
            this.pos = pos.immutable();
            this.variant = variant == null ? "" : variant;
        }

        @Override
        public String toString() {
            return kind + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + (variant.isEmpty() ? "" : "[" + variant + "]");
        }
    }

    /** One cell of the route: do {@link #ops}, then stand on {@link #feet}. */
    public static final class Step {
        public final BlockPos feet;
        public final MoveKind move;
        public final List<Op> ops;

        public Step(BlockPos feet, MoveKind move, List<Op> ops) {
            this.feet = feet.immutable();
            this.move = move;
            this.ops = List.copyOf(ops);
        }

        /** A step that needs no world modification is a plain walk. */
        public boolean isPlainMove() {
            return ops.isEmpty();
        }

        @Override
        public String toString() {
            return move + "->" + feet.getX() + "," + feet.getY() + "," + feet.getZ()
                    + (ops.isEmpty() ? "" : " " + ops);
        }
    }

    private final List<Step> steps;

    public TerrainPlan(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    public List<Step> steps() {
        return steps;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** Where the citizen ends up, or null for an empty plan. */
    public BlockPos destination() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1).feet;
    }

    public int opCount() {
        int n = 0;
        for (Step s : steps) n += s.ops.size();
        return n;
    }

    public int countOps(OpKind kind) {
        int n = 0;
        for (Step s : steps) {
            for (Op op : s.ops) {
                if (op.kind == kind) n++;
            }
        }
        return n;
    }

    /** True when the route is pure walking — vanilla navigation can do it alone. */
    public boolean needsNoModification() {
        return opCount() == 0;
    }

    /** Cells the plan will place a block into, in execution order. */
    public List<BlockPos> placements() {
        List<BlockPos> out = new ArrayList<>();
        for (Step s : steps) {
            for (Op op : s.ops) {
                if (op.kind == OpKind.PLACE) out.add(op.pos);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
