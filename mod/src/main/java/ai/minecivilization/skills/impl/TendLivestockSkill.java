package ai.minecivilization.skills.impl;

import ai.minecivilization.config.ModConfig;
import ai.minecivilization.livestock.*;
import ai.minecivilization.skills.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.sounds.SoundSource;

/** Harvest renewable products; only surplus adults may be slaughtered. */
public final class TendLivestockSkill implements CitizenSkill {
    private Animal animal;
    private final PenVisit visit = new PenVisit();
    private boolean finished;
    private long nextAttack;
    public SkillType type() { return SkillType.TEND_LIVESTOCK; }
    public boolean canStart(SkillContext c) { return true; }
    public void start(SkillContext c) { animal = null; }
    public SkillResult tick(SkillContext c) {
        if (finished) return visit.leave(c);
        var pen = Pens.nearest(c.level, c.citizen.blockPosition());
        if (pen == null) { c.fail(SkillFailure.notFound("no intact pen")); return SkillResult.FAILED; }
        if ("EXIT".equals(c.params.target)) return visit.exit(c, pen);
        var access = visit.enter(c, pen);
        if (access != SkillResult.COMPLETED) return access;
        String mode = c.params.target == null ? "SHEAR" : c.params.target;
        if (animal != null && !animal.isAlive()) { collect(c); return finish(c); }
        if (mode.equals("EGGS")) {
            var drops = c.level.getEntitiesOfClass(ItemEntity.class, Pens.interior(pen), e -> e.getItem().is(Items.EGG));
            if (drops.isEmpty()) return finish(c);
            var egg = drops.get(0);
            if (c.citizen.distanceToSqr(egg) > 9) {
                c.navigator.moveTo(egg, 1); c.navigator.tick();
                if (c.navigator.hasFailed()) { c.fail(c.navigator.failure()); return SkillResult.FAILED; }
                return SkillResult.RUNNING;
            }
            collect(c); return finish(c);
        }
        if (animal == null) for (Animal a : Pens.animals(c.level, pen)) {
            if (!HerdRegistry.owned(a) || a.isBaby() || a.hasCustomName()) continue;
            if (c.params.resource != null && !c.params.resource.equals(HerdRegistry.species(a))) continue;
            if (((mode.equals("SURPLUS") || mode.equals("FOOD")) && surplus(c, a))
                    || (mode.equals("SHEAR") && a instanceof Sheep sheep && sheep.readyForShearing())
                    || (mode.equals("MILK") && (a instanceof Cow || a instanceof Goat))) { animal = a; break; }
        }
        if (animal == null) { c.fail(SkillFailure.notFound("no livestock needs " + mode)); return SkillResult.FAILED; }
        if (!Pens.inside(pen, animal) || !HerdRegistry.owned(animal)) return finish(c);
        if (c.citizen.distanceToSqr(animal) > 9) {
            c.navigator.moveTo(animal, 1); c.navigator.tick();
            if (c.navigator.hasFailed()) { c.fail(c.navigator.failure()); return SkillResult.FAILED; }
            return SkillResult.RUNNING;
        }
        c.navigator.stop();
        var inv = c.citizen.getInventory();
        if ((mode.equals("SURPLUS") || mode.equals("FOOD"))) {
            // Recheck before EVERY blow: another worker may just have culled the excess.
            if (!surplus(c, animal)) return finish(c);
            if (c.level.getGameTime() >= nextAttack) {
                c.citizen.doHurtTarget(animal); nextAttack = c.level.getGameTime() + 16;
            }
            if (!animal.isAlive()) { collect(c); return finish(c); }
            return SkillResult.RUNNING;
        }
        if (mode.equals("SHEAR") && animal instanceof Sheep sheep && sheep.readyForShearing()) {
            for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.SHEARS)) {
                sheep.shear(SoundSource.NEUTRAL);
                ItemStack tool = inv.getItem(i); tool.setDamageValue(tool.getDamageValue() + 1);
                if (tool.getDamageValue() >= tool.getMaxDamage()) inv.setItem(i, ItemStack.EMPTY);
                collect(c); return finish(c);
            }
            c.fail(SkillFailure.missing("shearing needs real shears")); return SkillResult.FAILED;
        }
        if (mode.equals("MILK") && inv.containsAtLeast("minecraft:bucket", 1)) {
            inv.extract("minecraft:bucket", 1);
            int left = inv.insert(new ItemStack(Items.MILK_BUCKET));
            if (left > 0) c.citizen.spawnAtLocation(new ItemStack(Items.MILK_BUCKET, left));
            return finish(c);
        }
        c.fail(SkillFailure.missing("missing livestock harvesting tool")); return SkillResult.FAILED;
    }
    private SkillResult finish(SkillContext c) { finished = true; return visit.leave(c); }
    private boolean surplus(SkillContext c, Animal a) {
        String species = HerdRegistry.species(a);
        int adults = 0;
        for (var pen : Pens.all(c.level)) for (Animal b : Pens.animals(c.level, pen))
            if (HerdRegistry.owned(b) && !b.isBaby() && HerdRegistry.species(b).equals(species)) adults++;
        boolean foodNeeded = "FOOD".equals(c.params.target)
            && ai.minecivilization.storage.SettlementStock.totals(c.level, c.citizen.blockPosition()).entrySet().stream()
                .filter(e -> e.getKey().contains("cooked_") || e.getKey().equals("minecraft:bread"))
                .mapToInt(java.util.Map.Entry::getValue).sum() < 32;
        return HerdPolicy.canHarvestForFood(HerdRegistry.get(c.level).count(species), adults, a.isBaby(), ModConfig.LIVESTOCK_LIMIT.get(), foodNeeded);
    }
    private void collect(SkillContext c) {
        for (ItemEntity e : c.level.getEntitiesOfClass(ItemEntity.class, c.citizen.getBoundingBox().inflate(4))) {
            int left = c.citizen.getInventory().insert(e.getItem().copy());
            if (left == 0) e.discard(); else e.setItem(e.getItem().copyWithCount(left));
        }
    }
    public void cancel(SkillContext c) { visit.cancel(c); }
    public String progressLabel(SkillContext c) { return "livestock: " + c.params.target; }
}
