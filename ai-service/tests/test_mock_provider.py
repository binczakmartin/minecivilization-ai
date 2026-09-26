"""MockProvider policy: deterministic, safe, no free resources."""

from minecivilization_ai.llm.base import LLMRequest
from minecivilization_ai.llm.mock import MockProvider
from minecivilization_ai.schemas import Decision, GoalType, TaskType


def payload(obs: dict, reason: str = "no_goal") -> LLMRequest:
    return LLMRequest(system="s", payload={"observation": obs, "reason": reason},
                      schema_name="decision")


def obs(**kwargs) -> dict:
    base = {
        # hunger is 0..100 (100 = full), the scale the mod actually sends — not
        # the vanilla 0..20 food bar. Written as 18 here, these fixtures were
        # quietly describing a starving citizen.
        "citizen": {"name": "Alex", "profession": "BUILDER", "health": 20, "hunger": 90},
        "inventory": {},
        # A colony that already owns a container: the normal state a few minutes
        # in. Tests about the empty-warehouse case set known_storage themselves.
        "civilization": {"population": 5, "food_reserve": 400, "active_projects": 0,
                         "known_storage": 1},
        "current_goal": None,
        "task_failures": 0,
    }
    base.update(kwargs)
    return base


async def test_no_wood_gathers_oak_logs():
    p = MockProvider()
    out = await p.generate_structured(payload(obs()))
    d = Decision.model_validate(out.data)
    assert d.goal.type == GoalType.INCREASE_RESOURCE
    assert d.goal.resource == "minecraft:oak_log"
    assert d.goal.target_quantity == 16
    assert d.tasks[0].type == TaskType.GATHER
    assert d.tasks[0].quantity == 16


async def test_low_food_triggers_harvest_once_the_citizen_is_equipped():
    """A tooled citizen answers an empty larder by farming."""
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:wooden_hoe"] = 1
    o = obs(citizen=citizen_with("BUILDER"), inventory=inv, known_memories=PLACED,
            civilization={"population": 5, "food_reserve": 10, "active_projects": 0,
                          "known_storage": 1})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.HARVEST_FOOD
    assert d.tasks[0].type == TaskType.HARVEST


async def test_an_empty_larder_is_ignored_while_the_colony_has_nowhere_to_store_food():
    """A colony with no container reads 0 food whatever its fields produce.
    Acting on that reading blocked the rule that builds the chest, and miners
    spent whole sessions foraging for wheat seeds instead of mining."""
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:wooden_hoe"] = 1
    o = obs(citizen=citizen_with("MINER"), inventory=inv, known_memories=PLACED,
            civilization={"population": 5, "food_reserve": 0, "active_projects": 0,
                          "known_storage": 0})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type != GoalType.HARVEST_FOOD, "a miner should be mining"


async def test_an_empty_larder_does_not_outrank_the_tool_bootstrap():
    """The bug this ordering fixes: a colony with no chest reads 0 food
    forever, so treating that as an emergency had every citizen farming for
    its whole life and the settlement never built a single tool."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 32},
            civilization={"population": 5, "food_reserve": 0, "active_projects": 0})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type != GoalType.HARVEST_FOOD, \
        "an unequipped citizen must bootstrap before farming"
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.goal.resource == "minecraft:oak_planks"


async def test_a_starving_citizen_still_eats_before_anything_else():
    """Personal hunger is the one food rule that outranks the bootstrap."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 32},
            civilization={"population": 5, "food_reserve": 0, "active_projects": 0})
    o["citizen"] = dict(o["citizen"], hunger=3)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.HARVEST_FOOD


async def test_repeated_failures_disengage():
    p = MockProvider()
    o = obs(current_goal="INCREASE_RESOURCE|minecraft:oak_log", task_failures=3)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.IDLE
    assert d.tasks[0].type == TaskType.REST


