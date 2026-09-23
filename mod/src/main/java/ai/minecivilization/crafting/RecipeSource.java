package ai.minecivilization.crafting;

import java.util.List;

/**
 * Everything the planner is allowed to know about how items come into being.
 *
 * <p>Injected rather than read from the registries directly, so craft planning
 * is unit-tested against small hand-written recipe sets instead of the whole
 * vanilla recipe book. {@link VanillaRecipeSource} is the real implementation.</p>
 */
public interface RecipeSource {

    /**
     * Every known way to obtain {@code itemId}, in no particular order — the
     * planner ranks them itself against the citizen's inventory.
     *
     * @return the productions, or an empty list when the item cannot be
     *         obtained at all (the planner then reports the plan as impossible
     *         rather than inventing a source).
     */
    List<Production> productionsOf(String itemId);
}
