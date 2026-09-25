package ai.minecivilization.colony;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * What a sign should say.
 *
 * <p>Deciding the words is separate from placing the post, because the words
 * are the part that has to be right: four lines of fifteen characters is a
 * brutal budget, and a sign reading {@code MANAGED FOREST} with the name cut
 * off is worse than no sign. The composition is pure and tested; only the
 * last few helpers need a world, and only to look up a name.</p>
 *
 * <p>The encoding is deliberately plain English in capitals, because it is
 * read by three audiences — the player walking past, the citizens reading
 * signs back into {@link SignRegistry}, and the cognition service, which gets
 * the same strings in its observations.</p>
 */
public final class SignPlan {

    /** Title and detail, ready for {@link Signpost#write}. */
    public record Text(SignKind kind, String title, String detail) {

        /** The whole sign as one line, for logs and the colony feed. */
        public String flat() {
            return detail == null || detail.isBlank() ? title : title + " — " + detail;
        }
    }

    private SignPlan() {
    }

    // ------------------------------------------------------------------ places

    /** {@code TOWN HALL} over the colony's civic centre. */
    public static Text townHall(String colonyName) {
        return new Text(SignKind.TOWN_HALL, SignKind.TOWN_HALL.headline(),
                colonyName == null || colonyName.isBlank() ? "the colony" : colonyName);
    }

    /**
     * A district marker, e.g. {@code FARM DISTRICT} / {@code Farmland 2}.
     *
     * <p>Numbered rather than named where the colony has more than one, so a
     * player can say "meet me at mine 2" and be understood.</p>
     */
    public static Text district(ZoneType type, String name, int index) {
        SignKind kind = SignKind.forZone(type);
        String title = kind.headline();
        if (index > 1) title = title + " " + index;
        return new Text(kind, title, name == null ? "" : name);
    }

    /** {@code MINE #1} / {@code iron · y=16}. */
    public static Text mine(int index, String ore, int depth) {
        String detail = ore == null || ore.isBlank()
                ? "y=" + depth
                : Signpost.titleForOre(ore) + " y=" + depth;
        return new Text(SignKind.MINE, "MINE #" + Math.max(1, index), detail);
    }

    // ------------------------------------------------------------------ roads

    /** {@code ROAD} / {@code -> IRON MINE}, the colony's signposting convention. */
    public static Text road(String destination) {
        String where = destination == null || destination.isBlank()
                ? "onward" : destination.toUpperCase(Locale.ROOT);
        return new Text(SignKind.ROAD, "ROAD", "-> " + where);
    }

    /** The flat form used as a task label, e.g. {@code ROAD -> IRON MINE}. */
    public static String roadSign(ServerLevel level, BlockPos destination) {
        return "ROAD -> " + describe(level, destination).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ notices

    /** A building being put up, with how far along it is. */
    public static Text project(String projectName, int percentComplete) {
        String name = projectName == null || projectName.isBlank() ? "Building" : projectName;
        return new Text(SignKind.PROJECT, "BUILDING", name + " " + clampPercent(percentComplete) + "%");
    }

    /** {@code DANGER} / {@code DEEP CAVE}, placed where something went wrong. */
    public static Text danger(String what) {
        return new Text(SignKind.DANGER, "DANGER",
                what == null || what.isBlank() ? "" : what.toUpperCase(Locale.ROOT));
    }

    public static Text cave(int depth) {
        return new Text(SignKind.CAVE, "CAVE", "down to y=" + depth);
    }

    /** The town notice board: how many people, how much food, what is being built. */
    public static Text notice(int population, int food, int projects) {
        return new Text(SignKind.NOTICE, "NOTICE",
                population + " people " + food + " food " + projects + " builds");
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    // ------------------------------------------------------------------ naming places

    /**
     * A short name for somewhere, for putting on a signpost.
     *
     * <p>Tries the colony's own vocabulary first — a district, then a
     * remembered landmark — and falls back to coordinates, which are ugly but
     * never wrong.</p>
     */
    public static String describe(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return "?";
        Zone zone = ZoneManager.get(level).at(pos);
        if (zone != null) return zone.type.label();

        BlockPos centre = ZoneManager.get(level).townCenter(level);
        if (centre.distSqr(pos) <= 32 * 32) return "town";

        LandmarkRegistry registry = LandmarkRegistry.get(level);
        for (LandmarkKind kind : LandmarkKind.values()) {
            BlockPos nearest = registry.nearest(kind, pos);
            if (nearest != null && nearest.distSqr(pos) <= 12 * 12) return kind.label();
        }
        return pos.getX() + "," + pos.getZ();
    }
}