async def test_loop_breaker_fires_after_goal_was_cleared():
    """The brain clears current_goal before requesting the next decision
    (reason=task_failed) — the breaker must still fire, otherwise the mock
    re-issues the same failing GATHER forever (stuck-in-trees livelock)."""
    p = MockProvider()
    o = obs(current_goal=None, task_failures=3)
    d = Decision.model_validate(
        (await p.generate_structured(payload(o, reason="task_failed"))).data)
    assert d.goal.type == GoalType.IDLE
    assert d.tasks[0].type == TaskType.REST


async def test_continues_current_goal_without_churn():
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 3},
            current_goal="INCREASE_RESOURCE|minecraft:oak_log")
    d = Decision.model_validate(
        (await p.generate_structured(payload(o, reason="periodic"))).data)
    assert d.goal.type == GoalType.INCREASE_RESOURCE
    assert d.tasks[0].type == TaskType.GATHER


async def test_deterministic_output():
    p = MockProvider()
    a = (await p.generate_structured(payload(obs()))).data
    b = (await p.generate_structured(payload(obs()))).data
    assert a == b


async def test_crafts_planks_when_wood_is_stockpiled():
    """Enough logs + food security -> convert carried logs into planks (CRAFT task)."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 16},
            civilization={"population": 5, "food_reserve": 400, "active_projects": 0})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.CRAFT_ITEM
    assert d.goal.resource == "minecraft:oak_planks"
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].quantity == 16


async def test_craft_goal_continues_without_churn():
    """An in-flight CRAFT_ITEM goal is continued, not replaced."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 16},
            current_goal="CRAFT_ITEM|minecraft:oak_planks")
    d = Decision.model_validate(
        (await p.generate_structured(payload(o, reason="periodic"))).data)
    assert d.goal.type == GoalType.CRAFT_ITEM
    assert d.tasks[0].type == TaskType.CRAFT


# ---------------------------------------------------------------- bootstrap ladder
# Wood -> planks -> crafting table (placed!) -> sticks -> wooden tools, then roles.

def tool_set() -> dict:
    """A citizen who finished the wood bootstrap: tools crafted, table placed.

    Includes a sword, because arming comes before trade work — an unarmed
    colony is one that gets chased around its own fields.
    """
    return {
        "minecraft:oak_log": 16,
        "minecraft:oak_planks": 16,
        "minecraft:stick": 4,
        "minecraft:wooden_pickaxe": 1,
        "minecraft:wooden_axe": 1,
        "minecraft:wooden_shovel": 1,
        "minecraft:wooden_sword": 1,
        "minecraft:leather_chestplate": 1,
    }


PLACED = ["placed:minecraft:crafting_table"]


def citizen_with(profession: str) -> dict:
    return {"name": "Sam", "profession": profession, "health": 20, "hunger": 90}


async def test_crafts_crafting_table_after_planks():
    """Planks are stocked -> the next step of the chain is the table itself."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 16, "minecraft:oak_planks": 16})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.CRAFT_ITEM
    assert d.goal.resource == "minecraft:crafting_table"
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].quantity == 1


async def test_places_table_carried_in_inventory():
    """A crafted table must be PUT DOWN, not left in the inventory forever."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 16, "minecraft:oak_planks": 16,
                       "minecraft:crafting_table": 1})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.CRAFT_ITEM
    assert d.goal.resource == "minecraft:crafting_table"
    assert d.tasks[0].type == TaskType.PLACE
    assert d.tasks[0].block == "minecraft:crafting_table"


async def test_placed_memory_prevents_a_second_table():
    """known_memories says the workstation already stands: skip to the tools."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 16, "minecraft:oak_planks": 16,
                       "minecraft:wooden_sword": 1},
            known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].resource == "minecraft:stick"
    assert d.tasks[0].quantity == 4


async def test_crafts_wooden_pickaxe_once_sticks_are_ready():
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 16, "minecraft:oak_planks": 16,
                       "minecraft:stick": 4, "minecraft:wooden_sword": 1},
            known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.CRAFT_ITEM
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].resource == "minecraft:wooden_pickaxe"
    assert d.tasks[0].quantity == 1


async def test_an_unarmed_citizen_makes_a_sword_before_its_tools():
    """The first night comes before the shovel is needed."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 16, "minecraft:oak_planks": 16,
                       "minecraft:stick": 4},
            known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].resource == "minecraft:wooden_sword"


