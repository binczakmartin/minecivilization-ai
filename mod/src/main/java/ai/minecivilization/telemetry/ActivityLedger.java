package ai.minecivilization.telemetry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ai.minecivilization.citizen.CitizenPlan;
import ai.minecivilization.entity.CitizenEntity;
import ai.minecivilization.skills.SkillType;

/**
 * Where the colony's time actually goes.
 *
 * <p>"They spend most of their time doing nothing" is either the single most
 * important fact about this mod or a misreading of what busy looks like, and
 * there was no way to tell which. Every other measurement here is a snapshot —
 * what a citizen is doing at the instant somebody asks — which is exactly the
 * wrong shape for the question. A colony can look busy in every snapshot and
 * still spend four fifths of its life walking to things.</p>
 *
 * <p>This accumulates instead: one sample per citizen per tick, bucketed by
 * what that citizen was actually doing, so the answer comes out as a
 * percentage of colony-lifetime rather than an impression. It is what turns
 * "they seem useless" into "sixty per cent of all citizen time is spent
 * walking", which is a thing that can be fixed.</p>
 *
 * <p>Deliberately cheap: one map increment per citizen per tick, no
 * allocation, and the totals are plain longs.</p>
 */
public final class ActivityLedger {

    /** What a tick of citizen time was spent on. */
    private static final Map<String, long[]> BUCKETS = new HashMap<>();
    private static long totalTicks;
    private static long startedAtTick = -1;

    /** Buckets that count as the colony getting nothing done. */
    private static final List<String> WASTED = List.of("idle", "no work", "blocked", "resting");

    private ActivityLedger() {
    }

    /** One citizen, one tick. Called from the entity's own tick. */
    public static void sample(CitizenEntity citizen, long gameTime) {
        if (citizen == null || citizen.isRemoved()) return;
        if (startedAtTick < 0) startedAtTick = gameTime;

        BUCKETS.computeIfAbsent(labelFor(citizen), key -> new long[1])[0]++;
        totalTicks++;
    }

    /**
     * The bucket a citizen belongs in right now.
     *
     * <p>Named for what a player would say it is doing, not for the class that
     * happens to be running: "walking" covers every way of getting somewhere,
     * because from outside they are the same thing and they add up.</p>
     */
    private static String labelFor(CitizenEntity citizen) {
        var brain = citizen.getCitizenBrain();
        if (brain.isLost()) return "lost";
        if (citizen.isInCombat()) return "fighting";

        CitizenPlan.Task task = brain.currentTaskOrNull();
        if (task == null) {
            return brain.isDecisionPending() ? "waiting for a decision" : "no work";
        }

        SkillType skill = citizen.getExecutor().activeSkillType();
        if (skill == null) return "starting " + task.type.name().toLowerCase(Locale.ROOT);
        return switch (skill) {
            case MOVE_TO, FOLLOW -> "walking";
            // Kept apart from walking on purpose: one is pathing, the other is
            // digging and bridging a way through. Lumped together they hid
            // which of the two was eating the colony's day.
            case TRAVERSE -> "making a way";
            case FIND_BLOCK -> "searching";
            case MINE_BLOCK, MINE_AREA, DIG_MINE, DIG_TO_SURFACE -> "mining";
            case FELL_TREE -> "felling";
            case CRAFT_ITEM -> "crafting";
            case SMELT_ITEM -> "smelting";
            case BUILD_BLUEPRINT, PLACE_BLOCK, PLACE_SIGN -> "building";
            case DEPOSIT_ITEM, WITHDRAW_ITEM, DELIVER_ITEMS, PICKUP_ITEM -> "hauling";
            case HARVEST_CROP, PLANT_CROP, CULTIVATE, TILL_SOIL, FORAGE -> "farming";
            case TEND_LIVESTOCK, HERD_ANIMAL, BREED_ANIMALS, TAME_WOLF,
                 PREPARE_PEN -> "livestock";
            case HUNT -> "hunting";
            case EXPLORE -> "exploring";
            case IDLE -> "resting";
            default -> skill.name().toLowerCase(Locale.ROOT);
        };
    }

    // ------------------------------------------------------------------ reading

    /** One line of the breakdown. */
    public record Slice(String label, long ticks, int percent) {
    }

    /** Where the time went, biggest share first. */
    public static synchronized List<Slice> breakdown() {
        List<Slice> out = new ArrayList<>(BUCKETS.size());
        long total = Math.max(1, totalTicks);
        for (Map.Entry<String, long[]> entry : BUCKETS.entrySet()) {
            long ticks = entry.getValue()[0];
            out.add(new Slice(entry.getKey(), ticks, (int) Math.round(ticks * 100.0 / total)));
        }
        out.sort(Comparator.comparingLong(Slice::ticks).reversed());
        return out;
    }

    /**
     * Share of colony time spent doing nothing useful, 0–100.
     *
     * <p>The one number to watch. Anything above about a fifth means the work
     * board is failing to find these people something to do.</p>
     */
    public static synchronized int wastedPercent() {
        long wasted = 0;
        for (Map.Entry<String, long[]> entry : BUCKETS.entrySet()) {
            if (WASTED.contains(entry.getKey())) wasted += entry.getValue()[0];
        }
        return (int) Math.round(wasted * 100.0 / Math.max(1, totalTicks));
    }

    /** Citizen-seconds observed, so a reader knows how much to trust the split. */
    public static synchronized long observedSeconds() {
        return totalTicks / 20;
    }

    public static synchronized void reset() {
        BUCKETS.clear();
        totalTicks = 0;
        startedAtTick = -1;
    }
}
