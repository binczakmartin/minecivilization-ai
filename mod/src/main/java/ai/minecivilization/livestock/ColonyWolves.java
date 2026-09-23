package ai.minecivilization.livestock;

import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Creeper;
import java.util.EnumSet;

/** Added on taming and chunk load; no player ownership or player pets are touched. */
public final class ColonyWolves {
    private static final java.util.Set<Wolf> ATTACHED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    public static void attach(Wolf wolf) {
        if (!HerdRegistry.owned(wolf) || !ATTACHED.add(wolf)) return;
        wolf.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(wolf, Monster.class, 10, true, false,
            target -> !(target instanceof Creeper) && CitizenIndex.all().stream().anyMatch(c -> c.level() == wolf.level()
                && c.isAlive() && c.distanceToSqr(target) <= 225)));
        wolf.goalSelector.addGoal(6, new Goal() {
            private CitizenEntity escort;
            { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
            public boolean canUse() {
                if (wolf.getTarget() != null || wolf.isOrderedToSit() || wolf.isLeashed()) return false;
                escort = CitizenIndex.all().stream().filter(c -> c.isAlive() && c.level() == wolf.level())
                    .min(java.util.Comparator.comparingDouble(wolf::distanceToSqr)).orElse(null);
                return escort != null && wolf.distanceToSqr(escort) > 36;
            }
            public boolean canContinueToUse() { return escort != null && escort.isAlive() && wolf.getTarget() == null && wolf.distanceToSqr(escort) > 16; }
            public void tick() { wolf.getNavigation().moveTo(escort, 1.15); wolf.getLookControl().setLookAt(escort); }
            public void stop() { wolf.getNavigation().stop(); escort = null; }
        });
    }
    private ColonyWolves() {}
}