async def test_miner_mines_cobble_from_stone_after_wooden_tools():
    """With the wooden kit done, the miner's first trade step is real stone."""
    p = MockProvider()
    o = obs(citizen=citizen_with("MINER"), inventory=tool_set(),
            known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.INCREASE_RESOURCE
    assert d.goal.resource == "minecraft:cobblestone"
    assert d.tasks[0].type == TaskType.GATHER
    assert d.tasks[0].resource == "minecraft:cobblestone"
    assert d.tasks[0].quantity == 16
    # cobblestone drops from stone blocks: the task names the block explicitly
    assert d.tasks[0].block == "minecraft:stone"


async def test_miner_gets_sticks_before_stone_tools():
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:stick"] = 0
    inv["minecraft:cobblestone"] = 16
    o = obs(citizen=citizen_with("MINER"), inventory=inv, known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].resource == "minecraft:stick"


async def test_miner_crafts_stone_pickaxe_with_cobble_and_sticks():
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:cobblestone"] = 16
    o = obs(citizen=citizen_with("MINER"), inventory=inv, known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].resource == "minecraft:stone_pickaxe"
    assert d.tasks[0].quantity == 1


# ---------------------------------------------------------------- organized roles

async def test_roles_self_organize():
    """Same ready state, five professions -> five different trades."""
    p = MockProvider()

    lj = obs(citizen=citizen_with("LUMBERJACK"), inventory=tool_set(),
             known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(lj))).data)
    assert d.tasks[0].type == TaskType.GATHER
    assert d.tasks[0].resource == "minecraft:oak_log"
    assert d.tasks[0].quantity == 32

    mn = obs(citizen=citizen_with("MINER"), inventory=tool_set(),
             known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(mn))).data)
    assert d.tasks[0].resource == "minecraft:cobblestone"

    inv = tool_set()
    inv["minecraft:wheat"] = 20
    inv["minecraft:wooden_hoe"] = 1   # a farmer without one goes and makes it first
    fm = obs(citizen=citizen_with("FARMER"), inventory=inv, known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(fm))).data)
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].resource == "minecraft:bread"
    assert d.tasks[0].quantity == 6

    bd = obs(citizen=citizen_with("BUILDER"), inventory=tool_set(),
             known_memories=PLACED,
             civilization={"population": 5, "food_reserve": 400,
                           "active_projects": 1, "known_storage": 1})
    d = Decision.model_validate((await p.generate_structured(payload(bd))).data)
    assert d.goal.type == GoalType.BUILD_PROJECT
    assert d.tasks[0].type == TaskType.BUILD


async def test_lumberjack_delivers_surplus_to_storage():
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:oak_log"] = 40
    o = obs(citizen=citizen_with("LUMBERJACK"), inventory=inv, known_memories=PLACED,
            civilization={"population": 5, "food_reserve": 400,
                          "active_projects": 0, "known_storage": 1})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.DELIVER_RESOURCE
    assert d.tasks[0].type == TaskType.DELIVER


async def test_farmer_delivers_bread_to_storage():
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:bread"] = 6
    inv["minecraft:wooden_hoe"] = 1
    o = obs(citizen=citizen_with("FARMER"), inventory=inv, known_memories=PLACED,
            civilization={"population": 5, "food_reserve": 400,
                          "active_projects": 0, "known_storage": 1})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.DELIVER_RESOURCE
    assert d.tasks[0].type == TaskType.DELIVER


async def test_surplus_helps_on_active_projects():
    """A non-builder with a finished trade lends a hand on construction."""
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:oak_log"] = 40
    o = obs(citizen=citizen_with("LUMBERJACK"), inventory=inv, known_memories=PLACED,
            civilization={"population": 5, "food_reserve": 400,
                          "active_projects": 1})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.BUILD_PROJECT
    assert d.tasks[0].type == TaskType.BUILD


