package ai.minecivilization.construction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-saved construction projects + blueprint catalog access.
 * V1 ships the built-in Starter Warehouse blueprint; imported schematics
 * register additional blueprints here (memory only, cataloged in the AI DB).
 */
public final class ConstructionManager extends SavedData {
    private static final String KEY = "minecivilization_construction";

    private final Map<String, ConstructionProject> projects = new LinkedHashMap<>();
    private static final Map<String, Blueprint> BLUEPRINTS = new LinkedHashMap<>();

    static {
        registerBlueprint(StarterWarehouse.create());
        registerBlueprint(StarterHouse.create());
    }

    public static void registerBlueprint(Blueprint blueprint) {
        BLUEPRINTS.put(blueprint.id, blueprint);
    }

    public static Blueprint blueprint(String id) {
        return BLUEPRINTS.get(id);
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

    public ConstructionProject byId(String id) {
        return projects.get(id);
    }

    public List<ConstructionProject> all() {
        return new ArrayList<>(projects.values());
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

    public static ConstructionProject resolve(ServerLevel level, String projectId, BlockPos near) {
        ConstructionManager manager = get(level);
        if (projectId != null && !projectId.isEmpty() && !"nearest".equals(projectId)) {
            ConstructionProject p = manager.byId(projectId);
            if (p != null) return p;
        }
        return manager.nearestActive(near);
    }

    /**
     * Next block that should be physically placed: in build order, not yet
     * placed, and currently survivable (or deferred when support is missing).
     * Returns empty when the project is complete or everything left is blocked.
     */
    public Optional<NextStep> nextStep(ServerLevel level, ConstructionProject project) {
        Blueprint blueprint = BLUEPRINTS.get(project.blueprintId);
        if (blueprint == null) return Optional.empty();

        List<Blueprint.BlockEntry> deferred = new ArrayList<>();
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
            if (!state.canSurvive(level, pos)) {
                deferred.add(entry);
                continue;
            }
            return Optional.of(new NextStep(pos, entry.blockState, false, null));
        }

        if (!deferred.isEmpty()) {
            Blueprint.BlockEntry entry = deferred.get(0);
            BlockPos pos = new BlockPos(project.originX + entry.x,
                    project.originY + entry.y, project.originZ + entry.z);
            return Optional.of(new NextStep(pos, entry.blockState, true,
                    "missing support at " + pos.getX() + "," + pos.getY() + "," + pos.getZ()));
        }
        return Optional.empty();
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

        public NextStep(BlockPos pos, String blockState, boolean unsupported, String detail) {
            this.pos = pos;
            this.blockState = blockState;
            this.unsupported = unsupported;
            this.detail = detail;
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
            list.add(t);
        }
        tag.put("projects", list);
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
                p.placed.add(placedList.getString(j));
            }
            manager.projects.put(p.id, p);
        }
        return manager;
    }
}
