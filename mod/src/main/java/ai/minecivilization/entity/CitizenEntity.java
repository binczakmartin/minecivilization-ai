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
import ai.minecivilization.skills.SkillType;
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
import net.minecraft.world.entity.Pose;
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
    private static final EntityDataAccessor<Byte> DATA_WORK_ACTION =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Long> DATA_WORK_ACTION_START =
            SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.LONG);

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
    private static final String TAG_SHELTER = "ShelterBlocks";
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
    /** Blocks put up as tonight's shelter, to be taken down again at dawn. */
    private final List<BlockPos> shelterBlocks = new ArrayList<>();
    /** True while sitting the night out inside a finished shelter. */
    private boolean sheltered;
    /** Exact state owned by each temporary support, so cleanup cannot remove a replacement. */
    private final Map<BlockPos, BlockState> scaffoldStates = new HashMap<>();

    private CitizenIdentity identity = new CitizenIdentity();
    private CitizenPersonality personality = new CitizenPersonality();
    private final CitizenSkills skills = new CitizenSkills();
    private CitizenBrain brain;
    private CitizenNavigator navigator;
    /**
     * Breadcrumbs for the colony's road network.
     *
     * <p>Every journey this citizen completes is offered to the shared
     * {@link ai.minecivilization.roads.PathMemory}. Nothing has to ask for a
     * route to be recorded, which is why the network fills up at all.</p>
     */
    private final ai.minecivilization.roads.TripRecorder trips =
            new ai.minecivilization.roads.TripRecorder();
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

    /** True while a route is being walked with edge protection. */
    private boolean traversalSneak;
    /** True while a terrain operation owns a scaffold/pillar transition. */
    private boolean bracedPlacement;
    /** Server-side timestamp of the last work pulse; never persisted. */
    private long lastActionAnimationAt = Long.MIN_VALUE;
    private long lastActionAnimationTick = Long.MIN_VALUE;
    /** Prevents repeated emergency repositioning in the same wall. */
    private int escapeCooldown;
    /** True only during the atomic block-under-feet transaction. */
    private boolean selfSupportTransaction;
    /** Allows one explicitly planned FALL/DIG_DOWN edge. */
    private boolean controlledDrop;

    public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = 5;
        // PathfinderMob already built `navigation` in super(); wrap it deterministically.
        //
        // Ground navigation refuses to enter water unless it is told it may
        // float. Without this a citizen treated a stream as a wall, a lake as
        // the edge of the world, and — having fallen into either — could not
        // path its way out of the cell it was standing in.
        this.navigation.setCanFloat(true);
        // Never plan a walk across a tree canopy. Vanilla lets mobs stroll over
        // leaves; a citizen that did so ended its path ten blocks up, fell off
        // the edge of the canopy and died — and the ones that did not were left
        // stranded in treetops, pillaring down on dirt.
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LEAVES, -1.0F);
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
        builder.define(DATA_WORK_ACTION, (byte) WorkAnimation.NONE.ordinal());
        builder.define(DATA_WORK_ACTION_START, 0L);
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

    /**
     * Enable the short, edge-safe walking mode used by MOVE_TO and work-site
     * approaches.  It is intentionally separate from braced scaffold work so
     * combat, idle wandering and ordinary errands do not leave a citizen
     * crouching forever.
     */
    public void setTraversalSneak(boolean sneaking) {
        this.traversalSneak = sneaking;
        updateWorkPose();
    }

    /** Keep the worker braced while it owns a pillar/bridge transaction. */
    public void setBracedPlacement(boolean braced) {
        this.bracedPlacement = braced;
        updateWorkPose();
    }

    public boolean isSafeMovement() {
        return this.traversalSneak || this.bracedPlacement;
    }

    public boolean isBracedPlacement() {
        return this.bracedPlacement;
    }

    /** True while the current floor is one of this worker's temporary supports. */
    public boolean isOnOwnedScaffold() {
        return this.scaffoldPlaced.contains(this.blockPosition().below());
    }

    /** Mark the intentional, atomic pillar placement window. */
    public void beginSelfSupportTransaction() {
        this.selfSupportTransaction = true;
    }

    public void endSelfSupportTransaction() {
        this.selfSupportTransaction = false;
    }

    public void setControlledDrop(boolean allowed) {
        this.controlledDrop = allowed;
    }

    private void updateWorkPose() {
        boolean crouching = isSafeMovement();
        this.setShiftKeyDown(crouching);
        if (crouching) {
            this.setPose(Pose.CROUCHING);
        } else if (this.level() == null || canStandAtCurrentPosition()) {
            this.setPose(Pose.STANDING);
        }
    }

    private boolean canStandAtCurrentPosition() {
        if (this.level() == null) return true;
        var dimensions = this.getDimensions(Pose.STANDING);
        return this.level().noCollision(this,
                dimensions.makeBoundingBox(this.getX(), this.getY(), this.getZ()));
    }

    /** Start/repeat a generic work pulse (kept for small legacy skills). */
    public void animateAction() {
        animateAction(WorkAnimation.REACH, null);
    }

    /**
     * Publish a semantic work gesture and a vanilla arm-swing pulse together.
     * The timestamp is server game time, so the pulse remains rate-limited even
     * when several nested skills touch the same citizen in one tick.
     */
    public void animateAction(WorkAnimation action, @Nullable BlockPos target) {
        if (action == null || action == WorkAnimation.NONE) {
            clearWorkAnimation();
            return;
        }
        if (this.level() == null || this.level().isClientSide) return;

        long now = this.level().getGameTime();
        if (target != null) {
            this.lookControl.setLookAt(Vec3.atCenterOf(target));
        }
        WorkAnimation current = getWorkAnimation();
        boolean changed = current != action;
        boolean due = lastActionAnimationAt == Long.MIN_VALUE
                || now < lastActionAnimationAt
                || now - lastActionAnimationAt >= action.intervalTicks();
        if (!changed && !due) return;

        this.entityData.set(DATA_WORK_ACTION, (byte) action.ordinal());
        this.entityData.set(DATA_WORK_ACTION_START, now);
        this.lastActionAnimationAt = now;
        refreshEquipmentDisplay();
        if (lastActionAnimationTick == now) {
            lastActionAnimationTick = now;
            return;
        }
        lastActionAnimationTick = now;
        // true broadcasts to the server's nearby players, including the owner.
        this.swing(InteractionHand.MAIN_HAND, true);
    }

    public WorkAnimation getWorkAnimation() {
        return WorkAnimation.byOrdinal(this.entityData.get(DATA_WORK_ACTION));
    }

    public long getWorkAnimationStart() {
        return this.entityData.get(DATA_WORK_ACTION_START);
    }

    public void clearWorkAnimation() {
        if (getWorkAnimation() != WorkAnimation.NONE) {
            this.entityData.set(DATA_WORK_ACTION, (byte) WorkAnimation.NONE.ordinal());
            this.entityData.set(DATA_WORK_ACTION_START, 0L);
            refreshEquipmentDisplay();
        }
        this.lastActionAnimationAt = Long.MIN_VALUE;
        this.lastActionAnimationTick = Long.MIN_VALUE;
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
    /**
     * Eat one real item from the pack, now.
     *
     * <p>This used to set hunger straight to full without taking anything out
     * of the inventory — a free meal whenever a citizen merely owned food,
     * which hid every food shortage the colony actually had.</p>
     */
    public boolean eatNow() {
        float before = this.hunger;
        eatFromInventoryIfPossible();
        this.changed = true;
        return this.hunger > before;
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
        this.shelterBlocks.clear();
        for (long packed : tag.getLongArray(TAG_SHELTER)) {
            this.shelterBlocks.add(BlockPos.of(packed));
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
        tag.putLongArray(TAG_SHELTER, this.shelterBlocks.stream().mapToLong(BlockPos::asLong).toArray());
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (this.level().isClientSide) {
            return;
        }
        this.setTraversalSneak(false);
        this.setBracedPlacement(false);
        this.setControlledDrop(false);
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

    /** Record a throwaway block and the exact state placed there. */
    public void rememberScaffold(BlockPos pos, BlockState state) {
        if (pos == null) return;
        BlockPos immutable = pos.immutable();
        if (!this.scaffoldPlaced.contains(immutable)) {
            this.scaffoldPlaced.add(immutable);
        }
        if (state != null) this.scaffoldStates.put(immutable, state);
        // A citizen should never be dragging a hundred of these around. Keep
        // the state map in sync rather than silently losing cleanup ownership.
        while (this.scaffoldPlaced.size() > 256) {
            BlockPos oldest = this.scaffoldPlaced.remove(0);
            this.scaffoldStates.remove(oldest);
        }
    }

    /** Compatibility overload for callers that only have a position. */
    public void rememberScaffold(BlockPos pos) {
        rememberScaffold(pos, null);
    }

    public BlockState scaffoldState(BlockPos pos) {
        return pos == null ? null : this.scaffoldStates.get(pos.immutable());
    }

    /** Throwaway blocks still standing, highest first — take a tower down from the top. */
    public List<BlockPos> scaffoldPlaced() {
        List<BlockPos> sorted = new ArrayList<>(this.scaffoldPlaced);
        sorted.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        return sorted;
    }

    public void forgetScaffold(BlockPos pos) {
        if (pos == null) return;
        BlockPos immutable = pos.immutable();
        this.scaffoldPlaced.remove(immutable);
        this.scaffoldStates.remove(immutable);
    }

    public void rememberShelterBlock(BlockPos pos) {
        BlockPos immutable = pos.immutable();
        if (!this.shelterBlocks.contains(immutable)) this.shelterBlocks.add(immutable);
        this.markDirty();
    }

    public List<BlockPos> shelterBlocks() {
        return List.copyOf(this.shelterBlocks);
    }

    public void forgetShelterBlock(BlockPos pos) {
        this.shelterBlocks.remove(pos);
        this.markDirty();
    }

    public boolean isSheltered() {
        return this.sheltered;
    }

    public void setSheltered(boolean sheltered) {
        this.sheltered = sheltered;
    }

    public void forgetAllScaffold() {
        this.scaffoldPlaced.clear();
        this.scaffoldStates.clear();
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
        if (this.escapeCooldown > 0) this.escapeCooldown--;
        // A combat cancel or timeout must not leave a worker standing on a
        // temporary tower without the same edge guard used by normal TRAVERSE —
        // but only a tower worth guarding. Bracing on every scaffold block pinned
        // citizens to one-block dirt pillars for whole sessions: a hop down is
        // harmless, and the guard stopped them taking it.
        if (!this.bracedPlacement && this.isOnOwnedScaffold() && dangerousDropAround()) {
            this.setBracedPlacement(true);
        }
        long time = server.getGameTime();

        // Account for this tick before anything can return early. "They spend
        // most of their time doing nothing" is either the most important fact
        // about this colony or a misreading, and only a running total can say.
        ai.minecivilization.telemetry.ActivityLedger.sample(this, time);

        // Leave a trail. A journey that ends somewhere becomes a route the
        // whole colony can follow, and eventually a road it maintains.
        ai.minecivilization.roads.Route learned = this.trips.tick(server, this.blockPosition());
        if (learned != null && learned.uses == 1) {
            ai.minecivilization.telemetry.ColonyEventLog.of(server).infrastructure(server,
                    "a new route is known: " + learned.displayName()
                            + " (" + learned.lengthBlocks + " blocks)");
        }

        // Passive hunger: work costs energy, doing nothing costs less — and a
        // night spent sitting in a shelter costs least of all.
        if (time % 40 == 0) {
            SkillType resting = this.executor.activeSkillType();
            // Every 40 ticks. 0.35 here emptied a full belly in under ten
            // minutes of work — half a day — and with food still scarce that
            // sent the whole colony hunting for game that was not there.
            // Now a meal lasts about a working day, like a player's.
            float drain = resting == SkillType.SHELTER || resting == SkillType.SLEEP ? 0.03f
                    : this.executor.hasActiveTask() ? 0.17f : 0.06f;
            this.setHunger(this.hunger - drain);
        }

        // Natural healing. Citizens never regained a single point of health,
        // so every arrow and every zombie scratch added up until one more
        // finished them — which is why working after dark was a death sentence.
        // Like a player: quickly when fed, slowly on an empty stomach, and not
        // at all in the middle of a fight.
        if (this.isAlive() && this.getHealth() < this.getMaxHealth()
                && this.combatTarget == null
                && this.tickCount - this.getLastHurtByMobTimestamp() > 100
                && time % (this.hunger >= 30.0f ? 40 : 200) == 0) {
            this.heal(1.0f);
        }

        // Roads are faster, which is what they are for.
        if (time % 10 == 0) updateRoadSpeed();

        // Keep an eye out for wild livestock: the colony's herd starts from a
        // sighting, wherever it was.
        if ((time + this.getId()) % 200 == 0) {
            ai.minecivilization.livestock.AnimalSightings.glance(server, this);
        }

        // Eating is a local deterministic action: only from a real food stack.
        // Between jobs at 60; mid-job only once properly hungry, the way a
        // player eats without putting the pickaxe down.
        if (time % 20 == 0 && (this.hunger < 60.0f && !this.executor.hasActiveTask()
                || this.hunger < 30.0f)) {
            eatFromInventoryIfPossible();
        }

        // Deterministic self-defence runs first: combat is survival, not
        // policy, so it reacts locally and never waits for the AI service.
        this.tickMarker();
        if (time % 20 == 0) {
            this.syncEquipmentDisplay();
        }
        boolean fighting = this.tickCombat(server, time);
        if (this.bracedPlacement && !fighting && (!this.executor.hasActiveTask()
                || this.executor.activeSkillType() != SkillType.TRAVERSE)
                && (!this.isOnOwnedScaffold() || !dangerousDropAround())) {
            this.setBracedPlacement(false);
        }
        SkillType activeSkill = this.executor.activeSkillType();
        if (!fighting && (!this.executor.hasActiveTask()
                || activeSkill == null
                || activeSkill == SkillType.IDLE
                || activeSkill == SkillType.MOVE_TO
                || activeSkill == SkillType.TRAVERSE
                || activeSkill == SkillType.FIND_BLOCK
                || activeSkill == SkillType.SLEEP)) {
            this.clearWorkAnimation();
        }

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

    /**
     * What to eat now.
     *
     * <p>The good food while there is any; the rotten flesh only once hunger
     * has become the more dangerous of the two. A citizen that eats zombie
     * meat with bread in its pack is not being resourceful, it is poisoning
     * itself for no reason — and one that starves beside a stack of flesh it
     * refused is worse.</p>
     */
    private int findFoodSlot() {
        boolean desperate = this.hunger <= HUNGER_STARVING + 15f;
        return this.inventory.bestFoodSlot(desperate);
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

    /** Refresh the vanilla equipment slots when a semantic action changes. */
    public void refreshEquipmentDisplay() {
        if (!this.level().isClientSide) syncEquipmentDisplay();
    }

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
        String suffix = switch (getWorkAnimation()) {
            case MINE -> "_pickaxe";
            case CHOP -> "_axe";
            case TILL -> "_hoe";
            case PLACE, BUILD -> "_axe";
            case COMBAT -> "_sword";
            case NONE -> ai.minecivilization.citizen.CitizenLook.toolSuffixFor(
                    this.identity.profession, task == null ? null : task.type.name());
            default -> null;
        };

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
        if (ItemStack.isSameItemSameComponents(shown, owned)) return;
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

    /**
     * Keep the citizen findable.
     *
     * <p>Two levels. The outline, toggled by {@code /mciv highlight}, is what
     * finds somebody through a hillside. On top of that, a citizen who is lost
     * or stuck glows unconditionally: the whole point of detecting trouble is
     * that a player can then see where it is, and a stranded citizen that
     * looks exactly like a working one is trouble nobody acts on.</p>
     */
    private void tickMarker() {
        boolean wanted = ai.minecivilization.colony.CitizenMarkers.highlighted()
                || (this.brain != null && this.brain.isLost());
        if (this.hasGlowingTag() != wanted) {
            this.setGlowingTag(wanted);
        }
    }

    /**
     * Deterministic self-defence: engage the nearest hostile mob inside a
     * personality-scaled radius (or whoever just hurt us), chase it and punch
     * it on a cooldown. Runs locally every tick — like eating, fighting is a
     * survival reflex, never an AI decision.
     *
     * @return true while a fight is driving movement this tick
     */
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
            // Walled in for the night: whatever is prowling outside cannot get
            // in, and stepping out to fight it is the one way to lose. Only
            // something already landing blows gets an answer.
            if (this.sheltered && !(recentAttacker
                    && distSqr <= CombatPolicy.CORNERED_RANGE * CombatPolicy.CORNERED_RANGE)) {
                continue;
            }
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

    private static final net.minecraft.resources.ResourceLocation ROAD_SPEED =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecivilization", "road_speed");

    /** +30% walking speed on a path, gravel, cobble or stone road. */
    private void updateRoadSpeed() {
        var attribute = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attribute == null) return;
        BlockState under = this.level().getBlockState(this.blockPosition().below());
        BlockState at = this.level().getBlockState(this.blockPosition());
        boolean road = at.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
                || under.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
                || under.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                || under.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)
                || under.is(net.minecraft.world.level.block.Blocks.STONE_BRICKS);
        boolean has = attribute.hasModifier(ROAD_SPEED);
        if (road && !has) {
            attribute.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                    ROAD_SPEED, 0.3, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        } else if (!road && has) {
            attribute.removeModifier(ROAD_SPEED);
        }
    }

    /** Carrying a sword or an axe. */
    public boolean isArmed() {
        return hasWeapon();
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
        boolean explosive = this.fleeKind == CombatPolicy.ThreatKind.EXPLOSIVE;
        // A creeper hissing at arm's length cannot be outrun any more. Hit it:
        // the knock-back carries it out of range and its fuse winds down —
        // the move every player learns on their first night.
        if (explosive && distSqr <= 3.2 * 3.2 && hasWeapon()
                && threat instanceof net.minecraft.world.entity.monster.Creeper creeper
                && creeper.getSwellDir() > 0
                && CombatPolicy.canAttack(time, this.combatNextAttackAt)) {
            this.syncEquipmentDisplay();
            ItemStack weapon = bestMatching("_sword");
            if (weapon.isEmpty()) weapon = bestMatching("_axe");
            this.setDisplayItem(EquipmentSlot.MAINHAND, weapon);
            this.lookControl.setLookAt(creeper);
            this.swing(InteractionHand.MAIN_HAND);
            this.doHurtTarget(creeper);
            creeper.knockback(1.2D, this.getX() - creeper.getX(), this.getZ() - creeper.getZ());
            this.combatNextAttackAt = time + ModConfig.COMBAT_ATTACK_INTERVAL_TICKS.get();
        }
        // A threat that cannot be escaped — behind a wall, or following us round
        // a pen — must not cost a citizen the rest of its life. A creeper still
        // close enough to hurt is the exception: stopping then is the death.
        boolean stillInBlast = explosive
                && distSqr <= (CombatPolicy.BLAST_DANGER_RADIUS + 2) * (CombatPolicy.BLAST_DANGER_RADIUS + 2);
        if (!stillInBlast && time - this.fleeStartedAt > CombatPolicy.MAX_FLEE_TICKS) {
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

            // Try straight away first, then either side: running into a
            // hillside or a river because that was "directly away" is how
            // backing-off citizens were caught.
            boolean moving = false;
            for (int turn : new int[]{0, 60, -60, 110, -110}) {
                double rad = Math.toRadians(turn);
                double ax = away.x * Math.cos(rad) - away.z * Math.sin(rad);
                double az = away.x * Math.sin(rad) + away.z * Math.cos(rad);
                int x = net.minecraft.util.Mth.floor(this.getX() + ax);
                int z = net.minecraft.util.Mth.floor(this.getZ() + az);
                // Aim at the ground there, not at our own height inside a hill.
                int y = server.isLoaded(new BlockPos(x, this.getBlockY(), z))
                        ? server.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                        : this.getBlockY();
                this.navigator.stop();
                if (this.navigator.moveTo(new BlockPos(x, y, z), 1.3D)
                        && this.navigation.getPath() != null) {
                    moving = true;
                    break;
                }
            }
            if (!moving) this.navigator.stop();
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
        this.clearWorkAnimation();
        this.setBracedPlacement(false);
        this.setTraversalSneak(false);
        this.setControlledDrop(false);
        this.combatTarget = target;
        // Weapon in hand before the first swing, not at the next display sync
        // a second later: attack damage comes from the main-hand item.
        this.syncEquipmentDisplay();
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
        this.navigator.stop();
        this.setBracedPlacement(false);
        this.setTraversalSneak(false);
        this.setControlledDrop(false);
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
        if (!this.level().isClientSide && "suffocated".equals(source.getMsgId())
                && recoverFromWallCollision()) {
            // The extraction succeeded, so this particular damage tick is
            // avoided.  If no free cell exists we deliberately fall through to
            // vanilla damage instead of making a citizen immortal in a wall.
            return false;
        }
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
        recoverFromWallCollision();
    }

    /**
     * A path can legally end at a cell that another worker later fills.  Pull
     * the citizen out before suffocation damage cancels the whole plan.  The
     * AABB check and the success post-condition keep this an emergency escape,
     * not a permanent damage immunity or a free teleport during normal work.
     */
    private boolean recoverFromWallCollision() {
        if (this.selfSupportTransaction || this.escapeCooldown > 0) return false;
        if (this.level() == null || this.level().noCollision(this, this.getBoundingBox())) {
            return false;
        }

        BlockPos escape = findEscapeCell(this.blockPosition());
        if (escape == null) escape = findWiderEscapeCell(this.blockPosition());
        if (escape == null) {
            // Sealed in (a neighbour's wall went up around it): dig out, as a
            // player would, rather than suffocate politely. Builders put a
            // missing block back; nobody brings a citizen back.
            return digOutOfWall();
        }

        Vec3 oldPosition = this.position();
        Vec3 oldVelocity = this.getDeltaMovement();
        float oldFallDistance = this.fallDistance;
        boolean oldOnGround = this.onGround();
        boolean oldSafeStep = this.traversalSneak;
        boolean oldBraced = this.bracedPlacement;
        this.navigator.halt();
        this.setPos(escape.getX() + 0.5, escape.getY(), escape.getZ() + 0.5);
        this.setDeltaMovement(Vec3.ZERO);
        this.fallDistance = 0.0f;
        this.setOnGround(true);
        if (this.level().noCollision(this, this.getBoundingBox())) {
            this.escapeCooldown = 20;
            this.navigator.releaseSafeStep();
            this.setBracedPlacement(false);
            this.setTraversalSneak(false);
            LOGGER.warn("[Citizen {}] extracted from an occupied AABB at {}",
                    this.getName().getString(), this.blockPosition());
            return true;
        }

        // Never leave a half-completed extraction if the candidate changed in
        // the same server tick.  Restore the exact pre-recovery state.
        this.setPos(oldPosition);
        this.setDeltaMovement(oldVelocity);
        this.fallDistance = oldFallDistance;
        this.setOnGround(oldOnGround);
        this.setTraversalSneak(oldSafeStep);
        this.setBracedPlacement(oldBraced);
        return false;
    }

    private BlockPos findEscapeCell(BlockPos feet) {
        BlockPos[] candidates = {
                feet.above(), feet.north(), feet.south(), feet.east(), feet.west(),
                feet.above().north(), feet.above().south(),
                feet.above().east(), feet.above().west(),
                feet.below(), feet.below().north(), feet.below().south(),
                feet.below().east(), feet.below().west()
        };
        var dimensions = this.getDimensions(getPose());
        for (BlockPos candidate : candidates) {
            if (!this.level().isLoaded(candidate)) continue;
            AABB body = dimensions.makeBoundingBox(
                    candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
            if (!this.level().noCollision(this, body)) continue;
            BlockState floor = this.level().getBlockState(candidate.below());
            if (!(floor.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)
                    && floor.isFaceSturdy(this.level(), candidate.below(), Direction.UP)
                    && isCellFreeOfOtherLivingEntities(body)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    /** Standable free cells up to two blocks away, nearest first. */
    private BlockPos findWiderEscapeCell(BlockPos feet) {
        var dimensions = this.getDimensions(getPose());
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(feet.offset(-2, -2, -2), feet.offset(2, 2, 2))) {
            double d = candidate.distSqr(feet);
            if (d >= bestDist || !this.level().isLoaded(candidate)) continue;
            AABB body = dimensions.makeBoundingBox(
                    candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
            if (!this.level().noCollision(this, body)) continue;
            BlockState floor = this.level().getBlockState(candidate.below());
            if (floor.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                    || !floor.isFaceSturdy(this.level(), candidate.below(), Direction.UP)
                    || !isCellFreeOfOtherLivingEntities(body)) continue;
            best = candidate.immutable();
            bestDist = d;
        }
        return best;
    }

    /** Break whatever fills the body's cells (never bedrock-like blocks). */
    private boolean digOutOfWall() {
        BlockPos feet = this.blockPosition();
        boolean dug = false;
        for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
            BlockState state = this.level().getBlockState(cell);
            if (state.isAir() || state.getCollisionShape(this.level(), cell).isEmpty()) continue;
            if (state.getDestroySpeed(this.level(), cell) < 0) return false;
            this.level().destroyBlock(cell, true, this);
            dug = true;
        }
        if (!dug || !this.level().noCollision(this, this.getBoundingBox())) return false;
        this.escapeCooldown = 20;
        LOGGER.warn("[Citizen {}] dug itself out of a wall at {}", this.getName().getString(), feet);
        return true;
    }

    private boolean isCellFreeOfOtherLivingEntities(AABB body) {
        for (net.minecraft.world.entity.LivingEntity other :
                this.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        body.inflate(1.0))) {
            if (other != this && other.getBoundingBox().intersects(body)) return false;
        }
        return true;
    }

    @Override
    public void travel(Vec3 input) {
        // The cliff rule applies to every step, not only to a task's careful
        // "safe step" mode: a citizen following an ordinary path walked off a
        // fourteen-block hillside between two re-paths, the same one, in every
        // run. The stricter scaffold rules still only apply while braced.
        if (blocksSneakEdge(input)) {
            Vec3 motion = this.getDeltaMovement();
            this.setDeltaMovement(0.0, motion.y, 0.0);
            input = new Vec3(0.0, input.y, 0.0);
        }
        super.travel(input);
    }

    /** Local movement input (strafe, up, forward) as a world-space direction. */
    private Vec3 worldDirection(Vec3 local) {
        float yaw = this.getYRot() * ((float) Math.PI / 180F);
        double sin = Mth.sin(yaw);
        double cos = Mth.cos(yaw);
        return new Vec3(local.x * cos - local.z * sin, local.y, local.z * cos + local.x * sin);
    }

    /** Standing on, or at the edge of, a block this citizen placed to stand on. */
    private boolean nearOwnedScaffold() {
        if (this.scaffoldPlaced.isEmpty()) return false;
        BlockPos below = this.blockPosition().below();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (this.scaffoldPlaced.contains(below.offset(dx, 0, dz))) return true;
            }
        }
        return false;
    }

    /** Stepping off this spot in some direction would fall more than three blocks. */
    private boolean dangerousDropAround() {
        BlockPos feet = this.blockPosition();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = feet.relative(side);
            int drop = 0;
            while (drop <= 3 && !hasFloorAt(next.below(drop))) drop++;
            if (drop > 3) return true;
        }
        return false;
    }

    private boolean blocksSneakEdge(Vec3 localInput) {
        // travel() receives the mob's *local* input — strafe, up, forward —
        // not a world direction. Reading it as world x/z meant "forward" was
        // always checked as "south", and every riverbank and pillar edge froze
        // someone in place. Rotate it the way vanilla's own movement does.
        // Nobody falls off a ledge while swimming.
        if (this.isInWater()) return false;
        Vec3 input = worldDirection(localInput);
        Vec3 flat = new Vec3(input.x, 0.0, input.z);
        if (flat.lengthSqr() < 1.0e-6) return false;
        BlockPos feet = this.blockPosition();

        // The strict rules are for a worker up on its own pillar or bridge.
        // TRAVERSE braces for its whole route, natural ground included, and
        // applying them there refused ordinary steps down and held citizens
        // hanging over the lip of every slope.
        boolean strict = this.bracedPlacement && nearOwnedScaffold();
        // No steering in mid-fall. A citizen stepping down a staircase of
        // two-block ledges kept walking forward through the air, drifted over
        // ledge after ledge without ever landing, and hit the bottom fourteen
        // blocks down. Falling straight lands it on the first step, where the
        // edge check gets to look again.
        if (!this.onGround() && this.fallDistance > 0.5f && !this.isInWater()) return true;
        if (!this.onGround() && !strict) return false;

        // Where the body is actually heading: half a block ahead along the
        // real direction of travel. Looking a whole cell ahead along the main
        // axis only, and ignoring small sideways components, let a slow
        // diagonal drift walk a citizen off a fourteen-block ledge.
        Vec3 probe = this.position().add(flat.normalize().scale(0.5));
        BlockPos ahead = BlockPos.containing(probe.x, this.getY() + 0.01, probe.z);
        if (ahead.getX() == feet.getX() && ahead.getZ() == feet.getZ()) return false;

        if (strict && (!hasFloorAt(feet) || !hasFloorUnderSelf())) {
            // A braced worker already hanging over the edge of its scaffold may
            // move back towards the middle of the block, never farther out.
            if (hasFloorAt(ahead)) return false;
            double cx = feet.getX() + 0.5 - this.getX();
            double cz = feet.getZ() + 0.5 - this.getZ();
            return flat.x * cx < 0 || flat.z * cz < 0;
        }
        // How far down the next floor may be. Ordinary walking follows vanilla
        // path-finding, which plans drops of up to three blocks (the fall a mob
        // takes without damage). Up on its own scaffold only a level step
        // counts, one down at most: a longer step from a pillar top can be
        // blocks above the ground.
        int maxDrop = strict ? (this.controlledDrop ? 3 : 1) : 3;
        for (int k = 0; k <= maxDrop; k++) {
            BlockPos cell = ahead.below(k);
            if (hasFloorAt(cell)) return false;
            // Dropping into water is a landing, not a fall.
            if (!this.level().getFluidState(cell).isEmpty()) return false;
        }
        // Nothing to land on within reach: a real cliff or a ravine.
        return true;
    }

    private boolean hasFloorUnderSelf() {
        AABB box = this.getBoundingBox().inflate(0.02);
        BlockPos min = BlockPos.containing(box.minX, this.getY(), box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, this.getY(), box.maxZ);
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                if (!hasFloorAt(new BlockPos(x, this.blockPosition().getY(), z))) return false;
            }
        }
        return true;
    }

    private boolean hasFloorAt(BlockPos feet) {
        BlockPos floor = feet.below();
        BlockState state = this.level().getBlockState(floor);
        if (state.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                || state.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) return true;
        return !(state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)
                && state.isFaceSturdy(this.level(), floor, Direction.UP);
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
