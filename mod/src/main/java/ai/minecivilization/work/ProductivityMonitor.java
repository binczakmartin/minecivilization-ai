package ai.minecivilization.work;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import ai.minecivilization.citizen.StuckDetector;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.entity.CitizenIndex;
import ai.minecivilization.telemetry.ColonyEventLog;
import net.minecraft.server.level.ServerLevel;

/**
 * Watching whether the colony is actually working.
 *
 * <p>This is the number the whole project lives or dies by, and it was
 * invisible. A settlement in which more than half of all citizen-time is spent
 * standing still looks, from outside, almost exactly like one that is busy —
 * people are scattered around, occasionally moving. The only way to tell is to
 * count, which is what this does, per citizen and for the colony as a whole.</p>
 *
 * <p>It does not only measure. When a citizen has been stuck long enough that
 * the deterministic recovery has plainly failed, this is what says so out loud
 * — into the event feed, where a player can see it — and clears the way for a
 * different job.</p>
 */
public final class ProductivityMonitor {

    /** How often the colony-wide census is recomputed, in ticks. */
    private static final int CENSUS_INTERVAL = 100;
    /** Ticks a citizen must be stuck before it is announced. */
    private static final int ANNOUNCE_AFTER = StuckDetector.UNPRODUCTIVE_TICKS;
    /** Ticks between repeat announcements about the same citizen. */
    private static final int ANNOUNCE_COOLDOWN = 2400;

    /** How a citizen is spending its time, for the census. */
    public enum State {
        WORKING,
        IDLE,
        STUCK,
        LOST,
        RETURNING,
        EXPLORING,
        BUILDING,
        FIGHTING,
        DEAD;

        public String label() {
            return name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A colony-wide snapshot, cheap to read and safe to show anywhere. */
    public record Census(int population, Map<State, Integer> byState,
                         int stuckCount, double busyFraction, long takenAt) {

        public int count(State state) {
            return byState.getOrDefault(state, 0);
        }

        /** Percentage of citizens doing something useful, 0–100. */
        public int busyPercent() {
            return (int) Math.round(busyFraction * 100);
        }
    }

    private static Census latest = new Census(0, new EnumMap<>(State.class), 0, 0, 0);
    private static long nextCensusAt;
    private static final Map<java.util.UUID, Long> ANNOUNCED = new java.util.HashMap<>();

    private ProductivityMonitor() {
    }

    // ------------------------------------------------------------------ census

    /** Called every server tick; recomputes occasionally. */
    public static void tick(ServerLevel level) {
        if (level == null) return;
        long now = level.getGameTime();
        if (now < nextCensusAt) return;
        nextCensusAt = now + CENSUS_INTERVAL;

        List<CitizenEntity> citizens = CitizenIndex.all();
        Map<State, Integer> byState = new EnumMap<>(State.class);
        int busy = 0;
        int stuck = 0;

        for (CitizenEntity citizen : citizens) {
            State state = stateOf(citizen, now);
            byState.merge(state, 1, Integer::sum);
            if (state == State.WORKING || state == State.BUILDING
                    || state == State.EXPLORING || state == State.RETURNING) busy++;
            if (state == State.STUCK || state == State.LOST) {
                stuck++;
                announce(level, citizen, state, now);
            }
        }
        latest = new Census(citizens.size(), byState, stuck,
                citizens.isEmpty() ? 0 : (double) busy / citizens.size(), now);
    }

    public static Census census() {
        return latest;
    }

    /**
     * What one citizen is doing, in the vocabulary the census counts in.
     *
     * <p>Ordered most-alarming first: a citizen fighting while lost is lost,
     * because that is the fact that needs acting on.</p>
     */
    public static State stateOf(CitizenEntity citizen, long now) {
        if (citizen == null || citizen.isRemoved()) return State.DEAD;
        var brain = citizen.getCitizenBrain();
        if (brain.isLost()) return State.LOST;
        // Holding a task is not the same as making progress. Counting a
        // citizen as WORKING because it has a task — while its own detector
        // says it has failed the same thing three times running — is how the
        // colony reported itself 94% busy with twelve of sixteen citizens
        // carrying a warning on the very same line.
        if (brain.stuckReason(now) != StuckDetector.Reason.NONE) return State.STUCK;
        if (brain.isWalkingHome()) return State.RETURNING;
        if (citizen.isInCombat()) return State.FIGHTING;

        var task = brain.currentTaskOrNull();
        if (task == null) return State.IDLE;
        return switch (task.type) {
            case BUILD, PLACE, ROADWORK, SIGN -> State.BUILDING;
            case EXPLORE -> State.EXPLORING;
            case ESCAPE -> State.LOST;
            case IDLE, REST -> State.IDLE;
            default -> State.WORKING;
        };
    }

    /** Citizens a player probably wants to go and look at. */
    public static List<CitizenEntity> needingAttention(long now) {
        List<CitizenEntity> out = new ArrayList<>();
        for (CitizenEntity citizen : CitizenIndex.all()) {
            State state = stateOf(citizen, now);
            if (state == State.STUCK || state == State.LOST) out.add(citizen);
        }
        return out;
    }

    /**
     * Say once, in the feed, that a citizen is in trouble.
     *
     * <p>Rate-limited per citizen: a colony that reports the same stuck miner
     * every five seconds has replaced one invisible problem with an unreadable
     * one.</p>
     */
    private static void announce(ServerLevel level, CitizenEntity citizen, State state,
                                 long now) {
        var brain = citizen.getCitizenBrain();
        if (brain.stuckTicks(now) < ANNOUNCE_AFTER && state != State.LOST) return;

        Long last = ANNOUNCED.get(citizen.getUUID());
        if (last != null && now - last < ANNOUNCE_COOLDOWN) return;
        ANNOUNCED.put(citizen.getUUID(), now);

        var pos = citizen.blockPosition();
        ColonyEventLog.of(level).rescue(level, citizen.getIdentity().name,
                "is " + state.label().toLowerCase(java.util.Locale.ROOT)
                        + " at " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                        + " — " + brain.stuckReason(now).name().toLowerCase(java.util.Locale.ROOT)
                        .replace('_', ' '));
    }

    public static void reset() {
        latest = new Census(0, new EnumMap<>(State.class), 0, 0, 0);
        nextCensusAt = 0;
        ANNOUNCED.clear();
    }
}
