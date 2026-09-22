"""Deterministic mock provider.

Lets every test, integration scenario and Forge dev-session run without Ollama.
Implements a simple, transparent decision policy that mirrors what a minimal
citizen brain should do — no randomness, no network.

Policy v2 — a role-aware bootstrap ladder, in priority order:

 1. loop breaker: repeated task failures -> REST (never a livelock)
 2. continue the current goal instead of churning
 3. wood first: fewer than 8 logs -> gather 16 oak logs
 4. food security: civilization food reserve below 128 -> harvest wheat
 5. stock 16 oak planks from the logs already carried
 6. bootstrap: craft a crafting table, then PLACE it on the ground
    (remembered as the ``placed:minecraft:crafting_table`` memory key)
 7. bootstrap: sticks -> wooden pickaxe -> wooden axe -> wooden shovel
 8. miners: cobblestone (mined from stone) -> stone pickaxe/axe/shovel
 9. organized roles: builders and crafters advance construction first,
    then each profession does its trade — lumberjacks chop, miners mine,
    farmers bake and share bread, crafters convert surplus into planks —
    and everyone lends a hand on active projects from their surplus
10. REST when nothing needs doing

Every step is gated on what the inventory can actually pay for, so a task can
finish: no free resources, no instant-complete decision loops.
"""

from __future__ import annotations

import json
import time
from typing import Any

from ..schemas import Decision, Goal, GoalType, Task, TaskType
from .base import LLMRawResult, LLMRequest

# Absolute CRAFT targets keyed by item id. Single source of truth: the ladder
# below and the goal-continuation branch must agree on the quantity, and every
# gate must ensure the carried materials can actually pay for the whole craft.
CRAFT_TARGETS: dict[str, int] = {
    "minecraft:oak_planks": 16,
    "minecraft:stick": 4,
    "minecraft:crafting_table": 1,
    "minecraft:wooden_pickaxe": 1,
    "minecraft:wooden_axe": 1,
    "minecraft:wooden_shovel": 1,
    "minecraft:stone_pickaxe": 1,
    "minecraft:stone_axe": 1,
    "minecraft:stone_shovel": 1,
    "minecraft:bread": 6,
}

TABLE_PLACED_KEY = "placed:minecraft:crafting_table"


def _inventory_total(inv: dict[str, int], suffix: str) -> int:
    return sum(n for k, n in inv.items() if k.endswith(suffix))


def _craft_quantity(item: str) -> int:
    return CRAFT_TARGETS.get(item, 1)


def _dump(decision: Decision) -> dict[str, Any]:
    return decision.model_dump(mode="json")


def _gather(name: str, item: str, quantity: int, why: str,
            block: str | None = None) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} gathers {quantity} {item} — {why}",
        goal=Goal(type=GoalType.INCREASE_RESOURCE, resource=item,
                  target_quantity=quantity, description=why),
        tasks=[Task(type=TaskType.GATHER, resource=item, quantity=quantity,
                    block=block)],
    ))


def _craft(name: str, item: str, quantity: int, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} crafts {quantity} {item} — {why}",
        goal=Goal(type=GoalType.CRAFT_ITEM, resource=item,
                  target_quantity=quantity, description=why),
        tasks=[Task(type=TaskType.CRAFT, resource=item, quantity=quantity)],
    ))


def _place_table(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} sets its crafting table up on the ground — {why}",
        goal=Goal(type=GoalType.CRAFT_ITEM, resource="minecraft:crafting_table",
                  target_quantity=1, description="workstation in use"),
        tasks=[Task(type=TaskType.PLACE, block="minecraft:crafting_table")],
    ))


def _deliver(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} carries the surplus to shared storage — {why}",
        goal=Goal(type=GoalType.DELIVER_RESOURCE, description=why),
        tasks=[Task(type=TaskType.DELIVER)],
    ))


