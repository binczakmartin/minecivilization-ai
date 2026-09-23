package ai.minecivilization.citizen;

/**
 * Bounded recovery for a citizen stranded outside the colony.
 *
 * <p>The policy deliberately separates two ideas that used to be conflated:
 * being far enough away to need a walk home, and being unable to complete the
 * last walk home. A failed route gets an exponential cooldown; it is not
 * reissued on the very next tick. Once the citizen is safely back inside the
 * broad colony leash, the failed-route budget is reset.</p>
 */
final class HomewardPolicy {
    static final int STRAY_DISTANCE = 120;
    static final int HOME_DISTANCE = 60;

    private static final int BASE_BACKOFF_TICKS = 200;
    private static final int MAX_BACKOFF_TICKS = 3_200;

    enum Action {
        AT_HOME,
        WITHIN_LEASH,
        WAIT,
        START
    }

    private int failures;
    private long retryAt = Long.MIN_VALUE;

    Action evaluate(double distance, long now) {
        if (distance <= HOME_DISTANCE) {
            reset();
            return Action.AT_HOME;
        }
        // A citizen that made any real progress toward town does not need to
        // keep retrying the exact same impossible centre-column route.
        if (distance < STRAY_DISTANCE) {
            reset();
            return Action.WITHIN_LEASH;
        }
        if (now < retryAt) return Action.WAIT;
        return Action.START;
    }

    void failed(long now) {
        failures = Math.min(failures + 1, 16);
        retryAt = now + backoffTicks(failures);
    }

    void reset() {
        failures = 0;
        retryAt = Long.MIN_VALUE;
    }

    int failureCount() {
        return failures;
    }

    long retryAt() {
        return retryAt;
    }

    static int backoffTicks(int failures) {
        if (failures <= 1) return BASE_BACKOFF_TICKS;
        int shift = Math.min(failures - 1, 16);
        long delay = (long) BASE_BACKOFF_TICKS << shift;
        return (int) Math.min(delay, MAX_BACKOFF_TICKS);
    }
}
