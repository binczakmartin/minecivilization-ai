package ai.minecivilization.citizen;

import java.util.Random;

import net.minecraft.nbt.CompoundTag;

/**
 * Personality traits, floats in [0,1]. Deterministic when derived from
 * (seed, citizenId) so simulation runs can be compared.
 */
public final class CitizenPersonality {
    public float curiosity = 0.5f;
    public float ambition = 0.5f;
    public float sociability = 0.5f;
    public float patience = 0.5f;
    public float riskTolerance = 0.5f;
    public float cooperation = 0.5f;
    public float creativity = 0.5f;

    public static CitizenPersonality derived(long seed, java.util.UUID citizenId) {
        CitizenPersonality p = new CitizenPersonality();
        Random rng = new Random(seed ^ citizenId.getLeastSignificantBits());
        p.curiosity = rng.nextFloat();
        p.ambition = rng.nextFloat();
        p.sociability = rng.nextFloat();
        p.patience = rng.nextFloat();
        p.riskTolerance = rng.nextFloat();
        p.cooperation = rng.nextFloat();
        p.creativity = rng.nextFloat();
        return p;
    }

    public void save(CompoundTag tag) {
        tag.putFloat("curiosity", curiosity);
        tag.putFloat("ambition", ambition);
        tag.putFloat("sociability", sociability);
        tag.putFloat("patience", patience);
        tag.putFloat("riskTolerance", riskTolerance);
        tag.putFloat("cooperation", cooperation);
        tag.putFloat("creativity", creativity);
    }

    public void load(CompoundTag tag) {
        curiosity = tag.getFloat("curiosity");
        ambition = tag.getFloat("ambition");
        sociability = tag.getFloat("sociability");
        patience = tag.getFloat("patience");
        riskTolerance = tag.getFloat("riskTolerance");
        cooperation = tag.getFloat("cooperation");
        creativity = tag.getFloat("creativity");
    }
}
