package ai.minecivilization.skills;

import java.util.HashMap;
import java.util.Map;

import ai.minecivilization.citizen.CitizenTaskParams;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.navigation.CitizenNavigator;
import net.minecraft.server.level.ServerLevel;

/**
 * Everything a skill may touch. Skills run on the Minecraft server thread only.
 */
public final class SkillContext {
    public final CitizenEntity citizen;
    public final ServerLevel level;
    public final CitizenNavigator navigator;
    public final CitizenTaskParams params;

    /** Per-execution scratch space owned by the active skill instance. */
    public final Map<String, Object> data = new HashMap<>();

    /** Set by a skill when it fails. */
    public SkillFailure failure;

    public long startGameTime;
    public int timeoutTicks;
    public int attempts; // how many times this task has been retried

    public SkillContext(CitizenEntity citizen, ServerLevel level,
                        CitizenNavigator navigator, CitizenTaskParams params) {
        this.citizen = citizen;
        this.level = level;
        this.navigator = navigator;
        this.params = params;
    }

    public void fail(SkillFailure f) {
        this.failure = f;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T fallback) {
        Object v = data.get(key);
        return v == null ? fallback : (T) v;
    }

    public void put(String key, Object value) {
        data.put(key, value);
    }

    public boolean timedOut(long now) {
        return timedOut(now, timeoutTicks);
    }

    /** Timed out against a budget the caller sized for the running skill. */
    public boolean timedOut(long now, int budgetTicks) {
        return budgetTicks > 0 && now - startGameTime > budgetTicks;
    }
}
