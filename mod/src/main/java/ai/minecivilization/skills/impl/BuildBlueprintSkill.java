package ai.minecivilization.skills.impl;

import java.util.Optional;

import ai.minecivilization.citizen.CitizenTaskParams;
import ai.minecivilization.construction.ConstructionManager;
import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.navigation.PlacementSafety;
import ai.minecivilization.navigation.PlacementSupport;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillRegistry;
import ai.minecivilization.skills.SkillType;
import ai.minecivilization.storage.StorageManager;
import ai.minecivilization.storage.StorageNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * BUILD_BLUEPRINT — advance a construction project block by block:
 * find next required block → check inventory → (missing: fail
 * MISSING_MATERIALS so cognition can arrange gathering/withdrawal) →
 * navigate → validate support → place (consuming the item) → repeat.
 *
 * <p>NO creative pasting. Every placed block comes from the citizen's
 * inventory.</p>
 */
public final class BuildBlueprintSkill implements CitizenSkill {
    private CitizenSkill siteMiner;
    private SkillContext siteContext;
    private SkillContext siteClaimContext;

    @Override
    public SkillType type() {
        return SkillType.BUILD_BLUEPRINT;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return true;
    }

    @Override
    public void start(SkillContext context) {
        cancelSiteMiner();
        context.put("checkedStorage", false);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        ConstructionProject project = ConstructionManager.resolve(
                context.level, context.params.projectId, context.citizen.blockPosition());
        if (project == null) {
            context.fail(SkillFailure.notFound("no active construction project"));
            return SkillResult.FAILED;
        }
        ConstructionManager manager = ConstructionManager.get(context.level);
        // Rebuild the blueprint from its id when this session has not generated
        // it yet, which is the normal state of affairs after a world reload.
        var blueprint = ConstructionManager.ensureBlueprint(context.level, project.blueprintId);
        if (blueprint == null) {
            // Genuinely unbuildable. Retire the project rather than handing it
            // back to the next citizen to fail on in a tenth of a second.
            project.status = ConstructionProject.Status.FAILED;
            manager.setDirty();
            context.fail(new SkillFailure("UNKNOWN_BLUEPRINT",
                    "blueprint " + project.blueprintId + " cannot be rebuilt", false));
            return SkillResult.FAILED;
        }

        manager.reconcile(context.level, project, blueprint);
        if (project.isFinished(blueprint)) {
            project.status = ConstructionProject.Status.COMPLETED;
            manager.setDirty();
            return SkillResult.COMPLETED;
        }

        // Place whatever this builder can supply now, not strictly the next
        // block in blueprint order: one missing chest used to stop a house.
        java.util.Map<String, Integer> stock = storeTotals(context);
        java.util.function.Predicate<String> available = blockId -> {
            String item = itemFor(context, blockId);
            for (String variant : ai.minecivilization.construction.WoodSwap.variants(item)) {
                if (context.citizen.getInventory().count(variant) > 0
                        || stock.getOrDefault(variant, 0) > 0) return true;
            }
            return false;
        };
        Optional<ConstructionManager.NextStep> step = manager.nextStep(context.level, project, available);
        if (step.isPresent() && step.get().missingMaterial) {
            project.status = ConstructionProject.Status.WAITING_FOR_RESOURCES;
            manager.setDirty();
            // Ask for what the building needs most; a courier brings it while
            // this builder gets on with something else.
            String most = null;
            int mostCount = 0;
            for (var entry : ai.minecivilization.work.ConstructionSupply
                    .remainingMaterials(context.level, project).entrySet()) {
                if (entry.getValue() > mostCount) {
                    mostCount = entry.getValue();
                    most = itemFor(context, entry.getKey());
                }
            }
            if (most != null) {
                ai.minecivilization.work.MaterialRequests.post(context.citizen.getUUID(), most,
                        Math.min(32, mostCount), "for " + project.name, context.level.getGameTime());
            }
            context.fail(SkillFailure.missing("nothing left on " + project.name
                    + " that the stores or this builder can supply"));
            return SkillResult.FAILED;
        }
        if (step.isEmpty()) {
            context.fail(new SkillFailure("PROJECT_BLOCKED",
                    "all remaining blocks lack support", true));
            return SkillResult.FAILED;
        }
        ConstructionManager.NextStep next = step.get();
        if (next.clearExisting) {
            return prepareNaturalSite(context, manager, project, next.pos);
        }
        if (next.unsupported) {
            // A floor over a dip in the ground: lay the foundation the
            // blueprint assumed. Without this, every block over a hole was
            // "deferred" forever and the building could never be finished.
            if (next.pos != null && next.detail != null && next.detail.startsWith("missing support")) {
                SkillResult shored = shoreUp(context, next.pos);
                if (shored != null) return shored;
            }
            context.fail(new SkillFailure("UNSUPPORTED_BLOCK", next.detail, true));
            return SkillResult.FAILED;
        }

        BlockState state = ConstructionManager.parseState(context.level, next.blockState);
        if (state == null) {
            context.fail(new SkillFailure("INVALID_BLOCK_STATE", next.blockState, false));
            return SkillResult.FAILED;
        }
        BlockState existing = context.level.getBlockState(next.pos);
        if (ConstructionManager.matches(state, existing)) {
            // A world save or another worker may already have completed this
            // exact step. Adopt it instead of trying to overwrite it.
            String existingKey = project.key(next.pos.getX(), next.pos.getY(), next.pos.getZ());
            project.placed.add(existingKey);
            project.ownedCells.add(existingKey);
            manager.setDirty();
            return micro(context) ? SkillResult.COMPLETED : SkillResult.RUNNING;
        }
        if (!existing.canBeReplaced()) {
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "blueprint cell is occupied by a different block", true));
            return SkillResult.FAILED;
        }
        if (!PlacementSafety.canOccupy(context.level, context.citizen, next.pos, false)) {
            SkillResult reposition = leaveOccupiedCell(context, next.pos);
            if (reposition == SkillResult.RUNNING) return reposition;
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "blueprint cell intersects a living entity", true));
            return SkillResult.FAILED;
        }
        if (!PlacementSupport.canPlace(context.level, state, next.pos)) {
            context.fail(new SkillFailure("UNSUPPORTED_BLOCK",
                    "blueprint block has no physical support at " + next.pos, true));
            return SkillResult.FAILED;
        }
        if (ai.minecivilization.construction.AnimalPen.isPen(project.blueprintId)) {
            if (existing.is(state.getBlock())) {
                String penKey = project.key(next.pos.getX(), next.pos.getY(), next.pos.getZ());
                project.placed.add(penKey);
                project.ownedCells.add(penKey);
                manager.setDirty();
                return SkillResult.RUNNING;
            }
            if (!existing.canBeReplaced() || !context.level.getFluidState(next.pos).isEmpty()) {
                context.fail(new SkillFailure("PEN_OBSTRUCTED", "pen construction must not overwrite existing structures", true));
                return SkillResult.FAILED;
            }
        }
        Item blockItem = net.minecraft.world.item.BlockItem.byBlock(state.getBlock());
        if (blockItem == net.minecraft.world.item.Items.AIR) {
            context.fail(new SkillFailure("INVALID_BLOCK_STATE",
                    "no item for " + next.blockState, false));
            return SkillResult.FAILED;
        }
        String itemId = CitizenInventory.idOf(new ItemStack(blockItem));
        // Build in the wood the colony has. The blueprint's species is a
        // preference: an oak house in a savanna goes up in acacia.
        if (ai.minecivilization.construction.WoodSwap.isWooden(itemId)
                && !context.citizen.getInventory().containsAtLeast(itemId, 1)) {
            for (String variant : ai.minecivilization.construction.WoodSwap.variants(itemId)) {
                if (context.citizen.getInventory().count(variant) > 0
                        || stock.getOrDefault(variant, 0) > 0) {
                    String species = ai.minecivilization.construction.WoodSwap.speciesOf(variant);
                    BlockState swapped = ConstructionManager.inSpecies(state, species);
                    Item swappedItem = net.minecraft.world.item.BlockItem.byBlock(swapped.getBlock());
                    if (swappedItem == net.minecraft.world.item.Items.AIR) continue;
                    state = swapped;
                    blockItem = swappedItem;
                    itemId = CitizenInventory.idOf(new ItemStack(blockItem));
                    break;
                }
            }
        }

        // Missing materials: walk to storage and withdraw. "Still walking" is
        // not the same answer as "the material is absent"; the old boolean
        // helper failed the build on the first tick of every long walk.
        if (!context.citizen.getInventory().containsAtLeast(itemId, 1)) {
            SkillResult withdrawal = tryWithdrawFromStorage(context, itemId);
            if (withdrawal == SkillResult.RUNNING) return withdrawal;
            if (withdrawal == SkillResult.FAILED) return SkillResult.FAILED;
            if (!context.citizen.getInventory().containsAtLeast(itemId, 1)) {
                project.status = ConstructionProject.Status.WAITING_FOR_RESOURCES;
                manager.setDirty();
                context.fail(SkillFailure.missing(
                        "need " + itemId + " for " + project.name));
                return SkillResult.FAILED;
            }
        }

        project.status = ConstructionProject.Status.BUILDING;
        manager.setDirty();

        double distSqr = context.citizen.distanceToSqr(
                next.pos.getX() + 0.5, next.pos.getY() + 0.5, next.pos.getZ() + 0.5);
        if (distSqr > 25.0) {
            context.citizen.clearWorkAnimation();
            SkillResult arrival = SkillNavigation.approach(context, next.pos, 25.0, "build.walk");
            if (arrival != SkillResult.COMPLETED) return arrival;
        }
        context.navigator.stop();

        // Physically place: claim the cell, consume exactly one item, and refund
        // it if the world rejects the write.  A project must never advance on a
        // phantom block or let two workers race the same cell.
        if (!claimBuild(context, next.pos, "build")) {
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "another worker already claimed this blueprint cell", true));
            return SkillResult.FAILED;
        }
        if (context.citizen.getInventory().extract(itemId, 1) != 1) {
            releaseBuild(context, "build");
            context.fail(SkillFailure.missing("need " + itemId + " for " + project.name));
            return SkillResult.FAILED;
        }
        boolean wrote = context.level.setBlock(next.pos, state, 3);
        if (!wrote || !ConstructionManager.matches(state, context.level.getBlockState(next.pos))) {
            if (wrote) {
                context.level.setBlock(next.pos, existing, 3);
            }
            context.citizen.getInventory().insert(new ItemStack(blockItem));
            releaseBuild(context, "build");
            context.fail(new SkillFailure("BLOCK_PLACE_FAILED",
                    "the world rejected blueprint block at " + next.pos, true));
            return SkillResult.FAILED;
        }
        context.citizen.animateAction(WorkAnimation.BUILD, next.pos);
        // A blueprint that includes chests registers them as it raises them.
        ai.minecivilization.storage.StorageDiscovery.onContainerPlaced(context.level, next.pos);
        ai.minecivilization.colony.LandmarkRegistry.get(context.level)
                .notice(context.level, next.pos);
        String placedKey = project.key(next.pos.getX(), next.pos.getY(), next.pos.getZ());
        project.placed.add(placedKey);
        project.ownedCells.add(placedKey);
        releaseBuild(context, "build");
        context.citizen.getSkills().addXp("building", 0.05f);
        context.citizen.onBlockPlaced(itemId);
        manager.setDirty();
        // each placed block resets the per-attempt timeout window: a long build
        // is a sequence of healthy steps, not a hung skill
        context.startGameTime = context.level.getGameTime();

        context.citizen.onProjectProgress(project);
        manager.reconcile(context.level, project, blueprint);
        if (project.isFinished(blueprint)) {
            project.status = ConstructionProject.Status.COMPLETED;
            manager.setDirty();
            // A blueprint can place a sign but cannot say anything on it.
            ai.minecivilization.colony.DistrictMarker.stamp(context.level, project);
            context.citizen.onProjectCompleted(project);
            return SkillResult.COMPLETED;
        }
        if (micro(context)) return SkillResult.COMPLETED;
        return SkillResult.RUNNING;
    }

    private SkillResult prepareNaturalSite(SkillContext context, ConstructionManager manager,
                                           ConstructionProject project, BlockPos target) {
        if (siteMiner != null) {
            SkillResult result = siteMiner.tick(siteContext);
            if (result == SkillResult.RUNNING) return SkillResult.RUNNING;
            SkillFailure failure = siteContext.failure;
            cancelSiteMiner();
            if (result != SkillResult.COMPLETED) {
                context.fail(failure == null ? new SkillFailure("SITE_CLEAR_FAILED",
                        "could not clear the construction site", true) : failure);
                return SkillResult.FAILED;
            }
            context.startGameTime = context.level.getGameTime();
            return SkillResult.RUNNING;
        }

        if (!manager.ownsCell(project.id, target)
                || !ConstructionManager.isNaturalSiteBlock(context.level,
                context.level.getBlockState(target), target)) {
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "construction site is occupied by a protected or non-natural block", true));
            return SkillResult.FAILED;
        }
        if (target.equals(context.citizen.blockPosition())) {
            return leaveOccupiedCell(context, target);
        }
        if (!context.level.getEntities(context.citizen,
                new net.minecraft.world.phys.AABB(target)).isEmpty()) {
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "a living entity is inside the construction site cell", true));
            return SkillResult.FAILED;
        }
        double distance = context.citizen.distanceToSqr(target.getX() + 0.5,
                target.getY() + 0.5, target.getZ() + 0.5);
        if (distance > 20.0) {
            BlockPos stand = findSiteStand(context, target);
            context.citizen.clearWorkAnimation();
            // A block buried in a hillside has nowhere to stand beside it yet:
            // walk (or dig) up to the block itself instead of giving up on the
            // whole building — fourteen failures a session came from this.
            return SkillNavigation.approach(context, stand != null ? stand : target, 20.0,
                    "build.prepare-site");
        }

        if (!claimBuild(context, target, "site")) {
            context.fail(new SkillFailure("POSITION_OCCUPIED",
                    "another worker already claimed this construction site cell", true));
            return SkillResult.FAILED;
        }
        siteClaimContext = context;
        CitizenTaskParams params = new CitizenTaskParams();
        params.position = new int[]{target.getX(), target.getY(), target.getZ()};
        params.projectId = project.id;
        params.extra.put("authorizedProject", project.id);
        params.extra.put("workAnimation", "mine");
        siteContext = new SkillContext(context.citizen, context.level, context.navigator, params);
        siteContext.timeoutTicks = context.timeoutTicks;
        siteContext.startGameTime = context.level.getGameTime();
        siteMiner = SkillRegistry.create(SkillType.MINE_BLOCK);
        siteMiner.start(siteContext);
        return SkillResult.RUNNING;
    }

    private BlockPos findSiteStand(SkillContext context, BlockPos target) {
        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy : new int[]{0, 1, -1, 2, -2, 3}) {
                        BlockPos stand = target.offset(dx, dy, dz);
                        if (levelLoaded(context, stand)
                                && PlacementSafety.canStand(context.level, context.citizen, stand)) {
                            return stand;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean levelLoaded(SkillContext context, BlockPos pos) {
        return context.level.isLoaded(pos);
    }

    private boolean claimBuild(SkillContext context, BlockPos pos, String kind) {
        String owner = context.citizen.getIdentity().citizenId.toString();
        boolean claimed = ai.minecivilization.construction.WorkClaimStore.claim(
                context.level, owner, pos, kind, context.level.getGameTime(), 240);
        if (claimed) context.put("claim." + kind, pos);
        return claimed;
    }

    private void releaseBuild(SkillContext context, String kind) {
        BlockPos pos = context.get("claim." + kind, (BlockPos) null);
        if (pos == null) return;
        String owner = context.citizen.getIdentity().citizenId.toString();
        ai.minecivilization.construction.WorkClaimStore.release(
                context.level, owner, pos, kind);
        context.data.remove("claim." + kind);
    }

    private void cancelSiteMiner() {
        if (siteMiner != null && siteContext != null) siteMiner.cancel(siteContext);
        if (siteContext != null) releaseBuild(siteContext, "site");
        if (siteClaimContext != null) releaseBuild(siteClaimContext, "site");
        siteMiner = null;
        siteContext = null;
        siteClaimContext = null;
    }

    private static boolean micro(SkillContext context) {
        return Boolean.parseBoolean(context.params.extra.getOrDefault("micro", "false"));
    }

    private SkillResult leaveOccupiedCell(SkillContext context, BlockPos target) {
        BlockPos current = context.citizen.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stand = current.relative(direction);
            if (!PlacementSafety.canStand(context.level, context.citizen, stand)) continue;
            // The chosen cell lives under its own key: the navigation key below
            // holds the traversal in progress, and sharing one key between a
            // BlockPos and a skill crashed the server with a ClassCastException.
            BlockPos requested = context.get("build.reposition.target", (BlockPos) null);
            if (!stand.equals(requested)) {
                context.put("build.reposition.target", stand);
                SkillResult navigation = SkillNavigation.approach(context, stand, 1.5,
                        "build.reposition");
                if (navigation == SkillResult.FAILED) {
                    context.failure = null;
                    context.data.remove("build.reposition");
                    context.data.remove("build.reposition.ctx");
                    context.data.remove("build.reposition.target");
                    continue;
                }
                return SkillResult.RUNNING;
            }
            if (current.equals(stand)) return SkillResult.RUNNING;
        }
        return SkillResult.FAILED;
    }

    /**
     * Fill the gap under an unsupported blueprint block, from the bottom up,
     * with plain earth or stone from the pack.
     *
     * @return RUNNING while working on it, or null when this cannot help
     */
    private SkillResult shoreUp(SkillContext context, BlockPos unsupported) {
        BlockPos cell = unsupported.below();
        // Find the lowest open cell of the gap (at most six deep).
        BlockPos lowest = null;
        for (int depth = 0; depth < 6; depth++) {
            BlockState state = context.level.getBlockState(cell);
            if (!state.canBeReplaced()) break;
            lowest = cell;
            cell = cell.below();
        }
        if (lowest == null) return null;
        if (!context.level.getBlockState(lowest.below())
                .isFaceSturdy(context.level, lowest.below(), Direction.UP)) return null;
        var manager = ConstructionManager.get(context.level);
        if (manager.protectsCell(lowest)) return null;
        String material = ai.minecivilization.navigation.ScaffoldMaterial.choose(
                context.citizen.getInventory(), 1);
        if (material == null || material.endsWith("_log") || material.endsWith("_planks")) return null;

        if (context.citizen.distanceToSqr(lowest.getX() + 0.5, lowest.getY() + 0.5,
                lowest.getZ() + 0.5) > 25.0) {
            SkillResult arrival = SkillNavigation.approach(context, lowest, 25.0, "build.foundation");
            return arrival == SkillResult.FAILED ? null : SkillResult.RUNNING;
        }
        if (!PlacementSafety.canOccupy(context.level, context.citizen, lowest, false)) return null;
        var item = CitizenInventory.itemById(material);
        if (!(item instanceof net.minecraft.world.item.BlockItem blockItem)) return null;
        if (context.citizen.getInventory().extract(material, 1) != 1) return null;
        if (!context.level.setBlock(lowest, blockItem.getBlock().defaultBlockState(), 3)) {
            context.citizen.getInventory().insert(new ItemStack(item));
            return null;
        }
        context.citizen.animateAction(WorkAnimation.BUILD, lowest);
        context.startGameTime = context.level.getGameTime();
        return SkillResult.RUNNING;
    }

    /** What the stores near the builder hold, refreshed every few seconds. */
    private static java.util.Map<String, Integer> storeTotals(SkillContext context) {
        long now = context.level.getGameTime();
        Long at = context.get("stock.at", (Long) null);
        java.util.Map<String, Integer> cached = context.get("stock", (java.util.Map<String, Integer>) null);
        if (cached == null || at == null || now - at > 100) {
            cached = ai.minecivilization.storage.SettlementStock.totals(context.level,
                    context.citizen.blockPosition());
            context.put("stock", cached);
            context.put("stock.at", now);
        }
        return cached;
    }

    /** The item that places a blueprint block id (a wall torch is placed with a torch). */
    private static String itemFor(SkillContext context, String blockId) {
        var block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse(blockId));
        if (block == null) return blockId;
        Item item = net.minecraft.world.item.BlockItem.byBlock(block);
        if (item == net.minecraft.world.item.Items.AIR) item = block.asItem();
        return item == net.minecraft.world.item.Items.AIR ? blockId
                : CitizenInventory.idOf(new ItemStack(item));
    }

    private SkillResult tryWithdrawFromStorage(SkillContext context, String itemId) {
        StorageNode storage = StorageManager.nearestWith(context.level,
                context.citizen.blockPosition(), itemId, 1);
        if (storage == null) return SkillResult.COMPLETED;
        var be = context.level.getBlockEntity(storage.containerPos());
        if (!(be instanceof net.minecraft.world.Container container)) return SkillResult.COMPLETED;

        double distSqr = context.citizen.distanceToSqr(storage.containerPos().getX() + 0.5,
                storage.containerPos().getY() + 0.5, storage.containerPos().getZ() + 0.5);
        if (distSqr > 12.0) {
            context.citizen.clearWorkAnimation();
            SkillResult arrival = SkillNavigation.approach(context, storage.containerPos(),
                    12.0, "build.withdraw");
            if (arrival != SkillResult.COMPLETED) return arrival;
        }
        context.navigator.stop();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || !CitizenInventory.idOf(stack).equals(itemId)) continue;
            int leftover = context.citizen.getInventory().insert(stack.copy());
            int taken = stack.getCount() - leftover;
            if (taken > 0) {
                stack.shrink(taken);
                if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                container.setChanged();
                return SkillResult.COMPLETED;
            }
        }
        return SkillResult.COMPLETED;
    }

    @Override
    public void cancel(SkillContext context) {
        cancelSiteMiner();
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        ConstructionProject p = ConstructionManager.resolve(context.level,
                context.params.projectId, context.citizen.blockPosition());
        if (p == null) return "building";
        var bp = ConstructionManager.blueprint(p.blueprintId);
        return "building " + p.name + " " + (bp == null ? "?" : String.format("%.0f%%",
                p.progress(bp) * 100));
    }
}
