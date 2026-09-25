package ai.minecivilization.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.minecivilization.entity.CitizenEntity;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Where a citizen with nothing to do finds something worth doing.
 *
 * <p>The colony had no such place. Work was invented per citizen, in a fixed
 * order, with no notion of how urgent any of it was and no way for two
 * citizens to avoid picking the same job. The result was the failure mode the
 * whole mod is built to avoid: people standing still with an obvious job ten
 * blocks away, or six people converging on it at once.</p>
 *
 * <p>This is a board rather than a queue. Sources register themselves with a
 * {@link WorkPriority}; a citizen asks for the most urgent thing it can
 * actually take, and the claim it gets back stops the rest of the colony
 * taking the same one. Adding a new kind of work anywhere in the mod means
 * registering one more source — no dispatcher changes, no new branches in the
 * brain.</p>
 */
public final class GlobalTaskPool {

    /** Somewhere jobs come from. Returns null when it has nothing to offer. */
    @FunctionalInterface
    public interface Source {
        @Nullable
        WorkOffer offer(ServerLevel level, CitizenEntity citizen);
    }

    private record Registration(String name, WorkPriority priority, Source source) {
    }

    private static final List<Registration> SOURCES = new ArrayList<>();
    /** claimKey → how many citizens hold it, and until when. */
    private static final Map<String, Claim> CLAIMS = new HashMap<>();
    /** Per-citizen record of what it is holding, so it can be released. */
    private static final Map<java.util.UUID, String> HELD = new HashMap<>();
    /** Which source handed a citizen its current job, for failure accounting. */
    private static final Map<java.util.UUID, String> LAST_SOURCE = new HashMap<>();
    /** "citizenUuid|source" → game time the source may be offered again. */
    private static final Map<String, Long> BENCHED = new HashMap<>();

    /**
     * How long a source is benched for one citizen after its job fails.
     *
     * <p>Without this, a source that offers something impossible offers it
     * again on the next tick, forever. That is not hypothetical: a tool-making
     * source once put half the colony into the same uncraftable wooden axe for
     * an entire session, each failure followed immediately by the same offer.
     * The bench is per citizen, so one worker's bad luck never stops the rest
     * of the colony taking the same job.</p>
     */
    private static final int BENCH_TICKS = 600;

    private record Claim(List<java.util.UUID> holders, long expiresAt) {
    }

    private GlobalTaskPool() {
    }

    // ------------------------------------------------------------------ registry

    /**
     * Add a source of work.
     *
     * <p>Registration is by name so a reload replaces a source rather than
     * stacking a second copy of it.</p>
     */
    public static synchronized void register(String name, WorkPriority priority, Source source) {
        SOURCES.removeIf(registration -> registration.name().equals(name));
        SOURCES.add(new Registration(name, priority, source));
        SOURCES.sort(Comparator.comparingInt(registration -> registration.priority().rank()));
    }

    public static synchronized int sourceCount() {
        return SOURCES.size();
    }

    public static synchronized List<String> sourceNames() {
        List<String> names = new ArrayList<>();
        for (Registration registration : SOURCES) {
            names.add(registration.priority().label() + ": " + registration.name());
        }
        return names;
    }

    // ------------------------------------------------------------------ dispatch

    /**
     * The most urgent job this citizen can take right now.
     *
     * <p>Sources are consulted in priority order and the first one that both
     * offers work and grants a claim wins. A source that offers work somebody
     * else already holds is skipped rather than blocking the ones below it —
     * otherwise one contested job would starve the whole board.</p>
     */
    @Nullable
    public static WorkOffer claim(ServerLevel level, CitizenEntity citizen) {
        return claim(level, citizen, null);
    }

    /**
     * @param ceiling only consider work at least this urgent, or null for all
     */
    @Nullable
    public static synchronized WorkOffer claim(ServerLevel level, CitizenEntity citizen,
                                               @Nullable WorkPriority ceiling) {
        if (level == null || citizen == null) return null;
        long now = level.getGameTime();
        expire(now);

        for (Registration registration : SOURCES) {
            if (ceiling != null && registration.priority().rank() > ceiling.rank()) break;
            if (isBenched(citizen, registration.name(), now)) continue;
            WorkOffer offer;
            try {
                offer = registration.source().offer(level, citizen);
            } catch (RuntimeException ex) {
                // One broken source must never stop the colony working.
                ai.minecivilization.telemetry.ColonyEventLog.of(level).organisation(level,
                        "work source '" + registration.name() + "' failed: " + ex);
                continue;
            }
            if (offer == null || offer.isEmpty()) continue;
            if (!take(citizen, offer, now)) continue;
            LAST_SOURCE.put(citizen.getUUID(), registration.name());
            return offer;
        }
        return null;
    }

