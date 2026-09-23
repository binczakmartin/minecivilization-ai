package ai.minecivilization.entity;

import ai.minecivilization.citizen.CitizenBrain;
import ai.minecivilization.citizen.CitizenIdentity;
import ai.minecivilization.citizen.CitizenPersonality;
import ai.minecivilization.citizen.CitizenSkills;
import ai.minecivilization.citizen.TaskExecutor;
import ai.minecivilization.combat.CombatPolicy;
import ai.minecivilization.config.ModConfig;
import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.navigation.CitizenNavigator;
import ai.minecivilization.skills.SkillFailure;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A worker NPC. The entity only executes deterministic skills; the Python service
 * picks the plan/goal asynchronously and never controls this entity per tick.
 */
public class CitizenEntity extends PathfinderMob {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final EntityDataAccessor<String> DATA_ACTION =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_GOAL =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_STATUS =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);
    /**
     * The trade, synced to clients.
     *
     * <p>Identity lives in NBT, which never reaches the client — so the
     * renderer saw every citizen as UNASSIGNED and drew all of them with the
     * same face, whatever trade they actually held.</p>
     */
    private static final EntityDataAccessor<String> DATA_PROFESSION =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.STRING);

    /** Melee reach in blocks (feet-to-feet) before the citizen swings. */
    private static final double ATTACK_REACH = 2.5;
    /** How far the mob that just hurt us may still be chased (blocks). */
    private static final double ATTACKER_LEASH = 16.0;
    /** Ticks since being hurt during which that mob counts as "the attacker". */
    private static final int ATTACKER_RECENT_TICKS = 100;
    /** Ticks between threat scans while out of combat. */
    private static final int COMBAT_SCAN_INTERVAL = 5;
    /** Ticks between chase repaths. */
    private static final int COMBAT_REPATH_INTERVAL = 10;

    private static final String TAG_IDENTITY = "McivIdentity";
    private static final String TAG_PERSONALITY = "McivPersonality";
    private static final String TAG_HUNGER = "McivHunger";
    private static final String TAG_ENERGY = "McivEnergy";
    private static final String TAG_MEMORY = "McivMemory";
    private static final String TAG_KNOWN_RESOURCES = "McivKnownResources";
    private static final String TAG_LAST_OBSERVATION = "McivLastObservation";
    private static final String TAG_REGISTERED = "McivRegistered";
    private static final String TAG_WORK_BLOCKED = "McivWorkBlocked";
    private static final String TAG_SKILLS = "McivSkills";
    private static final String TAG_INVENTORY = "McivInventory";

    /** Hunger 0..100; below the starvation line citizens refuse to work. */
    public static final float HUNGER_FULL = 100.0f;
    public static final float HUNGER_STARVING = 15.0f;

    private final CitizenInventory inventory = new CitizenInventory(CitizenInventory.SIZE);
    private final TaskExecutor executor = new TaskExecutor();
    private final Map<String, Float> memory = new HashMap<>();
    /** Block id -> last known position, discovered by actually looking around. */
    private final Map<String, BlockPos> knownResources = new LinkedHashMap<>();
    private final List<String> disabledSkills = new ArrayList<>();
    /**
     * Blocks placed purely to stand on — bridge decks, pillar supports.
     *
     * <p>Deliberately not persisted: scaffolding is only worth tidying while
     * the citizen is still standing next to it, and a dirt tower left over a
     * restart is better forgotten than chased across a save.</p>
     */
    private final List<BlockPos> scaffoldPlaced = new ArrayList<>();

    private CitizenIdentity identity = new CitizenIdentity();
    private CitizenPersonality personality = new CitizenPersonality();
    private final CitizenSkills skills = new CitizenSkills();
    private CitizenBrain brain;
    private CitizenNavigator navigator;
    private String lastObservation = "{}";
    private boolean registeredWithService = false;
    private boolean workAllowed = true;
    private float hunger = HUNGER_FULL;
    private float energy = 100.0f;
    private volatile boolean changed = true;

    /** Transient combat state (deliberately not persisted — fights don't cross restarts). */
    private Mob combatTarget;
    /** Something the citizen is backing away from rather than fighting. */
    private Mob fleeFrom;
    /** What kind of thing it is — decides how far away counts as safe. */
    private CombatPolicy.ThreatKind fleeKind = CombatPolicy.ThreatKind.MELEE;
    /** When the retreat started, so it cannot last forever. */
    private long fleeStartedAt;
    private double combatLeash = ATTACKER_LEASH;
    private long combatLastHitAt;
    private long combatNextAttackAt;
    private long combatLastRepathAt;

    public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = 5;
        // PathfinderMob already built `navigation` in super(); wrap it deterministically.
        this.navigator = new CitizenNavigator(this, this.navigation);
        this.brain = new CitizenBrain(this);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.30D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.STEP_HEIGHT, 1.0D)
                .add(Attributes.ATTACK_DAMAGE, 4.0D);
    }

    @Override
    protected void registerGoals() {
        // Pathfinding movement goals are owned by CitizenNavigator (added in ctor,
        // before entity data init); registerGoals only adds the vanilla float goal.
        this.goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ACTION, "idle");
        builder.define(DATA_GOAL, "idle");
        builder.define(DATA_STATUS, "ok");
        builder.define(DATA_PROFESSION, "UNASSIGNED");
    }

    /** The trade as the client knows it — the only version a renderer may trust. */
    public String getSyncedProfession() {
        return this.entityData.get(DATA_PROFESSION);
    }

    /** Push the server-side trade out to watching clients. */
    private void publishProfession() {
        String trade = this.identity.profession == null ? "UNASSIGNED" : this.identity.profession;
        if (!trade.equals(this.entityData.get(DATA_PROFESSION))) {
            this.entityData.set(DATA_PROFESSION, trade);
        }
    }

    // ------------------------------------------------------------------ accessors

    public CitizenIdentity getIdentity() {
        return this.identity;
    }

    public CitizenPersonality getPersonality() {
        return this.personality;
    }

    public CitizenSkills getSkills() {
        return this.skills;
    }

    public CitizenBrain getCitizenBrain() {
        return this.brain;
    }

    public CitizenInventory getInventoryAccess() {
        return this.inventory;
    }

    /** Alias used by skills and the observation builder. */
    public CitizenInventory getInventory() {
        return this.inventory;
    }

    public CitizenNavigator getCitizenNavigator() {
        return this.navigator;
    }

    /** Alias matching the call sites in skills/observation code. */
    public CitizenNavigator getNavigator() {
        return this.navigator;
    }

    public TaskExecutor getExecutor() {
        return this.executor;
    }

    /** Latest observation JSON sent to the AI service (kept for inspection). */
    public String getLastObservation() {
        return this.lastObservation;
    }

    public void setLastObservation(String observationJson) {
        this.lastObservation = observationJson == null ? "{}" : observationJson;
    }

    public float getHunger() {
        return this.hunger;
    }

    public void setHunger(float value) {
        this.hunger = Mth.clamp(value, 0.0f, HUNGER_FULL);
        this.changed = true;
    }

    public float getEnergy() {
        return this.energy;
    }

    public void setEnergy(float value) {
        this.energy = Mth.clamp(value, 0.0f, 100.0f);
    }

    public boolean isRegisteredWithService() {
        return this.registeredWithService;
    }

    public void setRegisteredWithService(boolean value) {
        this.registeredWithService = value;
        this.markDirty();
    }

    public boolean isWorkAllowed() {
        return this.workAllowed;
    }

    public void setWorkAllowed(boolean value) {
        this.workAllowed = value;
        this.markDirty();
    }

    public void setDisplayState(String action, String goal, String status) {
        String a = action == null || action.isBlank() ? "idle" : action;
        String g = goal == null || goal.isBlank() ? "idle" : goal;
        String s = status == null || status.isBlank() ? "ok" : status;
        if (!a.equals(this.entityData.get(DATA_ACTION))
                || !g.equals(this.entityData.get(DATA_GOAL))
                || !s.equals(this.entityData.get(DATA_STATUS))) {
            this.entityData.set(DATA_ACTION, a);
            this.entityData.set(DATA_GOAL, g);
            this.entityData.set(DATA_STATUS, s);
        }
    }

    public String getActionName() {
        return this.entityData.get(DATA_ACTION);
    }

    public String getGoalName() {
        return this.entityData.get(DATA_GOAL);
    }

    public String getStatusName() {
        return this.entityData.get(DATA_STATUS);
    }

    public Map<String, Float> getMemory() {
        return this.memory;
    }

    /** Discovered resources: block id -> last known position. */
    public Map<String, BlockPos> knownResources() {
        return this.knownResources;
    }

    /** Just the ids, for containment checks. */
    public Set<String> getKnownResources() {
        return this.knownResources.keySet();
    }

    public List<String> getDisabledSkills() {
        return this.disabledSkills;
    }

    // ------------------------------------------------------------------ knowledge / events

    public void onResourceFound(Block block, BlockPos pos) {
        String key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
        if (!this.knownResources.containsKey(key)) {
            this.memory.merge("found:" + key, 1.0f, Float::sum);
        }
        this.knownResources.put(key, pos.immutable());
        this.memory.put("lastResourceX", (float) pos.getX());
        this.memory.put("lastResourceY", (float) pos.getY());
        this.memory.put("lastResourceZ", (float) pos.getZ());
        this.changed = true;
    }

    public void onTaskFailed(SkillFailure failure) {
        this.memory.merge("fail:" + failure.code, 1.0f, Float::sum);
        this.memory.merge("taskFailures", 1.0f, Float::sum);
        this.changed = true;
    }

    public void onTaskSucceeded(String skill) {
        this.memory.merge("done:" + skill, 1.0f, Float::sum);
        this.memory.put("lastSuccessAt", (float) this.level().getGameTime());
        this.changed = true;
    }

    public void onProjectProgress(ConstructionProject project) {
        this.changed = true;
    }

    public void onProjectCompleted(ConstructionProject project) {
        this.memory.put("completedProject:" + project.id, 1.0f);
        this.memory.merge("projectsCompleted", 1.0f, Float::sum);
        this.changed = true;
    }

    public void onItemTaken(net.minecraft.world.item.Item item, int count) {
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        this.memory.merge("took:" + key, (float) count, Float::sum);
        this.changed = true;
    }

    /**
     * Workstation/build milestone, e.g. "placed:minecraft:crafting_table".
     * The brain reads these keys (known_memories) so it never crafts a second
     * crafting table for a workstation that is already standing.
     */
    public void onBlockPlaced(String blockId) {
        if (blockId == null || blockId.isEmpty()) return;
        int bracket = blockId.indexOf('[');
        String id = bracket > 0 ? blockId.substring(0, bracket) : blockId;
        if (id.isEmpty()) return;
        this.memory.put("placed:" + id, (float) this.level().getGameTime());
        this.changed = true;
    }

    public void onItemDeposited(net.minecraft.world.item.Item item, int count) {
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
        this.memory.merge("deposited:" + key, (float) count, Float::sum);
        this.changed = true;
    }

    public void onSkillDisabled(String skill, SkillFailure failure) {
        if (!this.disabledSkills.contains(skill)) {
            this.disabledSkills.add(skill);
        }
        this.memory.merge("disabled:" + skill, 1.0f, Float::sum);
        this.changed = true;
    }

    /** Hunger points restored by a food item (hunger is 0..100, nutrition 0..20). */
    public static float hungerForNutrition(int nutrition) {
        return nutrition * 5.0f;
    }

    /** Called when the citizen eats; restores hunger and enforces nutrition rules. */
    public void eatNow() {
        if (this.level().random.nextFloat() < 0.05f) {
            this.level().playSound(null, this.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.6f, 1.0f);
        }
        this.setHunger(HUNGER_FULL);
        this.setEnergy(100.0f);
        this.changed = true;
    }

    /** Cheap heuristic used by skills: where might food be? */
    public boolean knowsFoodNearby() {
        return this.knownResources.containsKey("minecraft:wheat")
                || this.knownResources.containsKey("minecraft:bread")
                || this.knownResources.containsKey("minecraft:carrot")
                || this.knownResources.containsKey("minecraft:apple");
    }

    public void setStatus(String status) {
        this.setDisplayState(this.getActionName(), this.getGoalName(), status);
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(TAG_IDENTITY, Tag.TAG_COMPOUND)) {
            this.identity.load(tag.getCompound(TAG_IDENTITY));
            this.applyIdentityName();
        }
        if (tag.contains(TAG_PERSONALITY, Tag.TAG_COMPOUND)) {
            this.personality.load(tag.getCompound(TAG_PERSONALITY));
        }
        if (tag.contains(TAG_SKILLS, Tag.TAG_COMPOUND)) {
            this.skills.load(tag.getCompound(TAG_SKILLS));
        }
        this.hunger = tag.contains(TAG_HUNGER) ? tag.getFloat(TAG_HUNGER) : HUNGER_FULL;
        this.energy = tag.contains(TAG_ENERGY) ? tag.getFloat(TAG_ENERGY) : 100.0f;
        this.registeredWithService = tag.getBoolean(TAG_REGISTERED);
        this.workAllowed = !tag.contains(TAG_WORK_BLOCKED) || !tag.getBoolean(TAG_WORK_BLOCKED);
        this.memory.clear();
        if (tag.contains(TAG_MEMORY, Tag.TAG_COMPOUND)) {
            CompoundTag mem = tag.getCompound(TAG_MEMORY);
            for (String key : mem.getAllKeys()) {
                this.memory.put(key, mem.getFloat(key));
            }
        }
        this.knownResources.clear();
        if (tag.contains(TAG_KNOWN_RESOURCES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_KNOWN_RESOURCES, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String entry = list.getString(i);
                int at = entry.indexOf('@');
                if (at <= 0) continue;
                String[] xyz = entry.substring(at + 1).split(",");
                if (xyz.length != 3) continue;
                try {
                    this.knownResources.put(entry.substring(0, at), new BlockPos(
                            Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
                } catch (NumberFormatException ignored) {
                    // skip malformed entry
                }
            }
        }
        this.lastObservation = tag.contains(TAG_LAST_OBSERVATION)
                ? tag.getString(TAG_LAST_OBSERVATION) : "{}";
        if (tag.contains(TAG_INVENTORY, Tag.TAG_COMPOUND)) {
            this.inventory.load(tag.getCompound(TAG_INVENTORY), this.registryAccess());
        }
        this.navigator.setBlockedPaths(this.executor.hasActiveTask());
        this.markDirty();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        CompoundTag identityTag = new CompoundTag();
        this.identity.save(identityTag);
        tag.put(TAG_IDENTITY, identityTag);
        CompoundTag personalityTag = new CompoundTag();
        this.personality.save(personalityTag);
        tag.put(TAG_PERSONALITY, personalityTag);
        CompoundTag skillsTag = new CompoundTag();
        this.skills.save(skillsTag);
        tag.put(TAG_SKILLS, skillsTag);
        tag.putFloat(TAG_HUNGER, this.hunger);
        tag.putFloat(TAG_ENERGY, this.energy);
        tag.putBoolean(TAG_REGISTERED, this.registeredWithService);
        tag.putBoolean(TAG_WORK_BLOCKED, !this.workAllowed);
        CompoundTag mem = new CompoundTag();
        this.memory.forEach(mem::putFloat);
        tag.put(TAG_MEMORY, mem);
        ListTag known = new ListTag();
        this.knownResources.forEach((id, pos) -> known.add(StringTag.valueOf(
                id + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ())));
        tag.put(TAG_KNOWN_RESOURCES, known);
        tag.putString(TAG_LAST_OBSERVATION, this.lastObservation);
        CompoundTag inventoryTag = new CompoundTag();
        this.inventory.save(inventoryTag, this.registryAccess());
        tag.put(TAG_INVENTORY, inventoryTag);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (this.level().isClientSide) {
            return;
        }
        if (this.identity.citizenId == null) {
            this.identity.citizenId = this.getUUID();
        }
        if (this.identity.createdAt == 0L) {
            this.identity.createdAt = this.level().getGameTime();
        }
        CitizenIndex.add(this);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide) {
            CitizenIndex.remove(this);
        }
        super.remove(reason);
    }

    /**
     * Put the citizen's own name on the entity, visibly.
     *
     * <p>Without this the world (and every log line) calls every citizen
     * {@code entity.minecivilization.citizen}: they all look identical, cannot
     * be told apart in a crowd, and cannot be found again. The name tag is the
     * cheapest possible way to make a settlement legible.</p>
     */
    /**
     * Change a citizen's trade.
     *
     * <p>The name tag, the face and the tool in its hand all follow the trade,
     * so all three are refreshed. The citizen keeps its tools and its skills —
     * a retrained lumberjack still swings an axe well — it simply takes its
     * orders from a different rule now.</p>
     */
    public void retrain(String profession) {
        if (profession == null || profession.isBlank()) return;
        if (profession.equals(this.identity.profession)) return;
        this.identity.profession = profession;
        this.applyIdentityName();
        this.syncEquipmentDisplay();
        this.setRegisteredWithService(false);   // re-register under the new trade
        this.markDirty();
    }

    /** Record a throwaway block so it can be taken down again afterwards. */
    public void rememberScaffold(BlockPos pos) {
        if (pos == null) return;
        BlockPos immutable = pos.immutable();
        if (!this.scaffoldPlaced.contains(immutable)) {
            this.scaffoldPlaced.add(immutable);
        }
        // A citizen should never be dragging a hundred of these around.
        while (this.scaffoldPlaced.size() > 64) {
            this.scaffoldPlaced.remove(0);
        }
    }

    /** Throwaway blocks still standing, highest first — take a tower down from the top. */
    public List<BlockPos> scaffoldPlaced() {
        List<BlockPos> sorted = new ArrayList<>(this.scaffoldPlaced);
        sorted.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        return sorted;
    }

    public void forgetScaffold(BlockPos pos) {
        this.scaffoldPlaced.remove(pos);
    }

    public void forgetAllScaffold() {
        this.scaffoldPlaced.clear();
    }

    public void applyIdentityName() {
        String name = this.identity.name;
        if (name == null || name.isBlank()) return;
        String trade = this.identity.profession == null
                || "UNASSIGNED".equals(this.identity.profession)
                ? "" : " \u00b7 " + this.identity.profession.toLowerCase(java.util.Locale.ROOT);
        this.setCustomName(net.minecraft.network.chat.Component.literal(name + trade));
        this.setCustomNameVisible(true);
        if (!this.level().isClientSide) {
            this.publishProfession();
        }
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType spawnType, @Nullable SpawnGroupData groupData) {
        if (this.identity.citizenId == null) {
            this.identity.citizenId = this.getUUID();
        }
        if (this.identity.name == null || this.identity.name.isBlank()
                || "Citizen".equals(this.identity.name)) {
            this.identity.name = CitizenIdentity.randomName(level.getRandom());
        }
        if (this.identity.createdAt == 0L) {
            this.identity.createdAt = this.level().getGameTime();
        }
        // Fresh citizens (UNASSIGNED) join the role rotation in spawn order —
        // the settlement self-organizes: lumberjack, miner, farmer, builder,
        // crafter, then it repeats. The role persists in NBT, so a loaded
        // citizen never loses or changes its profession.
        if ("UNASSIGNED".equals(this.identity.profession)) {
            this.identity.profession =
                    CitizenIdentity.professionForPopulation(CitizenIndex.population());
            // Deterministic personality: same (seed, citizenId) always yields the same traits.
            this.personality = CitizenPersonality.derived(ModConfig.SIMULATION_SEED.get(),
                    this.identity.citizenId);
        }
        // Command/egg spawned citizens start with no seed money: they gather from the world.
        this.inventory.clear();
        this.applyIdentityName();
        this.markDirty();
        return super.finalizeSpawn(level, difficulty, spawnType, groupData);
    }

    // ------------------------------------------------------------------ ticking

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide) {
            return;
        }
        ServerLevel server = (ServerLevel) this.level();
        long time = server.getGameTime();

        // Passive hunger: work costs energy, doing nothing costs less.
        if (time % 40 == 0) {
            float drain = this.executor.hasActiveTask() ? 0.35f : 0.12f;
            this.setHunger(this.hunger - drain);
        }

        // Eating is a local deterministic action: only from a real food stack.
        if (this.hunger < 60.0f && !this.executor.hasActiveTask()) {
            eatFromInventoryIfPossible();
        }

        // Deterministic self-defence runs first: combat is survival, not
        // policy, so it reacts locally and never waits for the AI service.
        this.tickMarker();
        if (time % 20 == 0) {
            this.syncEquipmentDisplay();
        }
        boolean fighting = this.tickCombat(server, time);

        // GOAL -> TASK -> SKILL: the deterministic brain runs the current plan and
        // only asks the AI service for a new decision when there is nothing to do
        // (always asynchronously — Minecraft never waits for the AI).
        if (!fighting && this.workAllowed && this.isEligibleForWork()) {
            this.brain.tick(server, this);
        }

        // While a task or a fight drives movement, idle wandering must stay out of the way.
        this.navigator.setBlockedPaths(fighting || this.executor.hasActiveTask());
        if (!fighting && !this.navigator.hasBlockedPaths() && !this.executor.hasActiveTask()) {
            this.navigator.tickIdle(server.getRandom());
        }
    }

    private void eatFromInventoryIfPossible() {
        int slot = findFoodSlot();
        if (slot < 0) {
            return;
        }
        ItemStack stack = this.inventory.getItem(slot);
        int nutrition = nutritionOf(stack);
        stack.shrink(1);
        if (stack.isEmpty()) {
            this.inventory.setItem(slot, ItemStack.EMPTY);
        }
        this.setHunger(this.hunger + hungerForNutrition(nutrition));
        this.level().playSound(null, this.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.7f, 1.0f);
        this.onInventoryChanged();
    }

    /**
     * Whether the brain may run at all this tick.
     *
     * <p>This used to refuse work below the starvation line, which reads as
     * sensible and is in fact a death spiral: a starving citizen was forbidden
     * from doing the one thing that would feed it, so its hunger kept falling
     * and it stood still until it died. A settlement with no food would freeze
     * entirely and never recover.</p>
     *
     * <p>Hunger now shapes <em>what</em> a citizen does — the decision policy
     * puts eating above everything else — rather than stopping it from acting.
     * Only actually dying takes a citizen out of the loop.</p>
     */
    public boolean isEligibleForWork() {
        if (this.isDeadOrDying()) {
            return false;
        }
        if (this.hunger < HUNGER_STARVING) {
            this.setStatus("hungry");
        }
        return true;
    }

    private int findFoodSlot() {
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (nutritionOf(stack) > 0) {
                return i;
            }
        }
        return -1;
    }

    private static int nutritionOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        if (stack.is(Items.BREAD)) return 10;
        if (stack.is(Items.COOKED_BEEF)) return 12;
        if (stack.is(Items.COOKED_PORKCHOP)) return 12;
        if (stack.is(Items.COOKED_CHICKEN)) return 12;
        if (stack.is(Items.COOKED_SALMON)) return 10;
        if (stack.is(Items.COOKED_COD)) return 8;
        if (stack.is(Items.APPLE)) return 4;
        if (stack.is(Items.CARROT)) return 3;
        if (stack.is(Items.POTATO)) return 1;
        if (stack.is(Items.BAKED_POTATO)) return 5;
        if (stack.is(Items.MUSHROOM_STEW)) return 6;
        if (stack.is(Items.BEETROOT_SOUP)) return 6;
        if (stack.is(Items.PUMPKIN_PIE)) return 8;
        if (stack.is(Items.GOLDEN_APPLE)) return 4;
        if (stack.is(Items.MELON_SLICE)) return 2;
        if (stack.is(Items.SWEET_BERRIES)) return 2;
        if (stack.is(Items.GLOW_BERRIES)) return 2;
        if (stack.is(Items.RABBIT_STEW)) return 10;
        if (stack.is(Items.COOKIE)) return 2;
        if (stack.is(Items.CAKE)) return 2;
        return 0;
    }

    // ------------------------------------------------------------------ combat

    /**
     * Deterministic self-defence: engage the nearest hostile mob inside a
     * personality-scaled radius (or whoever just hurt us), chase it and punch
     * it on a cooldown. Runs locally every tick — like eating, fighting is a
     * survival reflex, never an AI decision.
     *
     * @return true while a fight is driving movement this tick
     */
    /** Keep the outline in step with the /mciv highlight toggle. */
    /**
     * Put the right tool in the citizen's hand, and its armour on its back.
     *
     * <p>Purely a display of what the citizen already owns: the real items stay
     * in its inventory and the equipment slots hold copies with a zero drop
     * chance, so nothing is duplicated when one dies. Without this a miner with
     * a full set of iron tools is indistinguishable from one with nothing.</p>
     */
    private void syncEquipmentDisplay() {
        var task = this.brain.currentTaskOrNull();
        String suffix = ai.minecivilization.citizen.CitizenLook.toolSuffixFor(
                this.identity.profession, task == null ? null : task.type.name());

        // A fight outranks the job: show the weapon if there is one.
        if (this.combatTarget != null) {
            ItemStack weapon = bestMatching("_sword");
            if (weapon.isEmpty()) weapon = bestMatching("_axe");
            if (!weapon.isEmpty()) suffix = null;
            setDisplayItem(EquipmentSlot.MAINHAND, weapon);
            if (!weapon.isEmpty()) return;
        }

        setDisplayItem(EquipmentSlot.MAINHAND,
                suffix == null ? ItemStack.EMPTY : bestMatching(suffix));

        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            setDisplayItem(slot, bestArmourFor(slot));
        }
    }

    /** Copy an owned item into a display slot, never letting it drop. */
    private void setDisplayItem(EquipmentSlot slot, ItemStack owned) {
        ItemStack shown = this.getItemBySlot(slot);
        if (ItemStack.isSameItem(shown, owned)) return;
        this.setItemSlot(slot, owned.isEmpty() ? ItemStack.EMPTY : owned.copyWithCount(1));
        this.setDropChance(slot, 0.0F);   // the real item lives in the inventory
    }

    /** The best carried item whose id ends with this suffix, by material tier. */
    private ItemStack bestMatching(String suffix) {
        ItemStack best = ItemStack.EMPTY;
        int bestTier = -1;
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String id = CitizenInventory.idOf(stack);
            if (!id.endsWith(suffix)) continue;
            int tier = materialTier(id);
            if (tier > bestTier) {
                bestTier = tier;
                best = stack;
            }
        }
        return best;
    }

    private ItemStack bestArmourFor(EquipmentSlot slot) {
        String suffix = switch (slot) {
            case HEAD -> "_helmet";
            case CHEST -> "_chestplate";
            case LEGS -> "_leggings";
            case FEET -> "_boots";
            default -> null;
        };
        return suffix == null ? ItemStack.EMPTY : bestMatching(suffix);
    }

    private static int materialTier(String itemId) {
        if (itemId.contains("netherite")) return 5;
        if (itemId.contains("diamond")) return 4;
        if (itemId.contains("iron")) return 3;
        if (itemId.contains("stone")) return 2;
        if (itemId.contains("golden")) return 2;
        if (itemId.contains("wooden") || itemId.contains("leather")) return 1;
        return 0;
    }

    private void tickMarker() {
        boolean wanted = ai.minecivilization.colony.CitizenMarkers.highlighted();
        if (this.hasGlowingTag() != wanted) {
            this.setGlowingTag(wanted);
        }
    }

    private boolean tickCombat(ServerLevel server, long time) {
        if (!ModConfig.COMBAT_ENABLED.get() || !this.isAlive()) {
            this.combatTarget = null;
            this.fleeFrom = null;
            return false;
        }

        // Retreating outranks everything: a citizen next to a creeper has no
        // business weighing up a fight with the zombie behind it.
        if (this.fleeFrom != null && tickFlee(server, time)) {
            return true;
        }

        if (this.combatTarget != null) {
            Mob target = this.combatTarget;
            if (!target.isAlive()) {
                recordKill(target);
                endCombat();
                return false;
            }
            double distSqr = this.distanceToSqr(target);
            if (distSqr > this.combatLeash * this.combatLeash) {
                this.brain.addEvent("lost " + entityId(target));
                endCombat();
                return false;
            }

            this.lookControl.setLookAt(target);
            boolean inReach = distSqr <= ATTACK_REACH * ATTACK_REACH;
            if (inReach) {
                this.navigation.stop();
                if (CombatPolicy.canAttack(time, this.combatNextAttackAt)) {
                    this.swing(InteractionHand.MAIN_HAND);
                    this.doHurtTarget(target);
                    this.combatNextAttackAt = time + ModConfig.COMBAT_ATTACK_INTERVAL_TICKS.get();
                    this.combatLastHitAt = time;
                    if (!target.isAlive()) {
                        recordKill(target);
                        endCombat();
                        return false;
                    }
                }
                this.setDisplayState("FIGHT", entityId(target), "attacking");
            } else {
                if (time - this.combatLastHitAt >= ModConfig.COMBAT_CHASE_TIMEOUT_TICKS.get()) {
                    // bounded engagement: never chase one unreachable target forever
                    this.brain.addEvent("gave up on " + entityId(target));
                    endCombat();
                    return false;
                }
                if (CombatPolicy.shouldRepath(time, this.combatLastRepathAt, COMBAT_REPATH_INTERVAL)) {
                    this.navigator.moveTo(target, 1.0D);
                    this.combatLastRepathAt = time;
                }
                this.setDisplayState("FIGHT", entityId(target), "chasing");
            }
            return true;
        }

        // Out of combat: throttled threat scan (cheap — every 5th tick).
        if (time % COMBAT_SCAN_INTERVAL != 0) {
            return false;
        }
        Mob found = findCombatTarget(server);
        if (found == null) {
            // findCombatTarget may have decided we should be running instead.
            return this.fleeFrom != null && tickFlee(server, time);
        }
        engage(found, time);
        return true;
    }

    /**
     * Look at every nearby hostile and decide what this citizen should do about
     * it. The nearest threat that must be fled from wins over any fight: being
     * next to a creeper is more urgent than a zombie two blocks further away.
     */
    private Mob findCombatTarget(ServerLevel server) {
        this.fleeFrom = null;
        double radius = CombatPolicy.effectiveRadius(
                ModConfig.COMBAT_TRIGGER_RADIUS.get(), this.personality.riskTolerance);
        double scanRadius = Math.max(Math.max(radius, ATTACKER_LEASH),
                CombatPolicy.DEADLY_AVOID_RADIUS);
        boolean starving = this.hunger < HUNGER_STARVING;
        boolean armed = this.hasWeapon();
        float healthFraction = this.getMaxHealth() <= 0 ? 1f : this.getHealth() / this.getMaxHealth();
        LivingEntity attacker = this.getLastHurtByMob();
        boolean attackerRecent =
                this.tickCount - this.getLastHurtByMobTimestamp() <= ATTACKER_RECENT_TICKS;

        AABB box = this.getBoundingBox().inflate(scanRadius);
        Mob bestFight = null;
        double bestFightDist = Double.MAX_VALUE;
        Mob bestFlee = null;
        double bestFleeDist = Double.MAX_VALUE;
        CombatPolicy.ThreatKind bestFleeKind = CombatPolicy.ThreatKind.MELEE;

        // Enemy is a marker interface (not an Entity subtype): scan mobs, filter with instanceof
        for (Mob candidate : server.getEntitiesOfClass(Mob.class, box)) {
            if (!(candidate instanceof Enemy) || !candidate.isAlive()) continue;
            double distSqr = this.distanceToSqr(candidate);
            if (distSqr > scanRadius * scanRadius) continue; // inflate() box reaches corners

            boolean recentAttacker = attackerRecent && attacker == candidate;
            CombatPolicy.Response response = CombatPolicy.assess(true,
                    CombatPolicy.classify(entityId(candidate)), distSqr, radius, ATTACKER_LEASH,
                    recentAttacker, healthFraction, starving, armed);

            if (response == CombatPolicy.Response.FLEE && distSqr < bestFleeDist) {
                bestFlee = candidate;
                bestFleeDist = distSqr;
                bestFleeKind = CombatPolicy.classify(entityId(candidate));
            } else if (response == CombatPolicy.Response.ENGAGE && distSqr < bestFightDist) {
                bestFight = candidate;
                bestFightDist = distSqr;
            }
        }

        if (bestFlee != null) {
            if (this.fleeFrom != bestFlee) {
                this.fleeStartedAt = this.level().getGameTime();
            }
            this.fleeFrom = bestFlee;
            this.fleeKind = bestFleeKind;
            return null;   // running takes precedence over any available fight
        }
        return bestFight;
    }

    /** Something worth swinging: a sword or an axe beats bare hands. */
    private boolean hasWeapon() {
        for (int slot = 0; slot < this.inventory.getContainerSize(); slot++) {
            var stack = this.inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            String id = CitizenInventory.idOf(stack);
            if (id.endsWith("_sword") || id.endsWith("_axe")) return true;
        }
        return false;
    }

    /**
     * Put distance between the citizen and something it must not fight.
     *
     * <p>Running directly away can corner a citizen against the terrain, so the
     * retreat aims for a point away from the threat and biased toward the
     * colony — help, walls and daylight are all more likely at home.</p>
     *
     * @return true while a retreat is driving movement this tick
     */
    private boolean tickFlee(ServerLevel server, long time) {
        Mob threat = this.fleeFrom;
        if (threat == null || !threat.isAlive()) {
            this.fleeFrom = null;
            return false;
        }
        double distSqr = this.distanceToSqr(threat);
        double safe = CombatPolicy.safeDistance(this.fleeKind,
                CombatPolicy.effectiveRadius(ModConfig.COMBAT_TRIGGER_RADIUS.get(),
                        this.personality.riskTolerance));
        if (distSqr > safe * safe) {
            this.fleeFrom = null;
            return false;   // far enough; get back to work
        }
        // A threat that cannot be escaped — behind a wall, or following us round
        // a pen — must not cost a citizen the rest of its life.
        if (time - this.fleeStartedAt > CombatPolicy.MAX_FLEE_TICKS) {
            this.fleeFrom = null;
            this.brain.addEvent("stopped running from " + entityId(threat));
            return false;
        }

        if (CombatPolicy.shouldRepath(time, this.combatLastRepathAt, COMBAT_REPATH_INTERVAL)) {
            net.minecraft.world.phys.Vec3 away = this.position().subtract(threat.position());
            if (away.lengthSqr() < 1.0e-4) {
                away = new net.minecraft.world.phys.Vec3(1, 0, 0);
            }
            away = away.normalize().scale(12.0);

            // Bias the retreat homeward rather than deeper into the wild.
            BlockPos home = ai.minecivilization.colony.ZoneManager.get(server).townCenter(server);
            net.minecraft.world.phys.Vec3 homeward =
                    new net.minecraft.world.phys.Vec3(home.getX() + 0.5 - this.getX(), 0,
                            home.getZ() + 0.5 - this.getZ());
            if (homeward.lengthSqr() > 1.0) {
                away = away.add(homeward.normalize().scale(6.0));
            }

            BlockPos destination = BlockPos.containing(this.getX() + away.x, this.getY(),
                    this.getZ() + away.z);
            this.navigator.stop();
            this.navigator.moveTo(destination, 1.3D);
            this.combatLastRepathAt = time;
        }
        this.navigator.tick();
        this.setDisplayState("FLEE", entityId(threat),
                "backing off (" + (int) Math.sqrt(distSqr) + "m)");
        return true;
    }

    private void engage(Mob target, long time) {
        // Same safety rule as getting hurt: no half-done task during a fight.
        if (this.executor.hasActiveTask() || this.brain.currentTaskOrNull() != null) {
            this.brain.stopAll(this);
        }
        this.navigator.stop();
        this.combatTarget = target;
        this.combatLeash = Math.max(
                CombatPolicy.effectiveRadius(ModConfig.COMBAT_TRIGGER_RADIUS.get(),
                        this.personality.riskTolerance),
                ATTACKER_LEASH) + 8.0D;
        this.combatLastHitAt = time;
        this.combatNextAttackAt = time;   // first swing as soon as in reach
        this.combatLastRepathAt = time;
        this.navigator.moveTo(target, 1.0D); // start closing distance immediately
        this.setDisplayState("FIGHT", entityId(target), "chasing");
        this.brain.addEvent("engaging " + entityId(target));
        this.markDirty();
        LOGGER.info("[Citizen {}] engaging hostile {}", this.getName().getString(),
                entityId(target));
    }

    private void endCombat() {
        this.combatTarget = null;
        // the real state reasserts on the next brain tick (or IDLE if gated)
        this.setDisplayState("IDLE", "", "");
    }

    private void recordKill(Mob dead) {
        String id = entityId(dead);
        this.memory.merge("defeated:" + id, 1.0f, Float::sum);
        this.brain.addEvent("defeated " + id);
        this.markDirty();
        LOGGER.info("[Citizen {}] defeated {}", this.getName().getString(), id);
    }

    private static String entityId(Mob entity) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(entity.getType()).toString();
    }

    public boolean isInCombat() {
        return this.combatTarget != null;
    }

    /** e.g. "minecraft:zombie" — for /mciv inspect. */
    public String combatTargetId() {
        return this.combatTarget == null ? "-" : entityId(this.combatTarget);
    }

    // ------------------------------------------------------------------ helpers

    public void markDirty() {
        this.changed = true;
    }

    /** True when the observation changed since the last tick. */
    public boolean consumeChangedFlag() {
        boolean was = this.changed;
        this.changed = false;
        return was;
    }

    /**
     * Pathfinding speed multiplier derived from the movement attribute:
     * competence never teleports the citizen, it only nudges walking speed.
     */
    public double navigationSpeed() {
        AttributeInstance attribute = this.getAttribute(Attributes.MOVEMENT_SPEED);
        double base = attribute == null ? 0.30D : attribute.getValue();
        return Mth.clamp(base / 0.30D, 0.5D, 2.0D);
    }

    public void requestObservationRefresh() {
        this.changed = true;
    }

    public void onInventoryChanged() {
        this.changed = true;
    }

    public void playWorkParticles(BlockPos pos, BlockState state) {
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.0);
        }
    }

    public void playProgressParticles(BlockPos pos) {
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.CRIT,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 2, 0.15, 0.15, 0.15, 0.0);
        }
    }

    public int countItem(net.minecraft.world.item.Item item) {
        return this.inventory.countItem(item);
    }

    public boolean consumeItemAtMost(net.minecraft.world.item.Item item, int max) {
        if (this.countItem(item) < max) {
            return false;
        }
        int remaining = max;
        for (int i = 0; i < this.inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                if (stack.isEmpty()) {
                    this.inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        this.onInventoryChanged();
        return true;
    }

    public Vec3 positionVec() {
        return this.position();
    }

    public Direction facingDirection() {
        return this.getDirection();
    }

    public BlockState getBlockStateAt(BlockPos pos) {
        return this.level().getBlockState(pos);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (super.hurt(source, amount)) {
            // Getting hurt cancels the current work: safety first, no partial plan execution.
            if (!this.level().isClientSide && this.executor.hasActiveTask()) {
                this.navigator.setBlockedPaths(false);
                this.brain.stopAll(this);
                this.setStatus("degraded");
            }
            return true;
        }
        return false;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!this.level().isClientSide) {
            CitizenIndex.remove(this);
            dropInventory();
        }
    }

    private void dropInventory() {
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty()) {
                this.spawnAtLocation(stack.copy());
                this.inventory.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        // Movement is handled by CitizenNavigator; nothing extra here.
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public int getMaxHeadYRot() {
        return 35;
    }
}
