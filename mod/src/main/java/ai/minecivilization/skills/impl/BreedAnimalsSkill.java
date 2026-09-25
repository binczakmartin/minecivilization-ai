package ai.minecivilization.skills.impl;

import ai.minecivilization.entity.WorkAnimation;
import ai.minecivilization.livestock.*;
import ai.minecivilization.config.ModConfig;
import ai.minecivilization.skills.*;
import net.minecraft.world.entity.animal.Animal;

/** Feed both adults together, reserve the birth globally, and let vanilla breed them. */
public final class BreedAnimalsSkill implements CitizenSkill {
    private Animal first, second;
    private final PenVisit visit = new PenVisit();
    private boolean finished;
    public SkillType type() { return SkillType.BREED_ANIMALS; }
    public boolean canStart(SkillContext c) { return true; }
    public void start(SkillContext c) { first = second = null; }
    private boolean ready(Animal a) { return a != null && a.isAlive() && !a.isBaby() && a.getAge() == 0 && a.canFallInLove() && HerdRegistry.owned(a); }
    public SkillResult tick(SkillContext c) {
        if (finished) return visit.leave(c);
        var pen = Pens.nearest(c.level, c.citizen.blockPosition());
        if (pen == null) { c.fail(SkillFailure.notFound("no enclosed pasture")); return SkillResult.FAILED; }
        Pens.census(c.level);
        var registry = HerdRegistry.get(c.level);
        if (!ready(first) || !ready(second) || !Pens.inside(pen, first) || !Pens.inside(pen, second)) {
            first = second = null;
            var animals = Pens.animals(c.level, pen);
            for (Animal a : animals) for (Animal b : animals) {
                String species = HerdRegistry.species(a);
                if (a == b || !ready(a) || !ready(b) || !species.equals(HerdRegistry.species(b))
                        || (c.params.resource != null && !species.equals(c.params.resource))
                        || a.distanceToSqr(b) > 64 || !HerdPolicy.canBreed(registry.count(species),
                            registry.pending(species, c.level.getGameTime()), 2, ModConfig.LIVESTOCK_LIMIT.get())) continue;
                for (String food : AnimalHusbandry.feedFor(species)) if (c.citizen.getInventory().count(food) >= 2) {
                    first = a; second = b; break;
                }
                if (first != null) break;
            }
            if (first == null) { c.fail(SkillFailure.notFound("no same-species pair with food and room under the herd limit")); return SkillResult.FAILED; }
        }
        var access = visit.enter(c, pen);
        if (access != SkillResult.COMPLETED) return access;
        // Walk close enough to feed both, without modifying the fence.
        Animal farther = c.citizen.distanceToSqr(first) > c.citizen.distanceToSqr(second) ? first : second;
        if (c.citizen.distanceToSqr(farther) > 16) {
            c.navigator.moveTo(farther, 1); c.navigator.tick();
            if (c.navigator.hasFailed()) { c.fail(c.navigator.failure()); return SkillResult.FAILED; }
            return SkillResult.RUNNING;
        }
        String species = HerdRegistry.species(first);
        for (String food : AnimalHusbandry.feedFor(species)) {
            if (c.citizen.getInventory().count(food) < 2) continue;
            if (!registry.reserveBirth(first, species, ModConfig.LIVESTOCK_LIMIT.get(), c.level.getGameTime())) { finished = true; return visit.leave(c); }
            c.citizen.animateAction(WorkAnimation.REACH, first.blockPosition());
            c.citizen.getInventory().extract(food, 2);
            first.setInLove(null); second.setInLove(null);
            c.navigator.stop(); c.citizen.getSkills().addXp("farming", 0.1f);
            finished = true; return visit.leave(c);
        }
        c.fail(SkillFailure.missing("two portions of breeding food required")); return SkillResult.FAILED;
    }
    public void cancel(SkillContext c) { visit.cancel(c); }
    public String progressLabel(SkillContext c) { return "feeding a breeding pair within the herd limit"; }
}
