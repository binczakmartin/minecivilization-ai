package ai.minecivilization.construction;

import java.util.ArrayList;
import java.util.List;

import ai.minecivilization.architecture.Palette;

/**
 * "An oak house can be built of acacia."
 *
 * <p>Every blueprint names one wood species, and the colony's first buildings
 * were all oak. Settled in a savanna, the citizens had acacia logs in their
 * packs and not an oak tree for a hundred blocks: every house sat at 0% while
 * they failed, over and over, to make oak planks out of acacia. A player would
 * simply build with the wood they have. This is that rule: a wooden block of
 * one species stands in for the same block of any other species.</p>
 *
 * <p>Pure string data, unit tested, no registry.</p>
 */
public final class WoodSwap {

    private WoodSwap() {
    }

    /** The species in {@code id} (e.g. {@code dark_oak} in {@code minecraft:dark_oak_stairs}), or null. */
    public static String speciesOf(String id) {
        if (id == null) return null;
        String name = id.substring(id.indexOf(':') + 1);
        if (name.startsWith("stripped_")) name = name.substring("stripped_".length());
        String best = null;
        for (String species : Palette.SPECIES) {
            // Longest match wins, so "dark_oak_log" is dark oak, not oak.
            if (name.startsWith(species + "_") && (best == null || species.length() > best.length())) {
                best = species;
            }
        }
        return best;
    }

    /** True for ids that belong to a wood species ({@code oak_planks}, {@code acacia_door}...). */
    public static boolean isWooden(String id) {
        return speciesOf(id) != null;
    }

    /** The same block or item in another species. */
    public static String withSpecies(String id, String species) {
        String from = speciesOf(id);
        if (from == null || species == null) return id;
        return id.replaceFirst(from + "_", species + "_");
    }

    /**
     * Every species version of this id, the named one first. A non-wooden id
     * is its own only variant.
     */
    public static List<String> variants(String id) {
        List<String> out = new ArrayList<>();
        out.add(id);
        if (!isWooden(id)) return out;
        for (String species : Palette.SPECIES) {
            String other = withSpecies(id, species);
            if (!out.contains(other)) out.add(other);
        }
        return out;
    }

    /** True when {@code actual} is {@code expected} or the same thing in another wood. */
    public static boolean sameKind(String expected, String actual) {
        if (expected == null || actual == null) return false;
        if (expected.equals(actual)) return true;
        String species = speciesOf(actual);
        return species != null && isWooden(expected) && withSpecies(expected, species).equals(actual);
    }
}
