package ai.minecivilization.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side index of the citizens currently in the world. Rebuilt as entities
 * load (never persisted): observations use it for population, /mciv commands
 * use it for listing/inspection.
 */
public final class CitizenIndex {
    private static final Set<CitizenEntity> CITIZENS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private CitizenIndex() {
    }

    public static void add(CitizenEntity citizen) {
        CITIZENS.add(citizen);
    }

    public static void remove(CitizenEntity citizen) {
        CITIZENS.remove(citizen);
    }

    public static int population() {
        return CITIZENS.size();
    }

    public static List<CitizenEntity> all() {
        return new ArrayList<>(CITIZENS);
    }

    /** Called when a server stops so the next world starts clean. */
    public static void clear() {
        CITIZENS.clear();
    }
}
