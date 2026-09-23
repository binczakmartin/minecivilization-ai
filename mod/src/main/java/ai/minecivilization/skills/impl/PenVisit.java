package ai.minecivilization.skills.impl;

import ai.minecivilization.construction.ConstructionProject;
import ai.minecivilization.livestock.Pens;
import ai.minecivilization.skills.*;
import net.minecraft.core.BlockPos;

/** A livestock worker enters/exits through the gate, never through the fence. */
final class PenVisit {
    private ConstructionProject pen;
    private boolean gateOpen;
    SkillResult enter(SkillContext c, ConstructionProject target) {
        pen = target;
        if (!Pens.lock(c.level, pen, c.citizen.getUUID())) {
            c.fail(new SkillFailure("PEN_BUSY", "another citizen is at the gate", true)); return SkillResult.FAILED;
        }
        if (Pens.interior(pen).contains(c.citizen.position())) {
            Pens.gate(c.level, pen, false); gateOpen = false; return SkillResult.COMPLETED;
        }
        BlockPos outside = Pens.gate(pen).south(2);
        if (!gateOpen && c.citizen.distanceToSqr(outside.getCenter()) > 4) return move(c, outside);
        Pens.gate(c.level, pen, true); gateOpen = true;
        return move(c, Pens.center(pen));
    }
    SkillResult exit(SkillContext c, ConstructionProject target) { pen = target; return leave(c); }
    SkillResult leave(SkillContext c) {
        if (pen == null) return SkillResult.COMPLETED;
        if (!Pens.lock(c.level, pen, c.citizen.getUUID())) return SkillResult.RUNNING;
        BlockPos outside = Pens.gate(pen).south(2);
        if (c.citizen.distanceToSqr(outside.getCenter()) <= 2) {
            Pens.gate(c.level, pen, false); gateOpen = false;
            Pens.unlock(c.level, pen, c.citizen.getUUID()); c.navigator.stop(); return SkillResult.COMPLETED;
        }
        if (!gateOpen && c.citizen.distanceToSqr(Pens.gate(pen).getCenter()) > 9) return move(c, Pens.gate(pen).north());
        Pens.gate(c.level, pen, true); gateOpen = true;
        return move(c, outside);
    }
    private SkillResult move(SkillContext c, BlockPos pos) {
        c.navigator.moveTo(pos, 1); c.navigator.tick();
        if (c.navigator.hasFailed()) { c.fail(SkillFailure.unreachable("cannot pass the animal pen gate")); return SkillResult.FAILED; }
        return SkillResult.RUNNING;
    }
    void cancel(SkillContext c) {
        if (pen != null) {
            Pens.gate(c.level, pen, false);
            Pens.unlock(c.level, pen, c.citizen.getUUID());
        }
        c.navigator.stop();
    }
}
