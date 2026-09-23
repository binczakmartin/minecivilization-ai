package ai.minecivilization.skills.impl;

import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.config.ModConfig;
import ai.minecivilization.livestock.*;
import ai.minecivilization.skills.*;
import net.minecraft.world.entity.animal.Animal;

/** Escort animals through a real gate, wait for stragglers, close it after leaving. */
public final class HerdAnimalSkill implements CitizenSkill {
    private Animal animal;
    private ConstructionProject pen;
    private boolean leading, gateOpen, delivered;
    public SkillType type() { return SkillType.HERD_ANIMAL; }
    public boolean canStart(SkillContext c) { return true; }
    public void start(SkillContext c) { pen = Pens.nearest(c.level, c.citizen.blockPosition()); }
    public SkillResult tick(SkillContext c) {
        if (pen == null || !Pens.intact(c.level, pen)) { c.fail(SkillFailure.notFound("build or repair the animal pen first")); return SkillResult.FAILED; }
        if (!Pens.lock(c.level, pen, c.citizen.getUUID())) { c.fail(new SkillFailure("PEN_BUSY", "another herder is using the gate", true)); return SkillResult.FAILED; }
        var outside = Pens.gate(pen).south(2);
        if (delivered) {
            if (c.citizen.distanceToSqr(outside.getCenter()) > 2) {
                c.navigator.moveTo(outside, 1); c.navigator.tick();
                if (c.navigator.hasFailed()) { cancel(c); c.fail(c.navigator.failure()); return SkillResult.FAILED; }
                return SkillResult.RUNNING;
            }
            cancel(c); return SkillResult.COMPLETED;
        }
        if (animal == null) {
            double distance = Double.MAX_VALUE;
            for (Animal a : c.level.getEntitiesOfClass(Animal.class, c.citizen.getBoundingBox().inflate(48))) {
                String species = typeOf(a);
                if (!a.isAlive() || a.isBaby() || a.isLeashed() || a.hasCustomName() || a instanceof net.minecraft.world.entity.TamableAnimal
                        || !AnimalHusbandry.isLivestock(species) || Pens.all(c.level).stream().anyMatch(other -> Pens.inside(other, a))
                        || (c.params.resource != null && !species.equals(c.params.resource))) continue;
                if (!HerdRegistry.owned(a) && !HerdPolicy.canRecruit(HerdRegistry.get(c.level).committed(species, c.level.getGameTime()), ModConfig.LIVESTOCK_LIMIT.get())) continue;
                if (AnimalHusbandry.feedFor(species).stream().noneMatch(f -> c.citizen.getInventory().count(f) > 0)) continue;
                if (c.citizen.distanceToSqr(a) < distance) { animal = a; distance = c.citizen.distanceToSqr(a); }
            }
            if (animal == null) { cancel(c); c.fail(SkillFailure.notFound("no available animal and feed for the pen")); return SkillResult.FAILED; }
        }
        if (!animal.isAlive()) { cancel(c); c.fail(SkillFailure.notFound("animal lost")); return SkillResult.FAILED; }
        if (!leading) {
            if (c.citizen.distanceToSqr(animal) > 9) {
                c.navigator.moveTo(animal, 1); c.navigator.tick();
                if (c.navigator.hasFailed()) { cancel(c); c.fail(SkillFailure.unreachable("cannot reach livestock")); return SkillResult.FAILED; }
                return SkillResult.RUNNING;
            }
            if (animal.isLeashed() || (!HerdRegistry.owned(animal) && !HerdPolicy.canRecruit(HerdRegistry.get(c.level).committed(typeOf(animal), c.level.getGameTime()), ModConfig.LIVESTOCK_LIMIT.get()))) {
                cancel(c); return SkillResult.COMPLETED;
            }
            String food = AnimalHusbandry.feedFor(typeOf(animal)).stream().filter(f -> c.citizen.getInventory().count(f) > 0).findFirst().orElse(null);
            if (food == null) { cancel(c); c.fail(SkillFailure.missing("no lure food")); return SkillResult.FAILED; }
            c.citizen.getInventory().extract(food, 1);
            HerdRegistry.get(c.level).register(animal);
            animal.setLeashedTo(c.citizen, true); leading = true;
        }
        if (Pens.inside(pen, animal) && animal.distanceToSqr(Pens.center(pen).getCenter()) < 9) {
            animal.dropLeash(true, false); animal.getNavigation().stop(); leading = false; delivered = true;
            return SkillResult.RUNNING;
        }
        if (c.citizen.distanceToSqr(animal) > 25) {
            c.navigator.stop(); animal.getNavigation().moveTo(c.citizen, 1.2);
            if (c.citizen.distanceToSqr(animal) > 100) { cancel(c); c.fail(SkillFailure.unreachable("animal could not follow the route")); return SkillResult.FAILED; }
            return SkillResult.RUNNING;
        }
        if (!gateOpen && c.citizen.distanceToSqr(outside.getCenter()) <= 4) {
            Pens.gate(c.level, pen, true); gateOpen = true;
        }
        var destination = gateOpen ? Pens.center(pen) : outside;
        c.navigator.moveTo(destination, 0.75); c.navigator.tick();
        animal.getNavigation().moveTo(c.citizen, 1.1);
        if (c.navigator.hasFailed()) { cancel(c); c.fail(SkillFailure.unreachable("no safe route through the pen gate")); return SkillResult.FAILED; }
        return SkillResult.RUNNING;
    }
    static String typeOf(Animal a) { return HerdRegistry.species(a); }
    public void cancel(SkillContext c) {
        if (animal != null && animal.isLeashed() && animal.getLeashHolder() == c.citizen) animal.dropLeash(true, false);
        if (pen != null) {
            if (gateOpen) Pens.gate(c.level, pen, false);
            Pens.unlock(c.level, pen, c.citizen.getUUID());
        }
        c.navigator.stop(); leading = false; gateOpen = false;
    }
    public String progressLabel(SkillContext c) { return delivered ? "closing pasture gate" : leading ? "escorting livestock through the gate" : "finding a breeding animal"; }
}
