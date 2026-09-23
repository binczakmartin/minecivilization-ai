package ai.minecivilization.livestock;

/** Limits count juveniles and births already requested, not just visible adults. */
public final class HerdPolicy {
    public static boolean canBreed(int total, int pendingBirths, int readyAdults, int limit) {
        return readyAdults >= 2 && total + pendingBirths < limit;
    }
    public static boolean canCull(int total, int adults, boolean baby, int limit) {
        return !baby && total > limit && adults > 2;
    }
    public static boolean canHarvestForFood(int total, int adults, boolean baby, int limit, boolean foodNeeded) {
        return canCull(total, adults, baby, limit) || (foodNeeded && !baby && total >= limit && adults > 2);
    }
    public static boolean canRecruit(int total, int limit) { return total < limit; }
    private HerdPolicy() {}
}
