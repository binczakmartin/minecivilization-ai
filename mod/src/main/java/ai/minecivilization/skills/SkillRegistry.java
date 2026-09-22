package ai.minecivilization.skills;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import ai.minecivilization.skills.impl.BuildBlueprintSkill;
import ai.minecivilization.skills.impl.CraftItemSkill;
import ai.minecivilization.skills.impl.DeliverItemsSkill;
import ai.minecivilization.skills.impl.DepositItemSkill;
import ai.minecivilization.skills.impl.EatSkill;
import ai.minecivilization.skills.impl.FindBlockSkill;
import ai.minecivilization.skills.impl.FollowSkill;
import ai.minecivilization.skills.impl.HarvestCropSkill;
import ai.minecivilization.skills.impl.IdleSkill;
import ai.minecivilization.skills.impl.MineAreaSkill;
import ai.minecivilization.skills.impl.MineBlockSkill;
import ai.minecivilization.skills.impl.MoveToSkill;
import ai.minecivilization.skills.impl.PickupItemSkill;
import ai.minecivilization.skills.impl.PlaceBlockSkill;
import ai.minecivilization.skills.impl.PlantCropSkill;
import ai.minecivilization.skills.impl.SleepSkill;
import ai.minecivilization.skills.impl.SmeltItemSkill;
import ai.minecivilization.skills.impl.WithdrawItemSkill;

/**
 * Skill registry — adding a new skill means implementing {@link CitizenSkill}
 * and registering it here. Skills are created fresh per execution.
 */
public final class SkillRegistry {
    private static final Map<SkillType, Supplier<CitizenSkill>> REGISTRY = new EnumMap<>(SkillType.class);

    static {
        REGISTRY.put(SkillType.IDLE, IdleSkill::new);
        REGISTRY.put(SkillType.MOVE_TO, MoveToSkill::new);
        REGISTRY.put(SkillType.FOLLOW, FollowSkill::new);
        REGISTRY.put(SkillType.FIND_BLOCK, FindBlockSkill::new);
        REGISTRY.put(SkillType.MINE_BLOCK, MineBlockSkill::new);
        REGISTRY.put(SkillType.MINE_AREA, MineAreaSkill::new);
        REGISTRY.put(SkillType.PICKUP_ITEM, PickupItemSkill::new);
        REGISTRY.put(SkillType.PLACE_BLOCK, PlaceBlockSkill::new);
        REGISTRY.put(SkillType.HARVEST_CROP, HarvestCropSkill::new);
        REGISTRY.put(SkillType.PLANT_CROP, PlantCropSkill::new);
        REGISTRY.put(SkillType.CRAFT_ITEM, CraftItemSkill::new);
        REGISTRY.put(SkillType.SMELT_ITEM, SmeltItemSkill::new);
        REGISTRY.put(SkillType.DEPOSIT_ITEM, DepositItemSkill::new);
        REGISTRY.put(SkillType.WITHDRAW_ITEM, WithdrawItemSkill::new);
        REGISTRY.put(SkillType.EAT, EatSkill::new);
        REGISTRY.put(SkillType.SLEEP, SleepSkill::new);
        REGISTRY.put(SkillType.BUILD_BLUEPRINT, BuildBlueprintSkill::new);
        REGISTRY.put(SkillType.DELIVER_ITEMS, DeliverItemsSkill::new);
    }

    public static CitizenSkill create(SkillType type) {
        Supplier<CitizenSkill> supplier = REGISTRY.get(type);
        if (supplier == null) {
            throw new IllegalArgumentException("unregistered skill: " + type);
        }
        return supplier.get();
    }

    public static boolean isRegistered(SkillType type) {
        return REGISTRY.containsKey(type);
    }

    private SkillRegistry() {
    }
}
