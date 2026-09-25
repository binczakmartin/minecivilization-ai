package ai.minecivilization.telemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.logging.LogUtils;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * The colony's newsfeed.
 *
 * <p>A settlement of twenty citizens generates a great deal of small activity
 * and almost no visible narrative. The log file has everything and therefore
 * shows nothing; chat announcements are reserved for births and deaths. What
 * was missing is the middle: a discovery, a project starting, someone getting
 * lost, a road being finished — the events that let a player glance at the
 * colony and understand what it has been doing.</p>
 *
 * <p>A bounded ring in memory, one per level. Deliberately not saved: this is
 * live news, not a ledger, and a restarted server should show what is
 * happening now rather than replay yesterday.</p>
 */
public final class ColonyEventLog {

    /** How many events are kept. Enough for roughly an hour of a busy colony. */
    public static final int CAPACITY = 400;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ServerLevel, ColonyEventLog> BY_LEVEL = new WeakHashMap<>();

    /** What kind of news this is — used for filtering and colouring. */
    public enum Kind {
        DISCOVERY,
        CONSTRUCTION,
        RESCUE,
        DANGER,
        INFRASTRUCTURE,
        POPULATION,
        ORGANISATION,
        PRODUCTION;

        public String label() {
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }
    }

    /** One line of colony news. */
    public record Entry(long gameTime, Kind kind, String actor, String message) {

        /** "Citizen #3 discovered an iron deposit" — ready to print. */
        public String line() {
            return actor == null || actor.isBlank() ? message : actor + " " + message;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private long sequence;

    public static synchronized ColonyEventLog of(ServerLevel level) {
        if (level == null) return new ColonyEventLog();
        return BY_LEVEL.computeIfAbsent(level, key -> new ColonyEventLog());
    }

    public static synchronized void clear() {
        BY_LEVEL.clear();
    }

    // ------------------------------------------------------------------ writing

    /**
     * Record one event.
     *
     * <p>Also written to the server log at INFO, because the two audiences want
     * the same facts: a player wants the feed, an operator debugging a stuck
     * colony wants it interleaved with everything else that happened.</p>
     */
    public synchronized void record(long gameTime, Kind kind, String actor, String message) {
        if (message == null || message.isBlank()) return;
        Entry entry = new Entry(gameTime, kind, actor, message);
        entries.addLast(entry);
        sequence++;
        while (entries.size() > CAPACITY) entries.removeFirst();
        LOGGER.info("[Colony/{}] {}", kind.name().toLowerCase(Locale.ROOT), entry.line());
    }

    private void record(ServerLevel level, Kind kind, String actor, String message) {
        record(level == null ? 0L : level.getGameTime(), kind, actor, message);
    }

    // Convenience writers. Named for what happened rather than for a severity,
    // so call sites read as narration.

    public void discovery(ServerLevel level, String actor, String what) {
        record(level, Kind.DISCOVERY, actor, what);
    }

    public void construction(ServerLevel level, String actor, String what) {
        record(level, Kind.CONSTRUCTION, actor, what);
    }

    public void rescue(String actor, String what) {
        record(0L, Kind.RESCUE, actor, what);
    }

    public void rescue(ServerLevel level, String actor, String what) {
        record(level, Kind.RESCUE, actor, what);
    }

    public void danger(ServerLevel level, String actor, String what) {
        record(level, Kind.DANGER, actor, what);
    }

    public void infrastructure(ServerLevel level, String what) {
        record(level, Kind.INFRASTRUCTURE, null, what);
    }

    public void organisation(ServerLevel level, String what) {
        record(level, Kind.ORGANISATION, null, what);
    }

    public void population(ServerLevel level, String what) {
        record(level, Kind.POPULATION, null, what);
    }

    public void production(ServerLevel level, String actor, String what) {
        record(level, Kind.PRODUCTION, actor, what);
    }

    // ------------------------------------------------------------------ reading

    /** The most recent events, newest last, at most {@code limit} of them. */
    public synchronized List<Entry> recent(int limit) {
        return recent(limit, EnumSet.allOf(Kind.class));
    }

    public synchronized List<Entry> recent(int limit, EnumSet<Kind> kinds) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (kinds != null && !kinds.contains(entry.kind())) continue;
            out.add(entry);
        }
        int from = Math.max(0, out.size() - Math.max(1, limit));
        return new ArrayList<>(out.subList(from, out.size()));
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Total events ever recorded, so a viewer can tell what it has missed. */
    public synchronized long sequence() {
        return sequence;
    }
}
