package ai.minecivilization.farming;

import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Explicit seed mapping: unsupported items must never silently become wheat. */
public final class Crops {
    public static final Map<String, String> SEEDS = Map.ofEntries(
        Map.entry("minecraft:wheat_seeds", "minecraft:wheat"),
        Map.entry("minecraft:carrot", "minecraft:carrots"),
        Map.entry("minecraft:potato", "minecraft:potatoes"),
        Map.entry("minecraft:beetroot_seeds", "minecraft:beetroots"),
        Map.entry("minecraft:sweet_berries", "minecraft:sweet_berry_bush"),
        Map.entry("minecraft:sugar_cane", "minecraft:sugar_cane"),
        Map.entry("minecraft:pumpkin_seeds", "minecraft:pumpkin_stem"),
        Map.entry("minecraft:melon_seeds", "minecraft:melon_stem"),
        Map.entry("minecraft:nether_wart", "minecraft:nether_wart"));

    /** The item actually obtained when the corresponding crop block is harvested. */
    public static final Map<String, String> PRODUCE = Map.ofEntries(
        Map.entry("minecraft:wheat", "minecraft:wheat"),
        Map.entry("minecraft:carrots", "minecraft:carrots"),
        Map.entry("minecraft:potatoes", "minecraft:potatoes"),
        Map.entry("minecraft:beetroots", "minecraft:beetroots"),
        Map.entry("minecraft:sweet_berry_bush", "minecraft:sweet_berries"),
        Map.entry("minecraft:sugar_cane", "minecraft:sugar_cane"),
        Map.entry("minecraft:nether_wart", "minecraft:nether_wart"),
        Map.entry("minecraft:pumpkin", "minecraft:pumpkin"),
        Map.entry("minecraft:melon", "minecraft:melon"));
    public static String seedFor(String crop) {
        return SEEDS.entrySet().stream().filter(e -> e.getValue().equals(crop))
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }
    public static String produceFor(String crop) {
        return PRODUCE.get(crop);
    }
    public static BlockState state(String seed) {
        String id = SEEDS.get(seed);
        if (id == null) return null;
        return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
            net.minecraft.resources.ResourceLocation.parse(id)).defaultBlockState();
    }
    public static boolean ripe(BlockState state, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) return crop.isMaxAge(state);
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return state.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE) >= 2;
        if (state.is(Blocks.SUGAR_CANE)) return level.getBlockState(pos.below()).is(Blocks.SUGAR_CANE);
        if (state.is(Blocks.NETHER_WART)) return state.getValue(net.minecraft.world.level.block.NetherWartBlock.AGE) >= 3;
        return state.is(Blocks.PUMPKIN) || state.is(Blocks.MELON);
    }
    public static boolean needsFarmland(String seed) {
        return SEEDS.containsKey(seed) && !seed.equals("minecraft:sweet_berries")
            && !seed.equals("minecraft:sugar_cane") && !seed.equals("minecraft:nether_wart");
    }
    private Crops() {}
}
