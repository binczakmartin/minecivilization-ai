package ai.minecivilization.skills.impl;

import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Eat one food item from the citizen's inventory to restore hunger.
 */
public final class EatSkill implements CitizenSkill {
    @Override
    public SkillType type() {
        return SkillType.EAT;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return context.citizen.getHunger() < 60f;
    }

    @Override
    public void start(SkillContext context) {
    }

    @Override
    public SkillResult tick(SkillContext context) {
        if (context.citizen.getHunger() >= 95f) {
            return SkillResult.COMPLETED;
        }
        int slot = context.citizen.getInventory().firstFoodSlot();
        if (slot < 0) {
            context.fail(SkillFailure.missing("no food in inventory"));
            return SkillResult.FAILED;
        }
        ItemStack food = context.citizen.getInventory().get(slot);
        FoodProperties properties = food.get(DataComponents.FOOD);
        if (properties == null) {
            context.fail(new SkillFailure("INVALID_FOOD", "item is not food", true));
            return SkillResult.FAILED;
        }
        context.citizen.animateAction(WorkAnimation.EAT, null);
        context.citizen.setHunger(Math.min(CitizenEntity.HUNGER_FULL,
                context.citizen.getHunger()
                        + CitizenEntity.hungerForNutrition(properties.nutrition())));
        food.shrink(1);
        if (food.isEmpty()) {
            context.citizen.getInventory().items().set(slot, ItemStack.EMPTY);
        }
        context.citizen.onInventoryChanged();
        return SkillResult.COMPLETED;
    }

    @Override
    public void cancel(SkillContext context) {
    }

    @Override
    public String progressLabel(SkillContext context) {
        return "eating";
    }
}
