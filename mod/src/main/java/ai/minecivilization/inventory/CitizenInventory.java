package ai.minecivilization.inventory;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Citizen inventory — a physically real NonNullList carried on the entity.
 * No free items, ever: every insert must come from the world, every place
 * consumes exactly one stack.
 */
public final class CitizenInventory {
    public static final int SIZE = 24;

    private final NonNullList<ItemStack> items;

    /** Fixed-size citizen inventory (24 slots). */
    public CitizenInventory() {
        this(SIZE);
    }

    public CitizenInventory(int slots) {
        if (slots < 1 || slots > 99) {
            throw new IllegalArgumentException("invalid citizen inventory size: " + slots);
        }
        this.items = NonNullList.withSize(slots, ItemStack.EMPTY);
    }

    public NonNullList<ItemStack> items() {
        return items;
    }

    public ItemStack get(int slot) {
        return items.get(slot);
    }

    public void set(int slot, ItemStack stack) {
        items.set(slot, stack);
    }

    // ------------------------------------------------------------------
    // Container-shaped aliases: these let entity helpers, observations and
    // vanilla interactions (getItem/setItem/countItem) share one inventory.

    public int getContainerSize() {
        return items.size();
    }

    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
    }

    public void clearContent() {
        clear();
    }

    public int countItem(Item item) {
        int total = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public void clear() {
        for (int i = 0; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
    }

    public int size() {
        return items.size();
    }

    public int count(String itemId) {
        int total = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public boolean containsAtLeast(String itemId, int qty) {
        return count(itemId) >= qty;
    }

    /** Insert, returning the leftover count that did not fit. */
    public int insert(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        // merge into existing stacks first
        for (ItemStack existing : items) {
            if (existing.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < existing.getMaxStackSize()) {
                int space = existing.getMaxStackSize() - existing.getCount();
                int moved = Math.min(space, stack.getCount());
                existing.grow(moved);
                stack.shrink(moved);
                if (stack.isEmpty()) return 0;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isEmpty()) {
                items.set(i, stack.copy());
                return 0;
            }
        }
        return stack.getCount();
    }

    /** Extract up to {@code qty} of itemId; returns extracted count. */
    public int extract(String itemId, int qty) {
        int remaining = qty;
        for (int i = 0; i < items.size() && remaining > 0; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || !idOf(stack).equals(itemId)) continue;
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            remaining -= take;
            if (stack.isEmpty()) items.set(i, ItemStack.EMPTY);
        }
        return qty - remaining;
    }

    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    /** First food item stack index, or -1. */
    public int firstFoodSlot() {
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
                return i;
            }
        }
        return -1;
    }

    public List<ItemStack> nonToolStacks() {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : items) {
            if (!stack.isEmpty() && !stack.isDamageableItem()) {
                result.add(stack);
            }
        }
        return result;
    }

    /** Compact summary {"minecraft:oak_log": 16, ...} — used in observations. */
    public java.util.LinkedHashMap<String, Integer> summary() {
        java.util.LinkedHashMap<String, Integer> map = new java.util.LinkedHashMap<>();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;
            map.merge(idOf(stack), stack.getCount(), Integer::sum);
        }
        return map;
    }

    public static String idOf(ItemStack stack) {
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key == null ? "minecraft:air" : key.toString();
    }

    public static Item itemById(String id) {
        var key = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (key == null) return Items.AIR;
        return ForgeRegistries.ITEMS.getValue(key);
    }

    public void save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    public void load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        clear();
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