def _build(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} advances the active construction project — {why}",
        goal=Goal(type=GoalType.BUILD_PROJECT, description=why),
        tasks=[Task(type=TaskType.BUILD)],
    ))


def _harvest(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} works the fields — {why}",
        goal=Goal(type=GoalType.HARVEST_FOOD, description=why),
        tasks=[Task(type=TaskType.HARVEST, resource="minecraft:wheat")],
    ))


def _rest(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} stands down — {why}",
        goal=Goal(type=GoalType.IDLE, description=why),
        tasks=[Task(type=TaskType.REST)],
    ))


def _mock_decision(payload: dict[str, Any]) -> dict[str, Any]:
    obs = payload.get("observation", {})
    citizen = obs.get("citizen", {})
    name = citizen.get("name", "citizen")
    profession = str(citizen.get("profession") or "UNASSIGNED").upper()
    inv: dict[str, int] = obs.get("inventory", {}) or {}
    civ = obs.get("civilization", {}) or {}
    current_goal = obs.get("current_goal")
    task_failures = int(obs.get("task_failures", 0) or 0)
    reason = str(payload.get("reason", ""))
    memories = set(obs.get("known_memories") or [])

    logs = _inventory_total(inv, "_log")
    planks = _inventory_total(inv, "_planks")
    sticks = inv.get("minecraft:stick", 0)
    wheat = inv.get("minecraft:wheat", 0)
    bread = inv.get("minecraft:bread", 0)
    table = inv.get("minecraft:crafting_table", 0)
    cobble = inv.get("minecraft:cobblestone", 0)
    has_wooden_pick = inv.get("minecraft:wooden_pickaxe", 0) > 0
    has_wooden_axe = inv.get("minecraft:wooden_axe", 0) > 0
    has_wooden_shovel = inv.get("minecraft:wooden_shovel", 0) > 0
    table_placed = TABLE_PLACED_KEY in memories
    storage = int(civ.get("known_storage", 0) or 0)
    food_reserve = int(civ.get("food_reserve", 0) or 0)
    active = int(civ.get("active_projects", 0) or 0)

    # 1. Loop breaker: repeated recent failures -> disengage safely. Fires even
    # when current_goal is None (the brain clears the goal right before asking
    # for a new decision after 3 task failures — that was the stuck-in-trees
    # livelock: without this the mock kept re-issuing the same GATHER).
    if task_failures >= 3:
        return _dump(Decision(
            reasoning_summary=(
                f"{name} repeatedly failed recent tasks; standing down to avoid "
                "burning resources. A new decision will be requested later."
            ),
            goal=Goal(type=GoalType.IDLE, description="recovering from repeated task failures"),
            tasks=[Task(type=TaskType.REST)],
        ))

    # 2. Continuing existing useful work beats constant goal churn.
    if current_goal and reason not in ("goal_completed", "no_goal"):
        goal_type = str(current_goal).split(":", 1)[0]
        if goal_type == "INCREASE_RESOURCE" or goal_type == "GATHER_RESOURCE":
            res = str(current_goal).split("|")[-1] if "|" in str(current_goal) else "minecraft:oak_log"
            # cobblestone is mined from stone blocks: name both sides explicitly
            block = "minecraft:stone" if res == "minecraft:cobblestone" else None
            return _dump(Decision(
                reasoning_summary=f"{name} continues the ongoing goal without changing plans.",
                goal=Goal(type=GoalType.INCREASE_RESOURCE, resource=res, target_quantity=16),
                tasks=[Task(type=TaskType.GATHER, resource=res, quantity=16, block=block)],
            ))
        if goal_type == "BUILD_PROJECT":
            pid = str(current_goal).split("|")[-1] if "|" in str(current_goal) else None
            return _dump(Decision(
                reasoning_summary=f"{name} keeps working on the current construction project.",
                goal=Goal(type=GoalType.BUILD_PROJECT, project_id=pid),
                tasks=[Task(type=TaskType.BUILD, project_id=pid)],
            ))
        if goal_type == "HARVEST_FOOD":
            return _dump(Decision(
                reasoning_summary=f"{name} continues producing food.",
                goal=Goal(type=GoalType.HARVEST_FOOD),
                tasks=[Task(type=TaskType.HARVEST, resource="minecraft:wheat")],
            ))
        if goal_type == "CRAFT_ITEM":
            res = str(current_goal).split("|")[-1] if "|" in str(current_goal) \
                else "minecraft:oak_planks"
            if res == "minecraft:crafting_table" and table > 0:
                # crafted last decision but not down yet: place it, don't craft a second
                return _place_table(name, "the table is crafted and still in the inventory")
            qty = _craft_quantity(res)
            return _craft(name, res, qty, "keeping the ongoing craft goal on track")

    # 3. Wood first: nothing builds without it.
    if logs < 8:
        return _gather(name, "minecraft:oak_log", 16,
                       "wood is the first prerequisite for tools, storage and construction")

    # 4. Food security outranks every luxury.
    if food_reserve < 128:
        return _harvest(name, "food reserves are below the safe threshold")

    # 5. Logs are useless as-is: carry 16 planks (logs >= 8 is guaranteed here).
    if logs >= 8 and planks < 16:
        return _craft(name, "minecraft:oak_planks", 16,
                      "planks are the working form of wood")

    # 6. Bootstrap: every citizen owns a workstation. Craft the table, then
    #    PLACE it — the placement is remembered in known_memories so it is
    #    never crafted twice.
    if not has_wooden_pick:
        if table > 0:
            return _place_table(name, "3x3 recipes need a table on the ground")
        if not table_placed and planks >= 4:
            return _craft(name, "minecraft:crafting_table", 1,
                          "the workstation unlocks every tool recipe")

    # 7. Bootstrap: sticks, then the wooden tool set (all need the placed table).
    if not (has_wooden_pick and has_wooden_axe and has_wooden_shovel):
        if sticks < 2 and planks >= 5:
            return _craft(name, "minecraft:stick", 4, "tool handles")
        if not has_wooden_pick and planks >= 3 and sticks >= 2:
            return _craft(name, "minecraft:wooden_pickaxe", 1, "the pickaxe opens mining")
        if not has_wooden_axe and planks >= 3 and sticks >= 2:
            return _craft(name, "minecraft:wooden_axe", 1, "an axe speeds up woodcutting")
        if not has_wooden_shovel and planks >= 1 and sticks >= 2:
            return _craft(name, "minecraft:wooden_shovel", 1, "a shovel clears soil fast")

    # 8. Miners upgrade to stone gear before their trade proper.
    if profession == "MINER" and has_wooden_pick:
        if cobble < 16:
            return _gather(name, "minecraft:cobblestone", 16,
                           "stone is the next material tier", block="minecraft:stone")
        if sticks < 2 and planks >= 5:
            return _craft(name, "minecraft:stick", 4, "stone tool handles")
        if inv.get("minecraft:stone_pickaxe", 0) == 0 and cobble >= 3 and sticks >= 2:
            return _craft(name, "minecraft:stone_pickaxe", 1, "stone mines faster than wood")
        if inv.get("minecraft:stone_axe", 0) == 0 and cobble >= 3 and sticks >= 2:
            return _craft(name, "minecraft:stone_axe", 1, "a stone axe for the lumber trade")
        if inv.get("minecraft:stone_shovel", 0) == 0 and cobble >= 1 and sticks >= 2:
            return _craft(name, "minecraft:stone_shovel", 1, "a stone shovel for digging")

    # 9. Organized roles. Builders and crafters serve construction first; the
    #    other trades keep the raw material lines flowing.
    if active > 0 and profession in ("BUILDER", "CRAFTER", "UNASSIGNED", "LOGISTICS"):
        return _build(name, "an active construction project needs hands")

    if profession == "LUMBERJACK":
        if logs < 32:
            return _gather(name, "minecraft:oak_log", 32,
                           "the settlement needs a steady wood supply")
        if storage > 0:
            return _deliver(name, "deposit logs and planks for the builders")
        if active > 0:
            return _build(name, "wood supply is caught up, help raise the buildings")
        if planks < 192:
            return _craft(name, "minecraft:oak_planks", planks + 64,
                          "no chest yet: turn carried logs into building material")
    elif profession == "MINER":
        if cobble < 32:
            return _gather(name, "minecraft:cobblestone", 32,
                           "stone feeds the tools and the buildings",
                           block="minecraft:stone")
        if storage > 0:
            return _deliver(name, "deposit cobblestone for the builders")
        if active > 0:
            return _build(name, "stone supply is caught up, help raise the buildings")
        if cobble < 192:
            return _gather(name, "minecraft:cobblestone", cobble + 16,
                           "stockpiling stone locally until a chest exists",
                           block="minecraft:stone")
    elif profession == "FARMER":
        if bread < 6 and wheat >= (6 - bread) * 3:
            return _craft(name, "minecraft:bread", 6, "wheat becomes bread for everyone")
        if storage > 0 and bread >= 6:
            return _deliver(name, "share the baked bread with the settlement")
        if bread < 6:
            return _harvest(name, "keep the fields producing food")
        if active > 0:
            return _build(name, "everyone is fed, help raise the buildings")
    elif profession == "BUILDER":
        if logs < 32:
            return _gather(name, "minecraft:oak_log", 32,
                           "collect building material for the next house")
        if storage > 0:
            return _deliver(name, "stock the chest with construction material")
        if planks < 192:
            return _craft(name, "minecraft:oak_planks", planks + 64,
                          "keep converting logs into buildable planks")
    elif profession == "CRAFTER":
        if bread < 3 and wheat >= (3 - bread) * 3:
            return _craft(name, "minecraft:bread", 3, "turn spare wheat into food")
        if storage > 0 and planks >= 16:
            return _deliver(name, "move crafted planks to shared storage")
        if logs < 32:
            return _gather(name, "minecraft:oak_log", 32, "raw material for the workshop")
        if planks < 192:
            return _craft(name, "minecraft:oak_planks", planks + 64,
                          "keep the plank supply flowing")
    else:  # LOGISTICS / UNASSIGNED / unknown — a self-sufficient villager
        if storage > 0 and logs >= 32:
            return _deliver(name, "drop off surplus at storage")
        if logs < 32:
            return _gather(name, "minecraft:oak_log", 32, "wood remains the base resource")
        if planks < 192:
            return _craft(name, "minecraft:oak_planks", planks + 64,
                          "convert carried logs into usable planks")

    # Trade surplus is handled: from here, pitch in on construction or rest.
    if active > 0:
        return _build(name, "the trade is caught up, lend a hand building")

    return _rest(name, "no pressing need detected")


class MockProvider:
    name = "mock"
    model = "mock-policy-v2"

    async def generate_structured(self, request: LLMRequest) -> LLMRawResult:
        start = time.perf_counter()
        if request.schema_name == "decision":
            data = _mock_decision(request.payload)
        elif request.schema_name == "civilization_plan":
            civ = request.payload.get("civilization", {})
            data = {
                "reasoning_summary": "Civilization plan (mock): keep food and wood reserves positive, "
                                     "complete active projects before starting new ones.",
                "priorities": ["food_security", "wood_supply", "active_projects"],
                "bottlenecks": civ.get("bottlenecks", []),
                "proposed_projects": [],
            }
        else:
            data = {"reasoning_summary": "mock", "unknown_schema": request.schema_name}
        latency = (time.perf_counter() - start) * 1000.0
        return LLMRawResult(
            data=data,
            provider=self.name,
            model=self.model,
            latency_ms=latency,
            raw_text=json.dumps(data),
        )

    async def health(self) -> bool:
        return True

    async def list_models(self) -> list[str]:
        return [self.model]
