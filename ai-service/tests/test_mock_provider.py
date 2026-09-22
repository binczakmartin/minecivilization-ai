"""MockProvider policy: deterministic, safe, no free resources."""

from minecivilization_ai.llm.base import LLMRequest
from minecivilization_ai.llm.mock import MockProvider
from minecivilization_ai.schemas import Decision, GoalType, TaskType


def payload(obs: dict, reason: str = "no_goal") -> LLMRequest:
    return LLMRequest(system="s", payload={"observation": obs, "reason": reason},
                      schema_name="decision")


def obs(**kwargs) -> dict:
    base = {
        "citizen": {"name": "Alex", "profession": "BUILDER", "health": 20, "hunger": 18},
        "inventory": {},
        "civilization": {"population": 5, "food_reserve": 400, "active_projects": 0},
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


async def test_low_food_triggers_harvest():
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 32},
            civilization={"population": 5, "food_reserve": 10, "active_projects": 0})
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.HARVEST_FOOD
    assert d.tasks[0].type == TaskType.HARVEST


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
    """A citizen who finished the wood bootstrap: tools crafted, table placed."""
    return {
        "minecraft:oak_log": 16,
        "minecraft:oak_planks": 16,
        "minecraft:stick": 4,
        "minecraft:wooden_pickaxe": 1,
        "minecraft:wooden_axe": 1,
        "minecraft:wooden_shovel": 1,
    }


PLACED = ["placed:minecraft:crafting_table"]


def citizen_with(profession: str) -> dict:
    return {"name": "Sam", "profession": profession, "health": 20, "hunger": 18}


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
    o = obs(inventory={"minecraft:oak_log": 16, "minecraft:oak_planks": 16},
            known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].resource == "minecraft:stick"
    assert d.tasks[0].quantity == 4


async def test_crafts_wooden_pickaxe_once_sticks_are_ready():
    p = MockProvider()
    o = obs(inventory={"minecraft:oak_log": 16, "minecraft:oak_planks": 16,
                       "minecraft:stick": 4},
            known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(o))).data)
    assert d.goal.type == GoalType.CRAFT_ITEM
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].resource == "minecraft:wooden_pickaxe"
    assert d.tasks[0].quantity == 1


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
    fm = obs(citizen=citizen_with("FARMER"), inventory=inv, known_memories=PLACED)
    d = Decision.model_validate((await p.generate_structured(payload(fm))).data)
    assert d.tasks[0].type == TaskType.CRAFT
    assert d.tasks[0].resource == "minecraft:bread"
    assert d.tasks[0].quantity == 6

    bd = obs(citizen=citizen_with("BUILDER"), inventory=tool_set(),
             known_memories=PLACED,
             civilization={"population": 5, "food_reserve": 400,
                           "active_projects": 1})
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
