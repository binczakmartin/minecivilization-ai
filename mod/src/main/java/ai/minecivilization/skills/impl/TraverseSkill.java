package ai.minecivilization.skills.impl;

import java.util.List;

import ai.minecivilization.citizen.CitizenTaskParams;
import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.navigation.BlockView;
import ai.minecivilization.navigation.LevelBlockView;
import ai.minecivilization.navigation.ScaffoldMaterial;
import ai.minecivilization.navigation.TerrainPlan;
import ai.minecivilization.navigation.TerrainPlanner;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillRegistry;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * TRAVERSE — go somewhere even when no walkable route exists, by changing the
 * terrain: bridge the ravine, tunnel the wall, pillar up the cliff.
 *
 * <p>This is {@link MoveToSkill}'s escalation. Ordinary movement asks vanilla
 * navigation "can I walk there?"; when the answer is no, this skill asks
 * {@link TerrainPlanner} "what would I have to build or break to walk there?"
 * and then does exactly that — one block at a time, through the normal
 * {@link MineBlockSkill} and {@link PlaceBlockSkill}, so every block placed is
 * paid for out of the citizen's own inventory and every block broken drops
 * normally. Nothing here bypasses the physical rules.</p>
 *
 * <p>The world moves under long plans (another citizen mines the same wall,
 * gravel falls into the trench), so a plan is a hypothesis, not a commitment:
 * operations already satisfied are skipped, and anything unexpected triggers a
 * bounded replan from wherever the citizen now stands.</p>
 */
public final class TraverseSkill implements CitizenSkill {

    /** Replans before admitting the destination is genuinely out of reach. */
    private static final int MAX_REPLANS = 3;
    /** Arm's reach for an operation, squared — matches MINE_BLOCK. */
    private static final double OP_REACH_SQR = 20.0;
    /**
     * How far ahead one planned leg may reach. The terrain planner searches a
     * bounded box, so a goal beyond it cannot be planned at all — which is why
     * every walk home from a long expedition failed outright.
     */
    private static final int LEG_LENGTH = 40;
    /** Vertical distance covered by one waypoint when a mine/home leg is far apart. */
    private static final int VERTICAL_LEG = 8;
    /**
     * Block operations one leg of a route may cost.
     *
     * <p>Generous, because digging is paid for in time rather than material
     * and a shaft down to ore is genuinely dozens of blocks. The planner's own
     * node ceiling is what keeps planning cheap; this only bounds the size of
     * the job it is allowed to come back with.</p>
     */
    private static final int MAX_ROUTE_OPS = 160;

    private TerrainPlan plan;
    private BlockPos finalGoal;
    private BlockPos planOrigin;
    private int stepIndex;
    private int opIndex;
    private int replans;
    private int recoveredBlocks;
    private String scaffold;

    private CitizenSkill sub;
    private SkillContext subContext;
    private BlockPos walkTarget;

    @Override
    public SkillType type() {
        return SkillType.TRAVERSE;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.params.position != null;
    }

    @Override
    public void start(SkillContext context) {
        plan = null;
        finalGoal = null;
        planOrigin = context.citizen.blockPosition();
        stepIndex = 0;
        opIndex = 0;
        replans = 0;
        recoveredBlocks = 0;
        scaffold = null;
        sub = null;
        subContext = null;
        walkTarget = null;
        context.navigator.stop();
        // TRAVERSE owns the worker for its whole plan, including the short
        // pauses between sub-skills.  Keeping the crouch flag here prevents a
        // pillar operation from losing fall protection while it places a block.
        context.citizen.setBracedPlacement(true);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        if (plan == null) {
            SkillResult settled = buildPlan(context);
            if (settled != null) return settled;
        }
        List<TerrainPlan.Step> steps = plan.steps();
        if (stepIndex >= steps.size()) {
            context.citizen.setBracedPlacement(false);
            return SkillResult.COMPLETED;
        }

        TerrainPlan.Step step = steps.get(stepIndex);
        if (opIndex < step.ops.size()) {
            return runOperation(context, step.ops.get(opIndex));
        }
        return walk(context, steps);
    }

    // ------------------------------------------------------------------ planning

