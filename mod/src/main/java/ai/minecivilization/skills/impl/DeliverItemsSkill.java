package ai.minecivilization.skills.impl;

import java.util.EnumMap;
import java.util.Map;

import ai.minecivilization.inventory.CitizenInventory;
import ai.minecivilization.skills.CitizenSkill;
import ai.minecivilization.skills.SkillContext;
import ai.minecivilization.skills.SkillFailure;
import ai.minecivilization.skills.SkillResult;
import ai.minecivilization.skills.SkillType;
import ai.minecivilization.storage.DeliveryPolicy;
import ai.minecivilization.storage.ItemCategory;
import ai.minecivilization.storage.StorageManager;
import ai.minecivilization.storage.StorageNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * DELIVER_ITEMS — empty the bag into the warehouse, one shelf at a time.
 *
 * <p>This used to dump everything into whichever chest happened to be nearest,
 * which is how a settlement ends up with forty containers and no idea what is
 * in any of them. Now the citizen groups what it carries by
 * {@link ItemCategory}, and for each group walks to the chest that holds that
 * category — claiming an unused chest for it if the settlement does not have
 * one yet. A sorted warehouse is the emergent result of everyone doing this,
 * not something anyone had to lay out.</p>
 *
 * <p>The citizen keeps a small personal reserve ({@link DeliveryPolicy}) so it
 * does not walk away from the chest hungry and unable to bridge.</p>
 */
public final class DeliverItemsSkill implements CitizenSkill {

    private final DepositItemSkill deposit = new DepositItemSkill();
    /** Guards against a container that accepts nothing sending us round forever. */
    private int emptyRounds;

    @Override
    public SkillType type() {
        return SkillType.DELIVER_ITEMS;
    }

    @Override
    public boolean canStart(SkillContext context) {
        return StorageManager.get(context.level).active(context.level).stream()
                .anyMatch(StorageNode::isPublic);
    }

    @Override
    public void start(SkillContext context) {
        emptyRounds = 0;
        context.data.remove("deliver.category");
        context.data.remove("node");
        context.data.remove("pos");
    }

    @Override
    public SkillResult tick(SkillContext context) {
        ItemCategory category = context.get("deliver.category", (ItemCategory) null);
        if (category == null) {
            category = nextCategory(context);
            if (category == null) {
                return SkillResult.COMPLETED; // nothing left worth depositing
            }
            if (!openShelf(context, category)) {
                context.fail(SkillFailure.notFound("no storage container to deliver to"));
                return SkillResult.FAILED;
            }
        }

        StorageNode node = context.get("node", (StorageNode) null);
        if (node == null) {
            context.fail(SkillFailure.notFound("no storage container to deliver to"));
            return SkillResult.FAILED;
        }

        BlockPos pos = node.containerPos();
        double distSqr = context.citizen.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSqr > 12.0) {
            // Walk there, and make a way if walking will not do.
            SkillResult arrival = SkillNavigation.approach(context, pos, 12.0, "deliver.walk");
            if (arrival == SkillResult.FAILED) return SkillResult.FAILED;
            if (arrival == SkillResult.RUNNING) return SkillResult.RUNNING;
        }
        context.navigator.stop();

        int before = carriedIn(context, category);
        SkillResult result = deposit.tick(context);
        int after = carriedIn(context, category);

        if (result == SkillResult.RUNNING && after < before) {
            emptyRounds = 0;
            return SkillResult.RUNNING;
        }

        // This shelf is done, full, or refusing the goods: move to the next one.
        context.failure = null;
        if (after >= before) {
            if (++emptyRounds > ItemCategory.values().length) {
                // Every shelf was tried and nothing moved — the warehouse is full.
                return SkillResult.COMPLETED;
            }
        } else {
            emptyRounds = 0;
        }
        closeShelf(context, category);
        return SkillResult.RUNNING;
    }

    // ------------------------------------------------------------------ shelves

    /**
     * The category with the most items to put away, so the biggest win comes
     * first and a citizen interrupted mid-delivery has still done the most
     * useful trip. Ties break on the enum order, which keeps retries stable.
     */
    private static ItemCategory nextCategory(SkillContext context) {
        Map<ItemCategory, Integer> pending = new EnumMap<>(ItemCategory.class);
        CitizenInventory inventory = context.citizen.getInventory();

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.isDamageableItem()) continue; // tools stay
            String id = CitizenInventory.idOf(stack);
            int spare = DeliveryPolicy.depositable(id, inventory.count(id));
            if (spare <= 0) continue;
            pending.merge(ItemCategory.of(id), Math.min(spare, stack.getCount()), Integer::sum);
        }

        ItemCategory best = null;
        int bestCount = 0;
        for (Map.Entry<ItemCategory, Integer> entry : pending.entrySet()) {
            if (context.get("deliver.done." + entry.getKey().name(), false)) continue;
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /** Point the deposit skill at the chest that owns this category. */
    private boolean openShelf(SkillContext context, ItemCategory category) {
        String sample = sampleItemOf(context, category);
        StorageNode node = sample == null
                ? StorageManager.nearest(context.level, context.citizen.blockPosition())
                : StorageManager.bestFor(context.level, sample, context.citizen.blockPosition());
        if (node == null) return false;

        context.put("deliver.category", category);
        context.put("node", node);
        context.put("pos", node.containerPos());
        context.params.target = node.storageId;
        context.params.extra.put(DepositItemSkill.CATEGORY_FILTER, category.name());
        context.startGameTime = context.level.getGameTime();
        return true;
    }

    private void closeShelf(SkillContext context, ItemCategory category) {
        context.data.remove("deliver.category");
        context.data.remove("node");
        context.data.remove("pos");
        context.params.extra.remove(DepositItemSkill.CATEGORY_FILTER);
        // A shelf that could not take its goods must not be offered again this
        // run, or the citizen shuttles between two full chests forever.
        context.put("deliver.done." + category.name(), true);
        context.startGameTime = context.level.getGameTime();
    }

    private static String sampleItemOf(SkillContext context, ItemCategory category) {
        CitizenInventory inventory = context.citizen.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.isDamageableItem()) continue;
            String id = CitizenInventory.idOf(stack);
            if (ItemCategory.of(id) == category) return id;
        }
        return null;
    }

    private static int carriedIn(SkillContext context, ItemCategory category) {
        CitizenInventory inventory = context.citizen.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.isDamageableItem()) continue;
            if (ItemCategory.of(CitizenInventory.idOf(stack)) == category) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    public void cancel(SkillContext context) {
        deposit.cancel(context);
        context.params.extra.remove(DepositItemSkill.CATEGORY_FILTER);
        context.navigator.stop();
    }

    @Override
    public String progressLabel(SkillContext context) {
        ItemCategory category = context.get("deliver.category", (ItemCategory) null);
        StorageNode node = context.get("node", (StorageNode) null);
        if (category == null) return "sorting the bag";
        return "filing " + category.label() + (node == null ? "" : " at " + node.storageId);
    }
}