async def test_unassigned_falls_back_to_wood():
    p = MockProvider()
    o = obs(citizen=citizen_with("UNASSIGNED"), inventory=tool_set(),
            known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.GATHER
    assert d.tasks[0].resource == "minecraft:oak_log"
    assert d.tasks[0].quantity == 32


# --------------------------------------------------------------------- metal tier

def tooled(profession: str = "BUILDER", memories: list[str] | None = None,
           **extra_inventory) -> dict:
    """An observation for a citizen already past the wooden-tool bootstrap."""
    inventory = {
        "minecraft:oak_log": 16,
        "minecraft:oak_planks": 32,
        "minecraft:stick": 8,
        "minecraft:wooden_pickaxe": 1,
        "minecraft:wooden_axe": 1,
        "minecraft:wooden_shovel": 1,
        # Armed and plated: arming comes before trade work, so a fixture about
        # anything else has to be past that step already.
        "minecraft:wooden_sword": 1,
        "minecraft:leather_chestplate": 1,
    }
    inventory.update(extra_inventory)
    o = obs(inventory=inventory)
    o["citizen"] = dict(o["citizen"], profession=profession)
    o["known_memories"] = ["placed:minecraft:crafting_table"] if memories is None else memories
    return o


async def test_crafts_a_furnace_once_cobble_is_available():
    p = MockProvider()
    d = Decision.model_validate(
        (await p.generate_structured(payload(tooled(**{"minecraft:cobblestone": 8})))).data)
    assert d.goal.type == GoalType.CRAFT_ITEM
    assert d.goal.resource == "minecraft:furnace"
    assert d.tasks[0].type == TaskType.CRAFT


async def test_places_the_furnace_instead_of_crafting_a_second():
    p = MockProvider()
    o = tooled(**{"minecraft:cobblestone": 8, "minecraft:furnace": 1})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.PLACE
    assert d.tasks[0].block == "minecraft:furnace"


async def test_a_standing_furnace_is_not_rebuilt():
    p = MockProvider()
    o = tooled(memories=["placed:minecraft:crafting_table", "placed:minecraft:furnace"],
               **{"minecraft:cobblestone": 8})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource != "minecraft:furnace", "the furnace memory must stop a second one"


async def test_miner_goes_for_iron_as_a_single_goal():
    """The whole point of recursive craft planning: the policy names the tool,
    not the twelve steps needed to reach it."""
    p = MockProvider()
    o = tooled(profession="MINER",
               memories=["placed:minecraft:crafting_table", "placed:minecraft:furnace"],
               **{"minecraft:cobblestone": 32, "minecraft:stone_pickaxe": 1,
                  "minecraft:stone_axe": 1, "minecraft:stone_shovel": 1})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.CRAFT_ITEM
    assert d.goal.resource == "minecraft:iron_pickaxe"
    assert d.tasks[0].type == TaskType.CRAFT
    assert len(d.tasks) == 1, "one task: the mod resolves ore, coal and smelting itself"


async def test_miner_returns_to_its_trade_once_the_iron_pickaxe_exists():
    p = MockProvider()
    o = tooled(profession="MINER",
               memories=["placed:minecraft:crafting_table", "placed:minecraft:furnace"],
               **{"minecraft:cobblestone": 32, "minecraft:stone_pickaxe": 1,
                  "minecraft:stone_axe": 1, "minecraft:stone_shovel": 1,
                  "minecraft:iron_pickaxe": 1, "minecraft:torch": 16})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource != "minecraft:iron_pickaxe", "no loop once the tool is in hand"
    # "Its trade" now includes preparing the shared mine — torches for the
    # stair, chests for the depots — as well as gathering and delivering.
    assert d.tasks[0].type in (TaskType.GATHER, TaskType.DELIVER, TaskType.BUILD,
                               TaskType.CRAFT, TaskType.MINE_SHAFT)


# ------------------------------------------------------- settlement awareness

def stocked(profession: str, stock: dict, **inventory) -> dict:
    """An observation where the settlement's chests already hold something."""
    o = obs(inventory=inventory)
    o["citizen"] = dict(o["citizen"], profession=profession)
    o["civilization"] = {
        "population": 5, "food_reserve": 400, "active_projects": 0,
        "known_storage": 2, "stock": stock,
    }
    # Both workstations already stand, so these observations land on the
    # profession rules rather than the bootstrap ladder.
    o["known_memories"] = ["placed:minecraft:crafting_table", "placed:minecraft:furnace"]
    return o


async def test_a_stocked_warehouse_stops_the_lumberjack_felling_more():
    """Without this the colony ends up with ten thousand logs and no buildings."""
    p = MockProvider()
    o = stocked("LUMBERJACK", {"minecraft:oak_log": 600},
                **{"minecraft:oak_log": 16, "minecraft:oak_planks": 32,
                   "minecraft:stick": 8, "minecraft:wooden_pickaxe": 1,
                   "minecraft:wooden_axe": 1, "minecraft:wooden_shovel": 1,
                   "minecraft:cobblestone": 8})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource != "minecraft:oak_log" or d.tasks[0].type != TaskType.GATHER, \
        "the settlement is drowning in wood; chopping more is not work"


async def test_an_empty_warehouse_still_sends_the_lumberjack_out():
    p = MockProvider()
    o = stocked("LUMBERJACK", {},
                **{"minecraft:oak_log": 16, "minecraft:oak_planks": 32,
                   "minecraft:stick": 8, "minecraft:wooden_pickaxe": 1,
                   "minecraft:wooden_axe": 1, "minecraft:wooden_shovel": 1,
                   "minecraft:wooden_sword": 1, "minecraft:leather_chestplate": 1,
                   "minecraft:cobblestone": 8, "minecraft:torch": 16})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.GATHER
    assert d.goal.resource == "minecraft:oak_log"


async def test_a_stocked_warehouse_stops_the_miner_too():
    p = MockProvider()
    o = stocked("MINER", {"minecraft:cobblestone": 700},
                **{"minecraft:oak_log": 16, "minecraft:oak_planks": 32,
                   "minecraft:stick": 8, "minecraft:wooden_pickaxe": 1,
                   "minecraft:wooden_axe": 1, "minecraft:wooden_shovel": 1,
                   "minecraft:cobblestone": 32, "minecraft:stone_pickaxe": 1,
                   "minecraft:stone_axe": 1, "minecraft:stone_shovel": 1,
                   "minecraft:iron_pickaxe": 1})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert not (d.tasks[0].type == TaskType.GATHER
                and d.goal.resource == "minecraft:cobblestone"), \
        "700 cobblestone in the chests is enough"


async def test_a_newcomer_draws_on_the_warehouse_instead_of_felling_trees():
    """The craft planner turns this CRAFT goal into a withdrawal in-world."""
    p = MockProvider()
    o = stocked("UNASSIGNED", {"minecraft:oak_log": 64})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.CRAFT_ITEM
    assert d.tasks[0].type == TaskType.CRAFT
    assert "warehouse" in d.reasoning_summary or "warehouse" in (d.goal.description or "")


async def test_with_no_storage_the_newcomer_still_fells_trees():
    p = MockProvider()
    o = stocked("UNASSIGNED", {"minecraft:oak_log": 64})
    o["civilization"]["known_storage"] = 0
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.GATHER, "unregistered chests are not reachable stock"


async def test_a_farmer_without_a_hoe_makes_one_before_anything_else():
    """A farmer that cannot break ground can never start a field, and the colony
    then reports "no reachable wheat" forever — which is exactly what a play
    session showed. The hoe is what makes farming bootstrappable at all."""
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:wheat"] = 20          # could bake, but has nowhere to farm
    o = obs(citizen=citizen_with("FARMER"), inventory=inv, known_memories=PLACED)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.CRAFT_ITEM
    assert d.goal.resource == "minecraft:wooden_hoe"


async def test_only_farmers_bother_with_a_hoe():
    p = MockProvider()
    for trade in ("LUMBERJACK", "MINER", "BUILDER", "CRAFTER"):
        o = obs(citizen=citizen_with(trade), inventory=tool_set(), known_memories=PLACED)
        d = Decision.model_validate((await p.generate_structured(payload(o))).data)
        assert d.goal.resource != "minecraft:wooden_hoe", trade + " has no field to break"


# ------------------------------------------------------------------- livestock

async def test_a_shepherd_without_feed_works_the_field_first():
    """An empty-handed shepherd can neither lead an animal home nor breed one."""
    p = MockProvider()
    o = obs(citizen=citizen_with("SHEPHERD"), inventory=tool_set(), known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.HARVEST_FOOD
    assert d.tasks[0].type == TaskType.HARVEST


async def test_a_fed_shepherd_herds_then_breeds():
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:wheat"] = 32
    o = obs(citizen=citizen_with("SHEPHERD"), inventory=inv, known_memories=PLACED)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert [t.type for t in d.tasks] == [TaskType.HERD, TaskType.BREED], \
        "fetch what is missing, then breed what is already penned"


async def test_a_shepherd_with_wool_takes_it_to_the_warehouse():
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:wheat"] = 32
    inv["minecraft:white_wool"] = 16
    o = obs(citizen=citizen_with("SHEPHERD"), inventory=inv, known_memories=PLACED,
            civilization={"population": 6, "food_reserve": 400,
                          "active_projects": 0, "known_storage": 1})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.DELIVER, "wool is beds, and beds cap the population"


async def test_only_shepherds_tend_livestock():
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:wheat"] = 32
    for trade in ("LUMBERJACK", "MINER", "BUILDER"):
        o = obs(citizen=citizen_with(trade), inventory=inv, known_memories=PLACED)
        d = Decision.model_validate((await p.generate_structured(payload(o))).data)
        assert d.tasks[0].type not in (TaskType.HERD, TaskType.BREED), trade


# ---------------------------------------------------------- lighting the colony

async def test_a_citizen_with_coal_makes_torches():
    """Unlit streets spawn the monsters that kill citizens: this is survival."""
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:coal"] = 8
    inv["minecraft:oak_log"] = 64          # trade work is caught up
    o = obs(citizen=citizen_with("CRAFTER"), inventory=inv, known_memories=PLACED,
            civilization={"population": 6, "food_reserve": 400,
                          "active_projects": 0, "known_storage": 1,
                          "stock": {"minecraft:oak_log": 999}})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource == "minecraft:torch"
    assert d.tasks[0].type == TaskType.CRAFT


async def test_a_citizen_carrying_torches_lights_the_district():
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:torch"] = 32
    inv["minecraft:oak_log"] = 64
    o = obs(citizen=citizen_with("CRAFTER"), inventory=inv, known_memories=PLACED,
            civilization={"population": 6, "food_reserve": 400,
                          "active_projects": 0, "known_storage": 1,
                          "stock": {"minecraft:oak_log": 999}})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.DECORATE


async def test_decoration_never_outranks_the_tool_bootstrap():
    """A citizen with torches but no pickaxe has more pressing business."""
    p = MockProvider()
    o = obs(inventory={"minecraft:torch": 64, "minecraft:oak_log": 32})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type != TaskType.DECORATE


# ----------------------------------------------------------------- storage

async def test_a_colony_with_no_container_makes_one():
    """Without a chest the shared stock is permanently empty: deliveries do
    nothing, the food reserve reads zero forever, and nobody can withdraw."""
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:oak_planks"] = 32
    o = obs(citizen=citizen_with("BUILDER"), inventory=inv, known_memories=PLACED,
            civilization={"population": 4, "food_reserve": 400,
                          "active_projects": 0, "known_storage": 0})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource == "minecraft:chest"
    assert d.tasks[0].type == TaskType.CRAFT


async def test_a_carried_chest_is_put_down_not_duplicated():
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:oak_planks"] = 32
    inv["minecraft:chest"] = 1
    o = obs(citizen=citizen_with("BUILDER"), inventory=inv, known_memories=PLACED,
            civilization={"population": 4, "food_reserve": 400,
                          "active_projects": 0, "known_storage": 0})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.PLACE
    assert d.tasks[0].block == "minecraft:chest"


async def test_an_existing_container_stops_the_chest_rule():
    p = MockProvider()
    inv = tool_set()
    inv["minecraft:oak_planks"] = 32
    o = obs(citizen=citizen_with("BUILDER"), inventory=inv, known_memories=PLACED,
            civilization={"population": 4, "food_reserve": 400,
                          "active_projects": 0, "known_storage": 2})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource != "minecraft:chest"


async def test_a_hungry_citizen_with_no_food_goes_and_gets_some():
    """Hunger arrives on a 0..100 scale. Reading it as 0..20 made every hunger
    rule fire only at death's door, and a starving citizen was separately
    forbidden from working at all — a death spiral."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 32})
    o["citizen"] = dict(o["citizen"], hunger=30)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.HARVEST_FOOD


async def test_a_well_fed_citizen_does_not_drop_everything_to_farm():
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 32})
    o["citizen"] = dict(o["citizen"], hunger=90)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type != GoalType.HARVEST_FOOD


# ------------------------------------------------------------------- hunting

async def test_a_hungry_citizen_works_the_field_while_the_colony_has_food():
    """Hunting is a last resort: a led animal breeds and feeds the colony for
    good, while one killed in a field feeds it once. A hoe is part of "can
    farm" — without one there is no field to work."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 32, "minecraft:wooden_hoe": 1},
            civilization={"population": 5, "food_reserve": 200, "active_projects": 0,
                          "known_storage": 1})
    o["citizen"] = dict(o["citizen"], hunger=20)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.HARVEST


