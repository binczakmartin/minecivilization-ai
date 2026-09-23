package ai.minecivilization.skills.impl;

import ai.minecivilization.farming.FarmLighting;
import ai.minecivilization.skills.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

/** Place a real torch for one dark crop, then let the lighting engine settle. */
public final class LightFarmSkill implements CitizenSkill {
    private BlockPos crop, spot;
    public SkillType type() { return SkillType.LIGHT_FARM; }
    public boolean canStart(SkillContext c) { return true; }
    public void start(SkillContext c) {
        crop = c.params.position == null ? ai.minecivilization.colony.ColonyLighting.find(c.level, c.citizen.blockPosition())
            : new BlockPos(c.params.position[0], c.params.position[1], c.params.position[2]);
    }
    public SkillResult tick(SkillContext c) {
        if (crop == null || !ai.minecivilization.colony.ColonyLighting.needsLight(c.level, crop)) return SkillResult.COMPLETED;
        if (!c.citizen.getInventory().containsAtLeast("minecraft:torch", 1)) {
            c.fail(SkillFailure.missing("colony lighting needs torches")); return SkillResult.FAILED;
        }
        spot = FarmLighting.torchSpot(c.level, crop);
        if (spot == null) { c.fail(SkillFailure.notFound("no safe torch support in this work area")); return SkillResult.FAILED; }
        var movement = SkillNavigation.approach(c, spot, 16, "light.walk");
        if (movement != SkillResult.COMPLETED) return movement;
        c.params.position = new int[]{spot.getX(), spot.getY(), spot.getZ()};
        c.params.block = "minecraft:torch";
        var place = new PlaceBlockSkill();
        place.start(c);
        return place.tick(c);
    }
    public void cancel(SkillContext c) {
        CitizenSkill traversal = c.get("light.walk", (CitizenSkill) null);
        SkillContext sub = c.get("light.walk.ctx", (SkillContext) null);
        if (traversal != null && sub != null) traversal.cancel(sub);
        c.navigator.stop();
    }
    public String progressLabel(SkillContext c) { return "lighting colony and mine passages"; }
}
