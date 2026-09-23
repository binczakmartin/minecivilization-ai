package ai.minecivilization.citizen;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

/**
 * Persistent citizen identity. citizenId is independent from the entity runtime id
 * and survives world reloads. Large AI memories live in the AI service database,
 * never in entity NBT.
 */
public final class CitizenIdentity {
    private static final String[] NAMES = {
            "Alex", "Marie", "Sam", "Noor", "Kai", "Lena", "Tomas", "Ines",
            "Ravi", "Zoe", "Hugo", "Mila", "Omar", "Elsa", "Jonas", "Aya"
    };
    /**
     * The role rotation. Citizens self-organize by joining this list in spawn
     * order: lumberjack, miner, farmer, builder, crafter, shepherd, then it
     * repeats.
     *
     * <p>The shepherd comes sixth on purpose. Livestock is what a settlement
     * reaches for once it is fed and housed — but it is also what unlocks the
     * next tier, since wool makes beds and beds allow births.</p>
     */
    private static final String[] ROLES = {
            "LUMBERJACK", "MINER", "FARMER", "BUILDER", "CRAFTER", "SHEPHERD"
    };

    public UUID citizenId;
    public String name = "Citizen";
    public long createdAt;          // game time of creation (informational)
    public String profession = "UNASSIGNED";
    public String employerId = null;
    public String companyId = null;
    public String homePosition = null;      // "x,y,z"
    public String workplacePosition = null; // "x,y,z"

    /** Round-robin role for the citizen that is about to become number N+1. */
    public static String professionForPopulation(int population) {
        return ROLES[Math.floorMod(population, ROLES.length)];
    }

    /** A fresh random display name (role assignment stays deterministic). */
    public static String randomName(net.minecraft.util.RandomSource random) {
        return NAMES[random.nextInt(NAMES.length)];
    }

    /** Fresh identity for a newly spawned citizen (role left UNASSIGNED:
     *  {@link #professionForPopulation} assigns it in spawn order at spawn). */
    public static CitizenIdentity random(UUID uuid, net.minecraft.util.RandomSource random) {
        CitizenIdentity identity = new CitizenIdentity();
        identity.citizenId = uuid;
        identity.name = randomName(random);
        return identity;
    }

    public void save(CompoundTag tag) {
        if (citizenId != null) {
            tag.putUUID("citizenId", citizenId);
        }
        tag.putString("name", name);
        tag.putLong("createdAt", createdAt);
        tag.putString("profession", profession);
        if (employerId != null) tag.putString("employerId", employerId);
        if (companyId != null) tag.putString("companyId", companyId);
        if (homePosition != null) tag.putString("homePosition", homePosition);
        if (workplacePosition != null) tag.putString("workplacePosition", workplacePosition);
    }

    public void load(CompoundTag tag) {
        citizenId = tag.hasUUID("citizenId") ? tag.getUUID("citizenId") : UUID.randomUUID();
        name = tag.getString("name");
        createdAt = tag.getLong("createdAt");
        profession = tag.getString("profession");
        employerId = tag.contains("employerId") ? tag.getString("employerId") : null;
        companyId = tag.contains("companyId") ? tag.getString("companyId") : null;
        homePosition = tag.contains("homePosition") ? tag.getString("homePosition") : null;
        workplacePosition = tag.contains("workplacePosition") ? tag.getString("workplacePosition") : null;
    }
}
