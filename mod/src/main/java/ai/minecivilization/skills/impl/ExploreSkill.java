package ai.minecivilization.skills.impl;

import ai.minecivilization.colony.LandmarkRegistry;
import ai.minecivilization.colony.SignRegistry;
import ai.minecivilization.colony.ZoneManager;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.navigation.PlacementSafety;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import ai.minecivilization.storage.StorageDiscovery;
import ai.minecivilization.telemetry.ColonyEventLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * EXPLORE — walk out to somewhere the colony has not looked, and come back
 * knowing something.
 *
 * <p>A settlement that never leaves its own chunks runs out of trees, then
 * runs out of ideas. Exploration is what supplies the colony with new ore
 * seams, new species of tree, caves worth marking and, incidentally, the
 * long-distance journeys that become its roads.</p>
 *
 * <p>Deliberately modest: one leg out to a bounded distance, logging whatever
 * it passes. Everything it finds lands in the citizen's own resource memory
 * and in the colony's gazetteer, so the discovery outlives the trip.</p>
 */
public final class ExploreSkill implements CitizenSkill {

    /** How far out one expedition goes, in blocks from the town centre. */
    private static final int LEG_DISTANCE = 96;
    /** Hard ceiling, so "explore" never means "walk to the world border". */
    private static final int MAX_DISTANCE = 220;
    /** Horizontal radius scanned for anything worth reporting. */
    private static final int SURVEY_RADIUS = 5;
    /** Vertical reach of a survey — an expedition looks around, not down a shaft. */
    private static final int SURVEY_HEIGHT = 3;
    /**
     * Ticks between surveys.
     *
     * <p>Each survey inspects roughly eight hundred blocks and classifies every
     * one of them. Doing that every second, per explorer, is a real share of a
     * tick budget that has to stretch to a hundred citizens — and an expedition
     * moving at walking pace has not seen anything new in the meantime.</p>
     */
    private static final int SURVEY_INTERVAL = 40;

    private BlockPos target;
    private long lastSurveyAt;
    private int discoveries;

    @Override
    public SkillType type() {
        return SkillType.EXPLORE;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return true;
    }

    @Override
    public void start(SkillContext context) {
        discoveries = 0;
        lastSurveyAt = context.level.getGameTime();
        target = chooseTarget(context);
    }

    @Override
    public SkillResult tick(SkillContext context) {
        if (target == null) {
            context.fail(SkillFailure.notFound("nowhere left worth exploring"));
            return SkillResult.FAILED;
        }

        long now = context.level.getGameTime();
        if (now - lastSurveyAt >= SURVEY_INTERVAL) {
            lastSurveyAt = now;
            survey(context);
        }

        SkillResult arrival = SkillNavigation.approach(context, target, 16.0, "explore.walk");
        if (arrival == SkillResult.RUNNING) return SkillResult.RUNNING;
        if (arrival == SkillResult.FAILED) {
            // An expedition that cannot reach its waypoint has still explored
            // everything between here and there, which was the point.
            return discoveries > 0 ? SkillResult.COMPLETED : SkillResult.FAILED;
        }

        survey(context);
        ColonyEventLog.of(context.level).discovery(context.level,
                context.citizen.getIdentity().name,
                "returned from " + target.getX() + "," + target.getZ()
                        + " with " + discoveries + " find(s)");
        return SkillResult.COMPLETED;
    }

    // ------------------------------------------------------------------ surveying

    /**
     * Note everything nearby worth knowing about.
     *
     * <p>Ores, workstations, containers and signs all go into the colony's
     * shared memory; resources also go into this citizen's own so it can come
     * back for them.</p>
     */
    private void survey(SkillContext context) {
        ServerLevel level = context.level;
        CitizenEntity citizen = context.citizen;
        ai.minecivilization.livestock.AnimalSightings.glance(level, citizen);
        BlockPos at = citizen.blockPosition();
        LandmarkRegistry landmarks = LandmarkRegistry.get(level);

        for (BlockPos probe : BlockPos.betweenClosed(
                at.offset(-SURVEY_RADIUS, -SURVEY_HEIGHT, -SURVEY_RADIUS),
                at.offset(SURVEY_RADIUS, SURVEY_HEIGHT, SURVEY_RADIUS))) {
            if (!level.isLoaded(probe)) continue;
            BlockState state = level.getBlockState(probe);
            if (state.isAir()) continue;

            var key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            if (key == null) continue;
            String id = key.toString();

            if (isWorthRemembering(id)) {
                citizen.onResourceFound(state.getBlock(), probe.immutable());
                discoveries++;
            }
            if (landmarks.notice(level, probe.immutable())) discoveries++;
            if (StorageDiscovery.isStorageBlock(level, probe)) {
                StorageDiscovery.onContainerPlaced(level, probe.immutable());
            }
            // A sign found in the wild is the colony learning something from
            // whoever wrote it — including the player.
            if (id.endsWith("_sign") || id.endsWith("_wall_sign")) {
                SignRegistry.get(level).readFromWorld(level, probe.immutable());
            }
        }
    }

    /** Blocks the colony will want to come back for. */
    private static boolean isWorthRemembering(String blockId) {
        return blockId.endsWith("_ore") || blockId.equals("minecraft:ancient_debris")
                || blockId.endsWith("_log") || blockId.endsWith("_sapling")
                || blockId.equals("minecraft:clay") || blockId.equals("minecraft:sand")
                || blockId.equals("minecraft:gravel") || blockId.equals("minecraft:coal_block")
                || blockId.equals("minecraft:obsidian");
    }

    // ------------------------------------------------------------------ direction

    /**
     * Somewhere outward the colony has not been.
     *
     * <p>Each citizen leans in its own direction, derived from its id, so a
     * colony that sends three people exploring covers three arcs rather than
     * three copies of the same walk.</p>
     */
    private static BlockPos chooseTarget(SkillContext context) {
        ServerLevel level = context.level;
        CitizenEntity citizen = context.citizen;

        int[] given = context.params.position;
        if (given != null && given.length == 3) {
            return new BlockPos(given[0], given[1], given[2]);
        }

        BlockPos centre = ZoneManager.get(level).townCenter(level);
        BlockPos at = citizen.blockPosition();
        // Spread the compass across the population, then rotate slowly over
        // time so repeat expeditions do not retrace the last one exactly.
        double bearing = (Math.floorMod(citizen.getId() * 47L, 360L)
                + level.getGameTime() / 400.0) * Math.PI / 180.0;

        for (int distance = LEG_DISTANCE; distance <= MAX_DISTANCE; distance += 32) {
            int x = centre.getX() + (int) Math.round(Math.cos(bearing) * distance);
            int z = centre.getZ() + (int) Math.round(Math.sin(bearing) * distance);
            if (!level.isLoaded(new BlockPos(x, at.getY(), z))) {
                // Unloaded is exactly what "unexplored" means — go there. Its
                // height is unknown until the chunk loads (the heightmap of an
                // unloaded column reads as the bottom of the world, and
                // explorers used to plan routes down to bedrock), so aim at
                // our own height; the journey is walked in legs anyway.
                return new BlockPos(x, at.getY(), z);
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (PlacementSafety.canStand(level, citizen, candidate)
                    && candidate.distSqr(at) > 32 * 32) {
                return candidate;
            }
        }
        return centre;
    }

    @Override
    public void cancel(SkillContext context) {
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        if (target == null) return "choosing a direction";
        int distance = (int) Math.sqrt(context.citizen.blockPosition().distSqr(target));
        return "exploring toward " + target.getX() + "," + target.getZ()
                + " (" + distance + "m, " + discoveries + " found)";
    }
}
