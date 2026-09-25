package ai.minecivilization.skills;

/** The full V1 skill registry — new skills are added here only. */
public enum SkillType {
    IDLE,
    MOVE_TO,
    TRAVERSE,
    FOLLOW,
    FIND_BLOCK,
    MINE_BLOCK,
    MINE_AREA,
    FELL_TREE,
    DIG_MINE,
    DIG_TO_SURFACE,
    PICKUP_ITEM,
    PLACE_BLOCK,
    PLACE_SIGN,
    HARVEST_CROP,
    PLANT_CROP,
    CULTIVATE,
    LIGHT_FARM,
    FORAGE,
    EXPLORE,
    TILL_SOIL,
    TEND_LIVESTOCK,
    TAME_WOLF,
    PREPARE_PEN,
    HERD_ANIMAL,
    BREED_ANIMALS,
    HUNT,
    DECORATE,
    CRAFT_ITEM,
    SMELT_ITEM,
    DEPOSIT_ITEM,
    WITHDRAW_ITEM,
    EAT,
    SLEEP,
    BUILD_BLUEPRINT,
    DELIVER_ITEMS;

    /**
     * How long this skill may run before it counts as hung, given the
     * configured base budget.
     *
     * <p>One flat timeout for everything was wrong in both directions. Sixty
     * seconds is generous for placing a block and absurd for felling a mature
     * oak, cutting a staircase out of a hillside or bridging a ravine — all of
     * which were killed mid-job and reported as failures while they were
     * visibly working. The clock is reset by progress anyway; this is the
     * allowance for a job that is genuinely long, not licence to stall.</p>
     *
     * @param base the configured per-skill budget, in ticks
     */
    public int budgetTicks(int base) {
        // This is an allowance for going *quiet*, not for taking a long time:
        // every one of these skills resets the clock whenever it actually digs,
        // places or arrives. A large multiplier therefore does nothing for a
        // healthy job and a great deal for a stuck one — a four-minute budget
        // on TRAVERSE let a single failing climb eat four minutes before
        // anyone noticed, and a citizen felling a big tree ground through
        // eight logs in as many minutes doing exactly that.
        return switch (this) {
            // A little slack: these interleave sub-skills, and the hand-off
            // between two of them is the one moment nothing resets the clock.
            case FELL_TREE, DIG_MINE, DIG_TO_SURFACE, MINE_AREA,
                 BUILD_BLUEPRINT, DELIVER_ITEMS -> base * 2;
            default -> base;
        };
    }
}
