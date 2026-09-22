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
    private static final String[] PROFESSIONS = {
            "FARMER", "MINER", "BUILDER", "CRAFTER", "LOGISTICS", "UNASSIGNED"
    };

    public UUID citizenId;
    public String name = "Citizen";
    public long createdAt;          // game time of creation (informational)
    public String profession = "UNASSIGNED";
    public String employerId = null;
    public String companyId = null;
    public String homePosition = null;      // "x,y,z"
    public String workplacePosition = null; // "x,y,z"

    /** Fresh identity for a newly spawned citizen. */
    public static CitizenIdentity random(UUID uuid, net.minecraft.util.RandomSource random) {
        CitizenIdentity identity = new CitizenIdentity();
        identity.citizenId = uuid;
        identity.name = NAMES[random.nextInt(NAMES.length)];
        identity.profession = PROFESSIONS[random.nextInt(PROFESSIONS.length)];
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