    /** @return a terminal result, or null to carry on with a fresh plan. */
    private SkillResult buildPlan(SkillContext context) {
        int[] p = context.params.position;
        if (p == null) {
            context.fail(new SkillFailure("INVALID_TASK", "TRAVERSE without position", false));
            return SkillResult.FAILED;
        }
        finalGoal = new BlockPos(p[0], p[1], p[2]);
        BlockView view = new LevelBlockView(context.level);
        var inventory = context.citizen.getInventory();

        // Long journeys are walked in legs. Planning is bounded to a box around
        // the citizen, so asking it for a route to somewhere 120 blocks away
        // returns nothing at all — the route exists, it simply cannot be seen
        // from here. Aiming at a point along the way turns an impossible
        // question into a series of answerable ones.
        BlockPos goal = legTowards(context, finalGoal);

        int carried = ScaffoldMaterial.availableAny(inventory);
        TerrainPlanner.Options options = new TerrainPlanner.Options();
        // First discover the physical route.  It may need more blocks than the
        // citizen currently holds; recover those from nearby real ground and
        // plan again rather than building half a pillar and failing.
        options.allowPlace = true;
        options.maxPlacements = 64;
        options.maxOps = MAX_ROUTE_OPS;
        options.arrivalRadius = arrivalRadius(context);

        planOrigin = context.citizen.blockPosition();
        TerrainPlan discovered = TerrainPlanner.plan(planOrigin, goal, view, options);
        if (discovered == null) {
            context.fail(SkillFailure.unreachable(
                    "no route to " + goal.getX() + "," + goal.getY() + "," + goal.getZ()
                            + " even after digging and bridging"));
            return SkillResult.FAILED;
        }
        if (discovered.isEmpty()) {
            if (arrivedAtFinalGoal(context)) {
                context.citizen.setBracedPlacement(false);
                context.citizen.setControlledDrop(false);
                return SkillResult.COMPLETED;
            }
            context.fail(SkillFailure.unreachable(
                    "terrain planner stopped inside the arrival radius without reaching "
                            + finalGoal));
            return SkillResult.FAILED;
        }

        int placements = discovered.countOps(TerrainPlan.OpKind.PLACE);
        if (placements > carried) {
            // Try to pay for the nice route by digging up some earth. If that
            // does not work, fall through and plan a route we can actually
            // afford — walking round, or tunnelling. Failing here instead was
            // the single largest source of failures in play: the citizen gave
            // up on a journey it could have walked, because the *best* route
            // happened to want blocks it did not have.
            SkillResult recovery = recoverScaffold(context, placements - carried);
            if (recovery == SkillResult.RUNNING) return recovery;
        }

        // Now enforce the physical budget.  The discovered route is retained
        // only when its placements fit; otherwise a fresh plan may choose a
        // shorter, dig-only route.
        carried = ScaffoldMaterial.availableAny(inventory);
        // Placing is limited by what is in the pack; digging is not. Tying the
        // whole operation budget to carried material meant a citizen with
        // empty hands could plan at most thirty-two blocks of work, which is
        // not enough to reach ore fifteen blocks under its feet — and it
        // reported that as "no route even after digging and bridging" while
        // standing on perfectly diggable ground.
        options.maxPlacements = carried;
        options.maxOps = MAX_ROUTE_OPS;
        plan = TerrainPlanner.plan(planOrigin, goal, view, options);
        if (plan == null) {
            context.fail(SkillFailure.unreachable(
                    "no route to " + goal.getX() + "," + goal.getY() + "," + goal.getZ()
                            + " within the carried-material budget"));
            return SkillResult.FAILED;
        }
        if (plan.isEmpty()) {
            if (arrivedAtFinalGoal(context)) {
                context.citizen.setBracedPlacement(false);
                context.citizen.setControlledDrop(false);
                return SkillResult.COMPLETED;
            }
            context.fail(SkillFailure.unreachable(
                    "terrain planner stopped inside the arrival radius without reaching "
                            + finalGoal));
            return SkillResult.FAILED;
        }

        placements = plan.countOps(TerrainPlan.OpKind.PLACE);
        if (placements > 0) {
            scaffold = ScaffoldMaterial.chooseAny(inventory, placements);
            if (scaffold == null) {
                context.fail(SkillFailure.missing(
                        "the route needs " + placements + " blocks to place and none are expendable"));
                return SkillResult.FAILED;
            }
        }
        stepIndex = 0;
        opIndex = 0;
        walkTarget = null;
        context.startGameTime = context.level.getGameTime();
        return null;
    }

