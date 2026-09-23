package ai.minecivilization.livestock;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HerdRegistryTest {
    @Test void populationSurvivesSaveReloadWithoutLoadedEntities() {
        HerdRegistry registry = new HerdRegistry();
        for (int i = 0; i < 50; i++) registry.record(new UUID(0, i), "minecraft:wolf");
        HerdRegistry restored = HerdRegistry.load(registry.save(new CompoundTag(), null), null);
        assertEquals(50, restored.count("minecraft:wolf"));
        assertFalse(HerdPolicy.canRecruit(restored.count("minecraft:wolf"), 50));
        restored.remove(new UUID(0, 0));
        assertTrue(HerdPolicy.canRecruit(restored.count("minecraft:wolf"), 50));
    }
    @Test void twoWorkersCannotReserveTheSameLastBirthSlot() {
        HerdRegistry registry = new HerdRegistry();
        for (int i = 0; i < 9; i++) registry.record(new UUID(0, i), "minecraft:cow");
        assertTrue(registry.reserveBirth(new UUID(0, 0), "minecraft:cow", 10, 100));
        assertFalse(registry.reserveBirth(new UUID(0, 1), "minecraft:cow", 10, 101));
        HerdRegistry restored = HerdRegistry.load(registry.save(new CompoundTag(), null), null);
        assertEquals(1, restored.pending("minecraft:cow", 102));
        assertFalse(restored.reserveBirth(new UUID(0, 2), "minecraft:cow", 10, 102));
        assertEquals(0, restored.pending("minecraft:cow", 1301));
        assertTrue(restored.reserveBirth(new UUID(0, 2), "minecraft:cow", 10, 1301));
    }
    @Test void pendingBirthAlsoReservesSpaceAgainstHerdingAndTaming() {
        HerdRegistry registry = new HerdRegistry();
        for (int i = 0; i < 49; i++) registry.record(new UUID(0, i), "minecraft:wolf");
        assertTrue(registry.reserveBirth(new UUID(0, 0), "minecraft:wolf", 50, 100));
        assertFalse(HerdPolicy.canRecruit(registry.committed("minecraft:wolf", 101), 50));
        registry.remove(new UUID(0, 0));
        assertEquals(0, registry.pending("minecraft:wolf", 102));
        assertEquals(48, registry.count("minecraft:wolf"));
    }
    @Test void reloadDoesNotDoubleCountAnimalsAndSpeciesAreIndependent() {
        HerdRegistry registry = new HerdRegistry();
        UUID id = UUID.randomUUID();
        registry.record(id, "minecraft:sheep"); registry.record(id, "minecraft:sheep");
        registry.record(UUID.randomUUID(), "minecraft:cow");
        assertEquals(1, registry.count("minecraft:sheep"));
        assertEquals(1, registry.count("minecraft:cow"));
    }
}
