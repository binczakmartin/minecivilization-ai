package ai.minecivilization.citizen;

import net.minecraft.nbt.CompoundTag;

/**
 * Work proficiencies 0..100. In V1 these influence task selection and estimated
 * competence only — never magical speed bonuses.
 */
public final class CitizenSkills {
    public float mining = 0f;
    public float farming = 0f;
    public float building = 0f;
    public float crafting = 0f;
    public float logistics = 0f;
    public float redstone = 0f;
    public float architecture = 0f;
    public float research = 0f;
    public float trading = 0f;

    public float byName(String name) {
        return switch (name) {
            case "mining" -> mining;
            case "farming" -> farming;
            case "building" -> building;
            case "crafting" -> crafting;
            case "logistics" -> logistics;
            case "redstone" -> redstone;
            case "architecture" -> architecture;
            case "research" -> research;
            case "trading" -> trading;
            default -> 0f;
        };
    }

    public void addXp(String name, float amount) {
        float current = byName(name);
        float updated = Math.min(100f, current + amount);
        switch (name) {
            case "mining" -> mining = updated;
            case "farming" -> farming = updated;
            case "building" -> building = updated;
            case "crafting" -> crafting = updated;
            case "logistics" -> logistics = updated;
            case "redstone" -> redstone = updated;
            case "architecture" -> architecture = updated;
            case "research" -> research = updated;
            case "trading" -> trading = updated;
            default -> {
            }
        }
    }

    public void save(CompoundTag tag) {
        tag.putFloat("mining", mining);
        tag.putFloat("farming", farming);
        tag.putFloat("building", building);
        tag.putFloat("crafting", crafting);
        tag.putFloat("logistics", logistics);
        tag.putFloat("redstone", redstone);
        tag.putFloat("architecture", architecture);
        tag.putFloat("research", research);
        tag.putFloat("trading", trading);
    }

    public void load(CompoundTag tag) {
        mining = tag.getFloat("mining");
        farming = tag.getFloat("farming");
        building = tag.getFloat("building");
        crafting = tag.getFloat("crafting");
        logistics = tag.getFloat("logistics");
        redstone = tag.getFloat("redstone");
        architecture = tag.getFloat("architecture");
        research = tag.getFloat("research");
        trading = tag.getFloat("trading");
    }
}