    /**
     * Mine one nearby earth block when a planned pillar or bridge is underfunded.
     *
     * <p>Best-effort, never fatal. Somewhere to get a block from is a
     * convenience, not a precondition for travelling: if there is no loose
     * earth to hand the caller simply plans a cheaper route instead.</p>
     *
     * @return RUNNING while a block is being recovered, or null when this
     *         citizen cannot top itself up here
     */
    private SkillResult recoverScaffold(SkillContext context, int missing) {
        if (recoveredBlocks >= 12) return null;

        BlockPos material = findRecoveryBlock(context);
        if (material == null) return null;

        int before = ScaffoldMaterial.availableAny(context.citizen.getInventory());
        if (!context.level.destroyBlock(material, true, context.citizen)) return null;

        context.citizen.animateAction(WorkAnimation.MINE, material);
        collectNearbyDrops(context);
        int after = ScaffoldMaterial.availableAny(context.citizen.getInventory());
        if (after <= before) return null;

        recoveredBlocks++;
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    /** Plain earth close enough to mine, without cutting settlement property. */
    private BlockPos findRecoveryBlock(SkillContext context) {
        BlockPos feet = context.citizen.blockPosition();
        for (int dy = -1; dy >= -3; dy--) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dz == 0 && dy == -1) continue;
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (context.citizen.distanceToSqr(pos.getX() + 0.5,
                            pos.getY() + 0.5, pos.getZ() + 0.5) > 25.0) continue;
                    if (!context.level.isLoaded(pos)) continue;
                    BlockState state = context.level.getBlockState(pos);
                    if (!state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND)
                            || !new LevelBlockView(context.level).diggable(pos)) continue;
                    if (context.level.getBlockEntity(pos) != null) continue;