async def test_a_real_famine_sends_a_citizen_hunting():
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 32},
            civilization={"population": 5, "food_reserve": 0, "active_projects": 0,
                          "known_storage": 1})
    o["citizen"] = dict(o["citizen"], hunger=20)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.HUNT
    assert d.goal.type == GoalType.HARVEST_FOOD


async def test_a_well_fed_citizen_never_hunts():
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 32},
            civilization={"population": 5, "food_reserve": 0, "active_projects": 0,
                          "known_storage": 1})
    o["citizen"] = dict(o["citizen"], hunger=95)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type != TaskType.HUNT, "an empty larder is not a reason to kill the herd"


async def test_a_starving_citizen_with_no_hoe_hunts_rather_than_failing_to_farm():
    """Sending a citizen that cannot break ground to go farming produced 130
    identical "no hoe" failures while it slowly starved."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 32},
            civilization={"population": 5, "food_reserve": 400, "active_projects": 0,
                          "known_storage": 1})
    o["citizen"] = dict(o["citizen"], hunger=20)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.HUNT


# -------------------------------------------------------------- self-defence

async def test_an_unarmed_citizen_makes_a_sword_before_working():
    """The combat rules make unarmed citizens retreat rather than trade a life
    for nothing, so an unarmed colony simply gets chased around its own
    fields. Two planks and a stick ends that."""
    p = MockProvider()
    inv = tool_set()
    del inv["minecraft:wooden_sword"]
    o = obs(citizen=citizen_with("LUMBERJACK"), inventory=inv, known_memories=PLACED)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource == "minecraft:wooden_sword"
    assert d.tasks[0].type == TaskType.CRAFT


async def test_stone_is_preferred_when_the_citizen_has_cobble():
    p = MockProvider()
    inv = tool_set()
    del inv["minecraft:wooden_sword"]
    inv["minecraft:cobblestone"] = 16
    o = obs(citizen=citizen_with("MINER"), inventory=inv, known_memories=PLACED)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource == "minecraft:stone_sword"


async def test_an_armed_citizen_moves_on_to_armour_when_it_can_afford_it():
    p = MockProvider()
    inv = tool_set()
    del inv["minecraft:leather_chestplate"]
    inv["minecraft:leather"] = 12
    o = obs(citizen=citizen_with("SHEPHERD"), inventory=inv, known_memories=PLACED)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource == "minecraft:leather_chestplate"


async def test_iron_plate_beats_leather_when_both_are_available():
    p = MockProvider()
    inv = tool_set()
    del inv["minecraft:leather_chestplate"]
    inv["minecraft:leather"] = 12
    inv["minecraft:iron_ingot"] = 12
    o = obs(citizen=citizen_with("MINER"), inventory=inv, known_memories=PLACED)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource == "minecraft:iron_chestplate"


async def test_an_already_equipped_citizen_gets_on_with_its_trade():
    p = MockProvider()
    o = obs(citizen=citizen_with("LUMBERJACK"), inventory=tool_set(), known_memories=PLACED)

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource not in ("minecraft:wooden_sword", "minecraft:leather_chestplate")


# ----------------------------------------------------------------- the mine

def _miner(**extra) -> dict:
    inv = tool_set()
    inv["minecraft:cobblestone"] = 64
    inv["minecraft:stone_pickaxe"] = 1
    inv["minecraft:stone_axe"] = 1
    inv["minecraft:stone_shovel"] = 1
    inv["minecraft:iron_pickaxe"] = 1
    inv.update(extra)
    o = obs(citizen=citizen_with("MINER"), inventory=inv,
            known_memories=["placed:minecraft:crafting_table", "placed:minecraft:furnace"])
    return o


async def test_an_equipped_miner_works_the_shared_mine():
    """One stair dug by everybody beats each miner scratching a hole: alone,
    nobody digs deep enough to reach iron, let alone diamond."""
    p = MockProvider()
    o = _miner(**{"minecraft:torch": 32, "minecraft:chest": 2})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.MINE_SHAFT


async def test_a_miner_lights_the_stair_before_cutting_it():
    p = MockProvider()
    o = _miner(**{"minecraft:coal": 8, "minecraft:chest": 2})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource == "minecraft:torch", "a dark mine is a mob corridor"


async def test_a_miner_makes_depots_before_cutting_the_stair():
    p = MockProvider()
    o = _miner(**{"minecraft:torch": 32, "minecraft:oak_planks": 64})

    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.resource == "minecraft:chest"


async def test_only_miners_cut_the_mine():
    p = MockProvider()
    for trade in ("LUMBERJACK", "FARMER", "SHEPHERD"):
        inv = tool_set()
        inv["minecraft:torch"] = 32
        inv["minecraft:chest"] = 2
        o = obs(citizen=citizen_with(trade), inventory=inv, known_memories=PLACED)
        d = Decision.model_validate((await p.generate_structured(payload(o))).data)
        assert d.tasks[0].type != TaskType.MINE_SHAFT, trade


async def test_a_prefetch_asks_what_comes_next_not_what_is_being_done():
    """Citizens line up their next job while finishing the current one. Asking
    with the ordinary periodic reason would return the goal they are already
    completing, and they would simply repeat it."""
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 16},
            current_goal="INCREASE_RESOURCE|minecraft:oak_log")

    ahead = Decision.model_validate(
        (await p.generate_structured(payload(o, reason="prefetch"))).data)
    assert ahead.goal.type != GoalType.INCREASE_RESOURCE or \
        ahead.goal.resource != "minecraft:oak_log", "queued the job it is already doing"


async def test_a_periodic_check_still_continues_the_current_goal():
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 16},
            current_goal="INCREASE_RESOURCE|minecraft:oak_log")

    d = Decision.model_validate(
        (await p.generate_structured(payload(o, reason="periodic"))).data)
    assert d.goal.type == GoalType.INCREASE_RESOURCE, "mid-job churn is its own problem"
