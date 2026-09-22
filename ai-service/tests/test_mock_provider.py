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