                    BlockState above = context.level.getBlockState(pos.above());
                    if (!above.getCollisionShape(context.level, pos.above()).isEmpty()
                            || above.is(BlockTags.CROPS)
                            || above.is(Blocks.CRAFTING_TABLE)
                            || above.is(Blocks.FURNACE)
                            || above.is(Blocks.CHEST)) continue;
                    return pos;
                }
            }
        }
        return null;
    }

    private void collectNearbyDrops(SkillContext context) {
        var inventory = context.citizen.getInventory();
        var box = context.citizen.getBoundingBox().inflate(4.0);
        for (ItemEntity item : context.level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!item.isAlive() || item.getItem().isEmpty()
                    || !ScaffoldMaterial.isExpendable(
                    ai.minecivilization.inventory.CitizenInventory.idOf(item.getItem()))) continue;
            int leftover = inventory.insert(item.getItem().copy());
            if (leftover <= 0) {
                item.discard();
            } else {
                var stack = item.getItem();
                stack.setCount(leftover);
                item.setItem(stack);
            }
        }
    }

    /** True when the citizen is at the journey's real destination. */
    private boolean arrivedAtFinalGoal(SkillContext context) {
        if (finalGoal == null) return true;
        if (!TerrainPlanner.arrivedAt(context.citizen.blockPosition(), finalGoal,
                Math.max(1, arrivalRadius(context)))) return false;
        // Ask whether this is a place a body fits, not whether somebody could
        // move into it: the citizen is already standing here, so the occupancy
        // test it used to run could never pass and every arrival inside the
        // radius was reported as a failure to reach the target.
        return context.citizen.onGround()
                && ai.minecivilization.navigation.PlacementSafety.canStandHere(
                context.level, context.citizen, context.citizen.blockPosition());
    }

    /**
     * The next leg's aim point: the goal itself when it is close enough to
     * plan for, otherwise a point {@link #LEG_LENGTH} blocks along the way,
     * dropped onto the surface so it is somewhere a citizen could stand.
     */
    private static BlockPos legTowards(SkillContext context, BlockPos goal) {
        BlockPos here = context.citizen.blockPosition();
        if (ai.minecivilization.navigation.TravelLeg.withinOneLeg(
                here.getX(), here.getZ(), goal.getX(), goal.getZ(), LEG_LENGTH)
                && Math.abs(goal.getY() - here.getY()) <= 40) {
            return goal;
        }
        int[] aim = ai.minecivilization.navigation.TravelLeg.aim(
                here.getX(), here.getZ(), goal.getX(), goal.getZ(), LEG_LENGTH);
        int x = aim[0];
        int z = aim[1];
        int verticalGap = goal.getY() - here.getY();
        int y;
        if (Math.abs(verticalGap) > 40) {
            // Do not aim a deep mine directly at a surface waypoint: the two
            // A* search boxes would be farther apart than their vertical range.
            // Climb/descend in short, fully physical vertical legs instead.
            y = here.getY() + Integer.signum(verticalGap) * VERTICAL_LEG;
        } else {
            y = context.level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        y = Math.max(context.level.getMinBuildHeight() + 1,
                Math.min(context.level.getMaxBuildHeight() - 2, y));
        return new BlockPos(x, y, z);
    }

    private static int arrivalRadius(SkillContext context) {
        String configured = context.params.extra.get("traverse.arrival");
        if (configured == null) return 1;
        try {
            return Math.max(0, Math.min(8, Integer.parseInt(configured)));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    /** Throw the plan away and build a new one from where the citizen now is. */
    private SkillResult replan(SkillContext context, SkillFailure cause) {
        if (++replans > MAX_REPLANS) {
            context.fail(cause != null ? cause
                    : SkillFailure.unreachable("route kept breaking down after "
                            + MAX_REPLANS + " replans"));
            return SkillResult.FAILED;
        }
        cancelSub(context);
        context.navigator.stop();
        context.citizen.setBracedPlacement(true);
        context.citizen.setControlledDrop(false);
        plan = null;
        walkTarget = null;
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ operations

    private SkillResult runOperation(SkillContext context, TerrainPlan.Op op) {
        // The world may already agree with us — never redo settled work.
        if (isSatisfied(context, op)) {
            finishOperation(context);
            return SkillResult.RUNNING;
        }
        // Drifted out of arm's reach: walk back onto the step we came from.
        if (context.citizen.distanceToSqr(op.pos.getX() + 0.5, op.pos.getY() + 0.5,
                op.pos.getZ() + 0.5) > OP_REACH_SQR) {
            return approachWorkSite(context);
        }

        if (sub == null && !beginOperation(context, op)) {
            return replan(context, context.failure);
        }

        // Mine/place sub-skills often stop navigation before doing their one
        // atomic operation.  Re-assert the traversal guard around that pause.
        context.citizen.setBracedPlacement(true);
        SkillResult result = sub.tick(subContext);
        if (result == SkillResult.RUNNING) return SkillResult.RUNNING;

        SkillFailure failure = subContext.failure;
        boolean ownsScaffold = Boolean.TRUE.equals(
                subContext.get("scaffoldOwned", true));
        cancelSub(context);
        context.citizen.setBracedPlacement(true);

        if (result == SkillResult.COMPLETED || isHarmless(failure)) {
            // Remember anything laid down purely to stand on, so it can be
            // taken back afterwards rather than left as a dirt tower.
            if (result == SkillResult.COMPLETED && ownsScaffold
                    && op.kind == TerrainPlan.OpKind.PLACE) {
                context.citizen.rememberScaffold(op.pos,
                        context.level.getBlockState(op.pos));
            }
            finishOperation(context);
            return SkillResult.RUNNING;
        }
        // Ran out of the chosen material: try another before giving up.
        if (failure != null && "MISSING_RESOURCE".equals(failure.code)) {
            String other = ScaffoldMaterial.chooseAny(context.citizen.getInventory(), 1);
            if (other != null && !other.equals(scaffold)) {
                scaffold = other;
                return SkillResult.RUNNING;
            }
            // The world may have supplied part of the route already.  Before
            // abandoning the operation, recover one real block from the ground
            // so a short climb does not deadlock on an empty inventory.
            SkillResult recovery = recoverScaffold(context, 1);
            if (recovery == SkillResult.RUNNING) return SkillResult.RUNNING;
            context.fail(SkillFailure.missing(
                    "out of material for the route and no earth within reach"));
            context.fail(failure);
            return SkillResult.FAILED;
        }
        return replan(context, failure);
    }

    private String blockStateFor(String material, String variant) {
        if (material == null) return null;
        if (variant != null && variant.startsWith("stair:")
                && material.endsWith("_stairs")) {
            return material + "[facing=" + variant.substring("stair:".length()) + "]";
        }
        return material;
    }

    private boolean beginOperation(SkillContext context, TerrainPlan.Op op) {
        CitizenTaskParams params = new CitizenTaskParams();
        params.position = new int[]{op.pos.getX(), op.pos.getY(), op.pos.getZ()};
        SkillType type;
        if (op.kind == TerrainPlan.OpKind.DIG) {
            type = SkillType.MINE_BLOCK;
            params.extra.put("workAnimation", "mine");
        } else {
            type = SkillType.PLACE_BLOCK;
            String material = scaffold;
            if (op.variant.startsWith("stair:")) {
                String stair = ScaffoldMaterial.chooseStair(
                        context.citizen.getInventory(), 1);
                if (stair != null) material = stair;
            } else if (material.endsWith("_stairs")) {
                String full = ScaffoldMaterial.choose(context.citizen.getInventory(), 1);
                if (full != null) material = full;
            }
            params.block = blockStateFor(material, op.variant);
            params.extra.put("item", material);
            params.extra.put("workAnimation", "place");
            if (stepIndex < plan.steps().size()
                    && plan.steps().get(stepIndex).move == TerrainPlan.MoveKind.PILLAR_UP) {
                params.extra.put("pillar", "true");
            }
        }
        subContext = new SkillContext(context.citizen, context.level, context.navigator, params);
        subContext.timeoutTicks = context.timeoutTicks;
        subContext.startGameTime = context.level.getGameTime();

        sub = SkillRegistry.create(type);
        if (!sub.canStart(subContext)) {
            sub = null;
            context.fail(new SkillFailure("INVALID_TASK",
                    type + " rejected the terrain operation at " + op.pos, true));
            return false;
        }
        sub.start(subContext);
        return true;
    }

    /** Advance past the finished operation and reset the per-step timeout window. */
    private void finishOperation(SkillContext context) {
        opIndex++;
        context.startGameTime = context.level.getGameTime();
    }

    private boolean isSatisfied(SkillContext context, TerrainPlan.Op op) {
        if (op.kind == TerrainPlan.OpKind.DIG) {
            return context.level.getBlockState(op.pos).isAir();
        }
        // A pillar support must go through PlaceBlockSkill even when another
        // worker already installed a block: the atomic raise still has to be
        // validated and performed. Plain bridge/deck cells may be adopted.
        if (stepIndex < plan.steps().size()
                && plan.steps().get(stepIndex).move == TerrainPlan.MoveKind.PILLAR_UP) {
            return false;
        }
        return !context.level.getBlockState(op.pos).canBeReplaced();
    }

    /** Failures that simply mean "someone else already did it". */
    private static boolean isHarmless(SkillFailure failure) {
        if (failure == null) return false;
        return "BLOCK_ALREADY_MINED".equals(failure.code)
                || "POSITION_OCCUPIED".equals(failure.code);
    }

    /** Walk back to the cell the current step is performed from. */
    private SkillResult approachWorkSite(SkillContext context) {
        BlockPos from = stepIndex == 0 ? planOrigin : plan.steps().get(stepIndex - 1).feet;
        if (walkTarget == null || !walkTarget.equals(from)) {
            walkTarget = from;
            context.navigator.stop();
            context.navigator.requestSafeStep();
            context.navigator.moveToStand(from, 1.0);
        }
        context.citizen.setBracedPlacement(true);
        context.navigator.tick();
        if (context.navigator.hasFailed()) {
            return replan(context, context.navigator.failure());
        }
        if (context.navigator.isArrived() && standingAt(context, from)) {
            walkTarget = null;
        }
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ walking

    private SkillResult walk(SkillContext context, List<TerrainPlan.Step> steps) {
        // Execute one planned transition at a time.  A geometrically nearby
        // FALL/DIG_DOWN/PILLAR_UP is not interchangeable with a WALK: accepting
        // it early can declare success while the worker is still airborne or
        // can let vanilla navigation choose a different ledge.
        TerrainPlan.Step step = steps.get(stepIndex);
        BlockPos destination = step.feet;
        context.citizen.setControlledDrop(step.move == TerrainPlan.MoveKind.FALL
                || step.move == TerrainPlan.MoveKind.DIG_DOWN);

        if (standingAt(context, destination)) {
            return arrive(context, stepIndex, steps);
        }

        if (walkTarget == null || !walkTarget.equals(destination)) {
            walkTarget = destination;
            context.navigator.stop();
            context.navigator.requestSafeStep();
            // A swim step has no ground to stand on by definition, so asking
            // navigation for a standable cell there can only fail.
            if (step.move == TerrainPlan.MoveKind.SWIM) {
                context.navigator.moveTo(destination, 1.0);
            } else {
                context.navigator.moveToStand(destination, 1.0);
            }
        }
        context.citizen.setBracedPlacement(true);
        context.navigator.tick();
        if (context.navigator.hasFailed()) {
            return replan(context, context.navigator.failure());
        }
        if (context.navigator.isArrived() && standingAt(context, destination)) {
            return arrive(context, stepIndex, steps);
        }
        return SkillResult.RUNNING;
    }

    /**
     * True when the citizen is standing on this exact step.
     *
     * <p>Asks whether a body fits here, not whether somebody could move in.
     * The occupancy form can never say yes about a cell the asker is already
     * standing in, so this check was permanently false and <em>every</em> step
     * of <em>every</em> route reported as not-yet-reached. The citizen would
     * walk onto the step, be told it had not arrived, walk again, and fail the
     * whole traverse after six repaths — which is why a log three blocks
     * overhead was unreachable and a felled tree always left its upper
     * branches standing.</p>
     */
    private boolean standingAt(SkillContext context, BlockPos feet) {
        return context.citizen.blockPosition().equals(feet)
                && context.citizen.onGround()
                && ai.minecivilization.navigation.PlacementSafety.canStandHere(
                context.level, context.citizen, feet);
    }

    private SkillResult arrive(SkillContext context, int reached, List<TerrainPlan.Step> steps) {
        stepIndex = reached + 1;
        opIndex = 0;
        walkTarget = null;
        context.navigator.stop();
        context.citizen.setControlledDrop(false);
        context.startGameTime = context.level.getGameTime();
        if (stepIndex < steps.size()) context.citizen.setBracedPlacement(true);
        if (stepIndex < steps.size()) return SkillResult.RUNNING;

        // End of this leg. Done if that was the destination, otherwise plan the
        // next leg from where we now stand.
        if (arrivedAtFinalGoal(context)) {
            context.citizen.setBracedPlacement(false);
            return SkillResult.COMPLETED;
        }
        plan = null;
        replans = 0;   // progress was made: the budget is for stuck routes only
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void cancel(SkillContext context) {
        cancelSub(context);
        context.navigator.stop();
        if (!context.citizen.isOnOwnedScaffold()) {
            context.citizen.setBracedPlacement(false);
        }
        context.citizen.setControlledDrop(false);
        plan = null;
        walkTarget = null;
    }

    private void cancelSub(SkillContext context) {
        if (sub != null && subContext != null) {
            sub.cancel(subContext);
        }
        sub = null;
        subContext = null;
    }

    @Override
    public String progressLabel(SkillContext context) {
        if (plan == null) return "planning a route";
        int total = plan.steps().size();
        TerrainPlan.MoveKind kind = stepIndex < total
                ? plan.steps().get(stepIndex).move : TerrainPlan.MoveKind.WALK;
        return kind + " " + Math.min(stepIndex + 1, total) + "/" + total;
    }
}
