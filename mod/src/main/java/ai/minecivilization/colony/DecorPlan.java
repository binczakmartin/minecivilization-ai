package ai.minecivilization.colony;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Where a colony puts its torches, its fire and its flowers.
 *
 * <p>Half of this is not decoration at all. An unlit settlement spawns hostile
 * mobs inside its own streets at night, and citizens die to things that should
 * never have appeared — so the lighting grid is a survival measure that happens
 * to look like civic pride. The campfire and the flower beds are the part that
 * is purely for the look of the place, and a settlement that looks lived in is
 * the whole point of building one.</p>
 *
 * <p>Deterministic: the same district always produces the same layout, so a
 * citizen interrupted halfway through decorating picks up exactly where it left
 * off rather than scattering torches at random.</p>
 */
public final class DecorPlan {

    /** Blocks between torches. Seven keeps the ground above the spawn threshold. */
    public static final int TORCH_SPACING = 7;
    /** Flower beds per district — enough to notice, few enough to look placed. */
    public static final int FLOWER_BEDS = 6;

    /** One thing to put down, and what it is. */
    public record Spot(BlockPos pos, String block) {
    }

    private DecorPlan() {
    }

    /**
     * The lighting grid for a district: torches on a regular lattice, inset from
     * the edges so they land in the district rather than in the street.
     */
    public static List<Spot> lighting(ZoneLayout.Bounds bounds, int surfaceY) {
        List<Spot> spots = new ArrayList<>();
        int inset = TORCH_SPACING / 2;
        for (int x = bounds.minX() + inset; x <= bounds.maxX(); x += TORCH_SPACING) {
            for (int z = bounds.minZ() + inset; z <= bounds.maxZ(); z += TORCH_SPACING) {
                spots.add(new Spot(new BlockPos(x, surfaceY, z), "minecraft:torch"));
            }
        }
        return spots;
    }

    /** The hearth at the middle of a district — a campfire people gather round. */
    public static Spot centrepiece(ZoneLayout.Bounds bounds, int surfaceY) {
        return new Spot(new BlockPos(bounds.centerX(), surfaceY, bounds.centerZ()),
                "minecraft:campfire");
    }

    /**
     * Flower beds, spread over the district by a fixed pattern rather than at
     * random — the same district decorates the same way every time.
     */
    public static List<Spot> flowerBeds(ZoneLayout.Bounds bounds, int surfaceY,
                                        List<String> available) {
        List<Spot> spots = new ArrayList<>();
        if (available == null || available.isEmpty()) return spots;

        int spanX = Math.max(1, bounds.sizeX() - 2);
        int spanZ = Math.max(1, bounds.sizeZ() - 2);
        for (int i = 0; i < FLOWER_BEDS; i++) {
            // A coprime stride walks the district without repeating a cell.
            int x = bounds.minX() + 1 + (i * 5 + 1) % spanX;
            int z = bounds.minZ() + 1 + (i * 3 + 2) % spanZ;
            String flower = available.get(i % available.size());
            spots.add(new Spot(new BlockPos(x, surfaceY, z), flower));
        }
        return spots;
    }

    /**
     * Everything a district wants, in the order it should be done: light first,
     * because that is the part that keeps citizens alive.
     */
    public static List<Spot> forDistrict(ZoneType type, ZoneLayout.Bounds bounds,
                                         int surfaceY, List<String> flowers) {
        List<Spot> spots = new ArrayList<>(lighting(bounds, surfaceY));
        if (type == ZoneType.CIVIC) {
            spots.add(centrepiece(bounds, surfaceY));
        }
        if (type == ZoneType.CIVIC || type == ZoneType.RESIDENTIAL) {
            spots.addAll(flowerBeds(bounds, surfaceY, flowers));
        }
        return spots;
    }

    /** The decorative blocks a colony can plant, given what it is carrying. */
    public static List<String> flowersAmong(List<String> carried) {
        List<String> out = new ArrayList<>();
        if (carried == null) return out;
        for (String item : carried) {
            if (isFlower(item)) out.add(item);
        }
        return out;
    }

    public static boolean isFlower(String itemId) {
        if (itemId == null) return false;
        String name = itemId.substring(itemId.indexOf(':') + 1);
        return name.endsWith("_tulip") || name.equals("dandelion") || name.equals("poppy")
                || name.equals("blue_orchid") || name.equals("allium")
                || name.equals("azure_bluet") || name.equals("oxeye_daisy")
                || name.equals("cornflower") || name.equals("lily_of_the_valley")
                || name.equals("sunflower") || name.equals("lilac")
                || name.equals("rose_bush") || name.equals("peony");
    }
}
