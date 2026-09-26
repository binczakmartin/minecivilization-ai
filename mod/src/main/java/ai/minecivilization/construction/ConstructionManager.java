package ai.minecivilization.construction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ai.minecivilization.navigation.PlacementSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * World-saved construction projects + blueprint catalog access, plus the camp
 * anchor (the player's bed) that auto-planned house rows grow from.
 * V1 ships the built-in Starter Warehouse blueprint; imported schematics
 * register additional blueprints here (memory only, cataloged in the AI DB).
 */
public final class ConstructionManager extends SavedData {
    private static final String KEY = "minecivilization_construction";

    private final Map<String, ConstructionProject> projects = new LinkedHashMap<>();
    private static final Map<String, Blueprint> BLUEPRINTS = new LinkedHashMap<>();

    /** Where the camp is anchored — the player's bed — until one is found. */
    @Nullable
    private BlockPos campAnchor;

    static {
        registerBlueprint(StarterWarehouse.create());
        registerBlueprint(StarterHouse.create());
        registerBlueprint(AnimalPen.create());
        for (String wood : java.util.List.of("spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove"))
            registerBlueprint(AnimalPen.create(wood));
    }

    public static void registerBlueprint(Blueprint blueprint) {
        BLUEPRINTS.put(blueprint.id, blueprint);
    }

    public static Blueprint blueprint(String id) {
        return BLUEPRINTS.get(id);
    }

    /**
     * Resolve a blueprint, rebuilding it from its id if this session has not
     * generated it yet.
     *
     * <p>Projects are saved with the world; procedural blueprints are not —
     * they live in a static map that is empty on every start and filled only
     * as a generator happens to be called. A project loaded from disk therefore
     * routinely names a blueprint nothing has registered, and the builder fails
     * with a <em>fatal</em> {@code UNKNOWN_BLUEPRINT} it can never recover
     * from. In one session that was sixteen hundred failures — the colony's
     * entire construction effort — because nothing ever rebuilt
     * {@code marker_oak} after a reload.</p>
     *
     * <p>Every generated blueprint id encodes what it is, so every one of them
     * can be regenerated. This is the single place that knows how, and the
     * only thing callers should use.</p>
     */
    public static Blueprint ensureBlueprint(ServerLevel level, String id) {
        if (id == null || id.isEmpty()) return null;
        Blueprint existing = BLUEPRINTS.get(id);
        if (existing != null) return existing;

        String markerPrefix = ai.minecivilization.colony.DistrictMarker.ID_PREFIX;
        if (id.startsWith(markerPrefix)) {
            return ai.minecivilization.colony.DistrictMarker.blueprint(
                    id.substring(markerPrefix.length()));
        }
        if (id.startsWith(TownHall.ID_PREFIX)) {
            return TownHall.create(id.substring(TownHall.ID_PREFIX.length()));
        }
        if (id.startsWith(ai.minecivilization.architecture.HouseCatalog.ID_PREFIX)) {
            return ai.minecivilization.architecture.HouseCatalog.ensureRegistered(level, id);
        }
        if (ai.minecivilization.architecture.TownSquare.isSquare(id)) {
            Blueprint square = ai.minecivilization.architecture.TownSquare.create(id);
            registerBlueprint(square);
            return square;
        }
        if (ai.minecivilization.architecture.ModularHouse.isHome(id)) {
            Blueprint home = ai.minecivilization.architecture.ModularHouse.create(id);
            if (home != null) registerBlueprint(home);
            return home;
        }
        if (AnimalPen.isPen(id)) {
            // Pens are registered statically for the woods the mod knows, so a
            // miss here means a wood it does not — rebuild it from the id.
            int underscore = id.lastIndexOf('_');
            if (underscore > 0) {
                return AnimalPen.create(id.substring(underscore + 1));
            }
        }
        return null;
    }

    /**
     * Rebuild every blueprint the world's projects refer to.
     *
     * <p>Called once when the colony first ticks, so a builder never meets an
     * unregistered blueprint in the first place, and so a project whose
     * blueprint genuinely cannot be rebuilt is retired instead of being
     * offered to citizens forever.</p>
     *
     * @return how many projects were retired as unbuildable
     */
    public int reconcileBlueprints(ServerLevel level) {
        int retired = 0;
        for (ConstructionProject project : projects.values()) {
            if (project.status == ConstructionProject.Status.FAILED) continue;
            if (ensureBlueprint(level, project.blueprintId) != null) continue;
            project.status = ConstructionProject.Status.FAILED;
            retired++;
        }
        if (retired > 0) setDirty();
        return retired;
    }

    public static List<Blueprint> blueprints() {
        return new ArrayList<>(BLUEPRINTS.values());
    }

    public static ConstructionManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ConstructionManager::new, ConstructionManager::load, null),
                KEY);
    }

    public ConstructionProject createProject(String name, String blueprintId,
                                             int originX, int originY, int originZ,
                                             long gameTime) {
        Blueprint blueprint = BLUEPRINTS.get(blueprintId);
        ConstructionProject project = new ConstructionProject(
                UUID.randomUUID().toString().substring(0, 8), name, blueprintId,
                originX, originY, originZ);
        project.createdAtGameTime = gameTime;
        project.status = blueprint == null ? ConstructionProject.Status.FAILED
                : ConstructionProject.Status.PLANNED;
        projects.put(project.id, project);
        setDirty();
        return project;
    }

    /** Drop a project that never started, so its plot can be planned again. */
    public void remove(ConstructionProject project) {
        if (project == null) return;
        projects.remove(project.id);
        setDirty();
    }

    public ConstructionProject byId(String id) {
        return projects.get(id);
    }

    public List<ConstructionProject> all() {
        return new ArrayList<>(projects.values());
    }

    /**
     * The camp anchor — normally the player's bed — remembered in save data so
     * the house row still resolves while no player is online. Null until a bed
     * has been seen; the world spawn is used before that.
     */
    @Nullable
    public BlockPos campAnchor() {
        return campAnchor;
    }

    /** Remember the camp anchor (called whenever a valid bed is found). */
    public void recordCampAnchor(BlockPos pos) {
        if (pos.equals(campAnchor)) return;
        campAnchor = pos;
        setDirty();
    }

    /**
     * Move a project that owns no placed blocks yet — the camp re-homes when
     * its anchor moves (spawn row → player's bed). Projects with progress are
     * never moved: their blocks would be left behind.
     */
    public void relocate(ConstructionProject project, int originX, int originY, int originZ) {
        if (!project.placed.isEmpty() || !project.ownedCells.isEmpty()) return;
        if (project.originX == originX && project.originY == originY
                && project.originZ == originZ) return;
        project.originX = originX;
        project.originY = originY;
        project.originZ = originZ;
        setDirty();
    }

    /**
     * Where the settlement camp grows: the player's bed while one still
     * stands, otherwise the last bed remembered (covers the player being
     * offline), otherwise the world spawn for a world without a bed yet.
     * Every house row is planned against this anchor.
     */
    public BlockPos resolveAnchor(ServerLevel level) {
        if (campAnchor != null && isBedAt(level, campAnchor)) return campAnchor;

        BlockPos bed = findPlayerBed(level);
        if (bed != null) {
            recordCampAnchor(bed);
            return bed;
        }
        return campAnchor != null ? campAnchor : level.getSharedSpawnPos();
    }

    /**
     * The respawn point of an online player in this level, but only while a
     * real bed block is still under it — /spawnpoint marks, destroyed beds and
     * respawn anchors in other dimensions are ignored. Stops at the first hit:
     * one bed anchors the whole camp.
     */
    @Nullable
    private static BlockPos findPlayerBed(ServerLevel level) {
        var server = level.getServer();
        if (server == null) return null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!level.dimension().equals(player.getRespawnDimension())) continue;
            BlockPos respawn = player.getRespawnPosition();
            if (respawn != null && isBedAt(level, respawn)) return respawn;
        }
        return null;
    }

    /** A respawn point names one half of the bed; the other half counts too. */
    private static boolean isBedAt(ServerLevel level, BlockPos pos) {
        return isBed(level, pos)
                || isBed(level, pos.offset(1, 0, 0))
                || isBed(level, pos.offset(-1, 0, 0))
                || isBed(level, pos.offset(0, 0, 1))
                || isBed(level, pos.offset(0, 0, -1));
    }

    private static boolean isBed(ServerLevel level, BlockPos pos) {
        // Never force-load the bed's chunk just to look at it: an unloaded
        // anchor reads as "no bed here", so the caller keeps its fallback.
        return level.isLoaded(pos)
                && level.getBlockState(pos).getBlock() instanceof BedBlock;
    }

    /** Nearest project that still needs building. */
    public ConstructionProject nearestActive(BlockPos from) {
        ConstructionProject best = null;
        double bestDist = Double.MAX_VALUE;
        for (ConstructionProject p : projects.values()) {
            if (p.status == ConstructionProject.Status.COMPLETED
                    || p.status == ConstructionProject.Status.FAILED) continue;
            double d = new BlockPos(p.originX, p.originY, p.originZ).distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * True when a cell is part of a colony building and must not be touched.
     *
     * <p>What a project has <em>built</em>, not what it intends to build.
     *
     * <p>This has been wrong twice, in the same direction. First it was the
     * whole bounding box of every project — several thousand cells of open
     * countryside, trees and all, declared unbreakable. Narrowing that to the
     * cells the design fills was better and still wrong: a district marker is
     * sited from the surface heightmap, which puts it on top of a tree, and
     * the tree then became unharvestable because a post was one day going to
     * stand there. Planning a building must not freeze the ground under it.</p>
     *
     * <p>So: a cell is protected once it is part of the structure. Until then
     * it is ordinary terrain, which is exactly what site preparation is for —
     * and the builder clears its own site through an explicit, authorised
     * step rather than by everyone else being forbidden to touch it.</p>
     */
    public boolean protectsCell(BlockPos pos) {
        if (pos == null) return false;
        String key = null;
        for (ConstructionProject project : projects.values()) {
            if (key == null) key = project.key(pos.getX(), pos.getY(), pos.getZ());
            if (project.ownedCells.contains(key) || project.placed.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code pos} lies on the plot of a building not yet finished:
     * inside its horizontal footprint, give or take a block.
     *
     * <p>Torches, fields and signs went wherever there was room, and the room
     * was often a house plot. Each one then stood in a blueprint cell, and the
     * house could not go up round it. Nothing but the building goes on a plot.</p>
     */
    public boolean inBuildingPlot(BlockPos pos) {
        if (pos == null) return false;
        for (ConstructionProject project : projects.values()) {
            if (project.status == ConstructionProject.Status.COMPLETED
                    || project.status == ConstructionProject.Status.FAILED) continue;
            Blueprint blueprint = BLUEPRINTS.get(project.blueprintId);
            if (blueprint == null) continue;
            int x = pos.getX() - project.originX;
            int z = pos.getZ() - project.originZ;
            if (x >= -1 && x <= blueprint.sizeX && z >= -1 && z <= blueprint.sizeZ
                    && Math.abs(pos.getY() - project.originY) <= blueprint.sizeY + 2) {
                return true;
            }
        }
        return false;
    }

    /** True when a named project owns this world cell. */
    public boolean ownsCell(String projectId, BlockPos pos) {
        if (projectId == null || pos == null) return false;
        ConstructionProject project = byId(projectId);
        if (project == null) return false;
        if (project.ownedCells.contains(project.key(pos.getX(), pos.getY(), pos.getZ()))) return true;
        Blueprint blueprint = BLUEPRINTS.get(project.blueprintId);
        return blueprint != null && blueprint.hasEntryAt(
                pos.getX() - project.originX,
                pos.getY() - project.originY,
                pos.getZ() - project.originZ);
    }

    private boolean isRecordedProjectCell(BlockPos pos) {
        for (ConstructionProject project : projects.values()) {
            if (project.ownedCells.contains(project.key(pos.getX(), pos.getY(), pos.getZ()))
                    || project.placed.contains(project.key(pos.getX(), pos.getY(), pos.getZ()))) {
                return true;
            }
        }
        return false;
    }

    /** Natural terrain may be cleared as an explicit construction-site step. */
    public static boolean isNaturalSiteBlock(ServerLevel level, BlockState state, BlockPos pos) {
        if (level == null || state == null || pos == null || level.getBlockEntity(pos) != null) {
            return false;
        }
        if (!state.getFluidState().isEmpty() || state.getDestroySpeed(level, pos) < 0f) return false;
        // Anything growing or lying about where a building goes: trees, leaves,
        // flowers, crops, torches, the odd cobblestone or plank left by a
        // traversal. Only dirt and stone used to count, so an acacia standing
        // in a savanna house plot blocked the house forever — 585 failures of
        // "cell is occupied by a different block" in one afternoon. What is
        // never cleared: block entities (checked above), beds and doors, and
        // cells belonging to another project (checked by the caller).
        if (state.is(net.minecraft.tags.BlockTags.BEDS) || state.is(net.minecraft.tags.BlockTags.DOORS)) {
            return false;
        }
        if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES)
                || state.is(net.minecraft.tags.BlockTags.FLOWERS) || state.is(net.minecraft.tags.BlockTags.SAPLINGS)
                || state.is(net.minecraft.tags.BlockTags.CROPS) || state.is(Blocks.FARMLAND)
                || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.COBBLESTONE) || state.is(net.minecraft.tags.BlockTags.PLANKS)
                || state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.ICE)
                || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MUD) || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.SANDSTONE) || state.is(Blocks.TERRACOTTA)
                || state.is(net.minecraft.tags.BlockTags.TERRACOTTA)
                || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.CACTUS) || state.is(Blocks.PUMPKIN)
                || state.is(Blocks.MELON) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)) {
            return true;
        }
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY) || state.is(Blocks.STONE)
                || state.is(Blocks.ANDESITE) || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIORITE) || state.is(Blocks.TUFF)
                || state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.CALCITE);
    }

    public void reconcile(ServerLevel level, ConstructionProject project, Blueprint blueprint) {
        if (level == null || project == null || blueprint == null) return;
        boolean changed = false;
        Map<String, Blueprint.BlockEntry> expectedByPosition = new HashMap<>();
        for (Blueprint.BlockEntry entry : blueprint.entries()) {
            expectedByPosition.put(entry.x + "," + entry.y + "," + entry.z, entry);
        }
        Set<String> owned = new LinkedHashSet<>(project.ownedCells);
        owned.addAll(project.placed);
        for (String key : owned) {
            String[] parts = key.split(",");
            if (parts.length != 3) {
                project.placed.remove(key);
                project.ownedCells.remove(key);
                changed = true;
                continue;
            }
            int x;
            int y;
            int z;
            try {
                x = Integer.parseInt(parts[0]);
                y = Integer.parseInt(parts[1]);
                z = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ex) {
                project.placed.remove(key);
                project.ownedCells.remove(key);
                changed = true;
                continue;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (!project.ownedCells.contains(key)) {
                project.ownedCells.add(key);
                changed = true;
            }
            if (!level.isLoaded(pos)) {
                // Ownership is durable even while the chunk is unavailable.
                project.ownedCells.add(key);
                continue;
            }
            String relative = (x - project.originX) + ","
                    + (y - project.originY) + "," + (z - project.originZ);
            Blueprint.BlockEntry expected = expectedByPosition.get(relative);
            BlockState expectedState = expected == null ? null : parseState(level, expected.blockState);
            boolean matches = expected != null && expectedState != null
                    && matches(expectedState, level.getBlockState(pos));
            if (matches) {
                if (project.placed.add(key)) changed = true;
            } else {
                if (project.placed.remove(key)) changed = true;
                if (project.status == ConstructionProject.Status.COMPLETED) {
                    project.status = ConstructionProject.Status.PLANNED;
                    changed = true;
                }
                // Keep ownedCells: a player replacing a civic block must not
                // turn it into an ordinary natural block that site prep can erase.
            }
        }
        if (changed) setDirty();
    }

    public static ConstructionProject resolve(ServerLevel level, String projectId, BlockPos near) {
        ConstructionManager manager = get(level);
        if (projectId != null && !projectId.isEmpty() && !"nearest".equals(projectId)) {
            ConstructionProject p = manager.byId(projectId);
            if (p != null) return p;
        }
        // No project named: the one the colony most wants finished, not the
        // nearest. A generic "go and build" sent every such builder to the
        // biggest building beside them while the homes stayed at 0%.
        List<ConstructionProject> ranked = manager.prioritized(level, near);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /**
     * Unfinished projects in the order the colony wants them done: least work
     * left first, then the nearest.
     */
    public List<ConstructionProject> prioritized(ServerLevel level, BlockPos from) {
        List<ConstructionProject> active = new ArrayList<>();
        for (ConstructionProject project : projects.values()) {
            if (project.status == ConstructionProject.Status.COMPLETED
                    || project.status == ConstructionProject.Status.FAILED) continue;
            active.add(project);
        }
        Map<String, Double> progress = new HashMap<>();
        Map<String, Integer> size = new HashMap<>();
        for (ConstructionProject project : active) {
            Blueprint blueprint = ensureBlueprint(level, project.blueprintId);
            progress.put(project.id, blueprint == null ? 0.0 : project.progress(blueprint));
            size.put(project.id, blueprint == null ? Integer.MAX_VALUE : blueprint.entries().size());
        }
        // Least work left first: that is what gets buildings finished. A cabin
        // with ninety blocks to go beats a town hall with three hundred, even
        // one that is further along.
        active.sort(java.util.Comparator
                .comparingDouble((ConstructionProject project) ->
                        size.get(project.id) * (1.0 - progress.get(project.id)))
                .thenComparingDouble(project -> from == null ? 0.0
                        : new BlockPos(project.originX, project.originY, project.originZ).distSqr(from)));
        return active;
    }

    /**
     * Next block that should be physically placed: in build order, not yet
     * placed, and currently survivable (or deferred when support is missing).
     * Returns empty when the project is complete or everything left is blocked.
     */
    /**
     * Whether {@code actual} fulfils a blueprint cell that asks for {@code expected}:
     * the exact state, or the same wooden block in another species with the
     * same shape (facing, half, axis...). See {@link WoodSwap}.
     */
    public static boolean matches(BlockState expected, BlockState actual) {
        if (expected == null || actual == null) return false;
        if (expected.equals(actual)) return true;
        var expectedKey = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(expected.getBlock());
        var actualKey = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(actual.getBlock());
        if (expectedKey == null || actualKey == null
                || !WoodSwap.sameKind(expectedKey.toString(), actualKey.toString())) return false;
        for (var property : expected.getProperties()) {
            if (!actual.hasProperty(property)
                    || !actual.getValue(property).equals(expected.getValue(property))) return false;
        }
        return true;
    }

    /** The blueprint state rebuilt in another wood species, keeping its shape. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockState inSpecies(BlockState state, String species) {
        var key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null || !WoodSwap.isWooden(key.toString())) return state;
        var other = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                net.minecraft.resources.ResourceLocation.parse(WoodSwap.withSpecies(key.toString(), species)));
        if (other == null || other == net.minecraft.world.level.block.Blocks.AIR) return state;
        BlockState out = other.defaultBlockState();
        for (var property : state.getProperties()) {
            if (out.hasProperty(property)) {
                out = out.setValue((net.minecraft.world.level.block.state.properties.Property) property,
                        (Comparable) state.getValue(property));
            }
        }
        return out;
    }

    public Optional<NextStep> nextStep(ServerLevel level, ConstructionProject project) {
        return nextStep(level, project, item -> true);
    }

    /**
     * The next block to place, skipping those whose material {@code available}
     * says the builder cannot get right now. Building strictly in blueprint
     * order meant one missing chest stopped a whole house; now a builder puts
     * up every wall it has planks for and leaves the chest for later.
     */
    public Optional<NextStep> nextStep(ServerLevel level, ConstructionProject project,
                                       java.util.function.Predicate<String> available) {
        Blueprint blueprint = BLUEPRINTS.get(project.blueprintId);
        if (blueprint == null) return Optional.empty();

        List<Blueprint.BlockEntry> deferred = new ArrayList<>();
        boolean skippedForMaterial = false;
        for (Blueprint.BlockEntry entry : blueprint.entries()) {
            int ax = project.originX + entry.x;
            int ay = project.originY + entry.y;
            int az = project.originZ + entry.z;
            String key = project.key(ax, ay, az);
            if (project.placed.contains(key)) continue;

            BlockPos pos = new BlockPos(ax, ay, az);
            BlockState state = parseState(level, entry.blockState);
            if (state == null) {
                return Optional.of(new NextStep(pos, entry.blockState, true,
                        "unparseable block state: " + entry.blockState));
            }
            BlockState existing = level.getBlockState(pos);
            if (matches(state, existing)) {
                // Reconcile the ledger with the real world instead of asking a
                // builder to overwrite a block that is already correct.
                project.placed.add(key);
                project.ownedCells.add(key);
                continue;
            }
            if (!existing.canBeReplaced()) {
                if (isRecordedProjectCell(pos)) {
                    return Optional.of(new NextStep(pos, entry.blockState, true,
                            "cell is already owned by a construction project"));
                }
                if (isNaturalSiteBlock(level, existing, pos)) {
                    return Optional.of(new NextStep(pos, entry.blockState, false,
                            "prepare natural site", true));
                }
                return Optional.of(new NextStep(pos, entry.blockState, true,
                        "cell is occupied by a different block"));
            }
            if (!PlacementSupport.canPlace(level, state, pos)
                    || !supportChainReady(level, project, blueprint, state, pos)) {
                deferred.add(entry);
                continue;
            }
            if (!available.test(entry.itemId())) {
                skippedForMaterial = true;
                continue;
            }
            return Optional.of(new NextStep(pos, entry.blockState, false, null));
        }

        // Unsupported blocks the builder has material for come first: the
        // builder can lay the foundation under them (see BuildBlueprintSkill).
        for (Blueprint.BlockEntry entry : deferred) {
            if (!available.test(entry.itemId())) continue;
            BlockPos pos = new BlockPos(project.originX + entry.x,
                    project.originY + entry.y, project.originZ + entry.z);
            return Optional.of(new NextStep(pos, entry.blockState, true,
                    "missing support at " + pos.getX() + "," + pos.getY() + "," + pos.getZ()));
        }
        if (skippedForMaterial || !deferred.isEmpty()) {
            return Optional.of(NextStep.noMaterial());
        }
        return Optional.empty();
    }

    private boolean supportChainReady(ServerLevel level, ConstructionProject project,
                                      Blueprint blueprint, BlockState state, BlockPos pos) {
        BlockPos below = pos.below();
        if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return true;
        Direction support = attachedSupport(state);
        if (support == null) return true; // PlacementSupport already rejected this case.
        BlockPos supportPos = pos.relative(support);
        if (!blueprint.containsRelative(supportPos.getX() - project.originX,
                supportPos.getY() - project.originY,
                supportPos.getZ() - project.originZ)) return true;
        String key = project.key(supportPos.getX(), supportPos.getY(), supportPos.getZ());
        if (project.placed.contains(key) || project.ownedCells.contains(key)) return true;
        for (Blueprint.BlockEntry entry : blueprint.entries()) {
            if (project.originX + entry.x == supportPos.getX()
                    && project.originY + entry.y == supportPos.getY()
                    && project.originZ + entry.z == supportPos.getZ()) {
                BlockState expected = parseState(level, entry.blockState);
                return expected != null && level.getBlockState(supportPos).equals(expected);
            }
        }
        return true;
    }

    private static Direction attachedSupport(BlockState state) {
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
            Direction direction = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
            if (direction.getAxis().isHorizontal()) return direction.getOpposite();
        }
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
            Direction direction = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
            if (direction.getAxis().isHorizontal()) return direction.getOpposite();
        }
        return null;
    }

    public static BlockState parseState(ServerLevel level, String blockStateString) {
        try {
            return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                    level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                    blockStateString, false).blockState();
        } catch (Exception ex) {
            return null;
        }
    }

    public static final class NextStep {
        public final BlockPos pos;
        public final String blockState;
        public final boolean unsupported;
        public final String detail;
        /** The worker must remove natural terrain before placing this entry. */
        public final boolean clearExisting;

        /** Set when every placeable block left needs a material the builder cannot get. */
        public boolean missingMaterial;

        public NextStep(BlockPos pos, String blockState, boolean unsupported, String detail) {
            this(pos, blockState, unsupported, detail, false);
        }

        static NextStep noMaterial() {
            NextStep step = new NextStep(null, null, true, "no material on hand for any remaining block");
            step.missingMaterial = true;
            return step;
        }

        public NextStep(BlockPos pos, String blockState, boolean unsupported, String detail,
                        boolean clearExisting) {
            this.pos = pos;
            this.blockState = blockState;
            this.unsupported = unsupported;
            this.detail = detail;
            this.clearExisting = clearExisting;
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ConstructionProject p : projects.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", p.id);
            t.putString("name", p.name);
            t.putString("blueprint", p.blueprintId);
            t.putInt("ox", p.originX);
            t.putInt("oy", p.originY);
            t.putInt("oz", p.originZ);
            t.putString("status", p.status.name());
            t.putLong("created", p.createdAtGameTime);
            ListTag placedList = new ListTag();
            for (String key : p.placed) {
                placedList.add(StringTag.valueOf(key));
            }
            t.put("placed", placedList);
            ListTag ownedList = new ListTag();
            for (String key : p.ownedCells) ownedList.add(StringTag.valueOf(key));
            t.put("owned", ownedList);
            list.add(t);
        }
        tag.put("projects", list);
        if (campAnchor != null) {
            tag.putLong("campAnchor", campAnchor.asLong());
        }
        return tag;
    }

    public static ConstructionManager load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ConstructionManager manager = new ConstructionManager();
        ListTag list = tag.getList("projects", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            ConstructionProject p = new ConstructionProject(
                    t.getString("id"), t.getString("name"), t.getString("blueprint"),
                    t.getInt("ox"), t.getInt("oy"), t.getInt("oz"));
            try {
                p.status = ConstructionProject.Status.valueOf(t.getString("status"));
            } catch (IllegalArgumentException ex) {
                p.status = ConstructionProject.Status.PAUSED;
            }
            p.createdAtGameTime = t.getLong("created");
            ListTag placedList = t.getList("placed", Tag.TAG_STRING);
            for (int j = 0; j < placedList.size(); j++) {
                String key = placedList.getString(j);
                p.placed.add(key);
                p.ownedCells.add(key); // migrate pre-ownership saves safely
            }
            ListTag ownedList = t.getList("owned", Tag.TAG_STRING);
            for (int j = 0; j < ownedList.size(); j++) {
                p.ownedCells.add(ownedList.getString(j));
            }
            manager.projects.put(p.id, p);
        }
        if (tag.contains("campAnchor")) {
            manager.campAnchor = BlockPos.of(tag.getLong("campAnchor"));
        }
        return manager;
    }
}