    /**
     * The job this citizen took from the board did not work out.
     *
     * <p>Benches that source for this citizen so the board offers something
     * else next time. This is the generic cure for a source that keeps
     * proposing the impossible — it needs no knowledge of what the source
     * does, which is why it covers sources that do not exist yet.</p>
     */
    public static synchronized void noteFailure(CitizenEntity citizen, long now) {
        if (citizen == null) return;
        String source = LAST_SOURCE.get(citizen.getUUID());
        if (source == null) return;
        BENCHED.put(citizen.getUUID() + "|" + source, now + BENCH_TICKS);
    }

    /** The job worked; the source is trustworthy again. */
    public static synchronized void noteSuccess(CitizenEntity citizen) {
        if (citizen == null) return;
        String source = LAST_SOURCE.get(citizen.getUUID());
        if (source == null) return;
        BENCHED.remove(citizen.getUUID() + "|" + source);
    }

    private static boolean isBenched(CitizenEntity citizen, String source, long now) {
        Long until = BENCHED.get(citizen.getUUID() + "|" + source);
        if (until == null) return false;
        if (until > now) return true;
        BENCHED.remove(citizen.getUUID() + "|" + source);
        return false;
    }

    /** What last gave this citizen work, for inspection. */
    @Nullable
    public static synchronized String sourceFor(CitizenEntity citizen) {
        return citizen == null ? null : LAST_SOURCE.get(citizen.getUUID());
    }

    /** How many source/citizen pairs are currently benched, for /mciv work. */
    public static synchronized int benchedCount() {
        return BENCHED.size();
    }

    /** Take a claim on an offer, if there is room on it. */
    private static boolean take(CitizenEntity citizen, WorkOffer offer, long now) {
        String key = offer.claimKey();
        if (key == null || key.isBlank()) return true;   // unclaimed work: anyone may do it

        Claim existing = CLAIMS.get(key);
        if (existing == null) {
            List<java.util.UUID> holders = new ArrayList<>(1);
            holders.add(citizen.getUUID());
            CLAIMS.put(key, new Claim(holders, now + WorkOffer.DEFAULT_TTL));
            HELD.put(citizen.getUUID(), key);
            return true;
        }
        if (existing.holders().contains(citizen.getUUID())) return true;
        if (existing.holders().size() >= offer.maxWorkers()) return false;

        existing.holders().add(citizen.getUUID());
        CLAIMS.put(key, new Claim(existing.holders(), now + WorkOffer.DEFAULT_TTL));
        HELD.put(citizen.getUUID(), key);
        return true;
    }

    /** Give up whatever this citizen was holding — it finished, failed or died. */
    public static synchronized void release(CitizenEntity citizen) {
        if (citizen == null) return;
        String key = HELD.remove(citizen.getUUID());
        if (key == null) return;
        Claim claim = CLAIMS.get(key);
        if (claim == null) return;
        claim.holders().remove(citizen.getUUID());
        if (claim.holders().isEmpty()) CLAIMS.remove(key);
    }

    /** What this citizen currently holds, for inspection. */
    @Nullable
    public static synchronized String heldBy(CitizenEntity citizen) {
        return citizen == null ? null : HELD.get(citizen.getUUID());
    }

    public static synchronized int claimCount() {
        return CLAIMS.size();
    }

    public static synchronized void clear() {
        CLAIMS.clear();
        HELD.clear();
        LAST_SOURCE.clear();
        BENCHED.clear();
    }

    /**
     * Forget claims nobody renewed.
     *
     * <p>A citizen that crashes, unloads with its chunk or simply wanders off
     * must not hold a job forever; the TTL is the backstop that keeps the
     * board from silently filling up with work nobody is doing.</p>
     */
    private static void expire(long now) {
        CLAIMS.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt() > now) return false;
            for (java.util.UUID holder : entry.getValue().holders()) {
                HELD.remove(holder, entry.getKey());
            }
            return true;
        });
    }
}
