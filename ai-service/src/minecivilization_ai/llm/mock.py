"""Deterministic mock provider.

Lets every test, integration scenario and Forge dev-session run without Ollama.
Implements a simple, transparent decision policy that mirrors what a minimal
citizen brain should do — no randomness, no network.
"""

from __future__ import annotations

import json
import time
from typing import Any

from ..schemas import Decision, Goal, GoalType, Task, TaskType
from .base import LLMRawResult, LLMRequest


def _inventory_total(inv: dict[str, int], suffix: str) -> int:
    return sum(n for k, n in inv.items() if k.endswith(suffix))


def _mock_decision(payload: dict[str, Any]) -> dict[str, Any]:
    obs = payload.get("observation", {})
    citizen = obs.get("citizen", {})
    name = citizen.get("name", "citizen")
    inv: dict[str, int] = obs.get("inventory", {}) or {}
    civ = obs.get("civilization", {}) or {}
    current_goal = obs.get("current_goal")
    task_failures = int(obs.get("task_failures", 0) or 0)
    reason = str(payload.get("reason", ""))

    # Loop breaker: repeated recent failures -> disengage safely. Fires even
    # when current_goal is None (the brain clears the goal right before asking
    # for a new decision after 3 task failures — that was the stuck-in-trees
    # livelock: without this the mock kept re-issuing the same GATHER).
    if task_failures >= 3:
        return Decision(
            reasoning_summary=(
                f"{name} repeatedly failed recent tasks; standing down to avoid "
                "burning resources. A new decision will be requested later."
            ),
            goal=Goal(type=GoalType.IDLE, description="recovering from repeated task failures"),
            tasks=[Task(type=TaskType.REST)],
        ).model_dump(mode="json")

    # Continuing existing useful work beats constant goal churn.
    if current_goal and reason not in ("goal_completed", "no_goal"):
        goal_type = str(current_goal).split(":", 1)[0]
        if goal_type == "INCREASE_RESOURCE" or goal_type == "GATHER_RESOURCE":
            res = str(current_goal).split("|")[-1] if "|" in str(current_goal) else "minecraft:oak_log"
            return Decision(
                reasoning_summary=f"{name} continues the ongoing goal without changing plans.",
                goal=Goal(type=GoalType.INCREASE_RESOURCE, resource=res, target_quantity=16),
                tasks=[Task(type=TaskType.GATHER, resource=res, quantity=16)],
            ).model_dump(mode="json")
        if goal_type == "BUILD_PROJECT":
            pid = str(current_goal).split("|")[-1] if "|" in str(current_goal) else None
            return Decision(
                reasoning_summary=f"{name} keeps working on the current construction project.",
                goal=Goal(type=GoalType.BUILD_PROJECT, project_id=pid),
                tasks=[Task(type=TaskType.BUILD, project_id=pid)],
            ).model_dump(mode="json")
        if goal_type == "HARVEST_FOOD":
            return Decision(
                reasoning_summary=f"{name} continues producing food.",
                goal=Goal(type=GoalType.HARVEST_FOOD),
                tasks=[Task(type=TaskType.HARVEST, resource="minecraft:wheat")],
            ).model_dump(mode="json")
        if goal_type == "CRAFT_ITEM":
            res = str(current_goal).split("|")[-1] if "|" in str(current_goal) \
                else "minecraft:oak_planks"
            return Decision(
                reasoning_summary=f"{name} keeps crafting {res}.",
                goal=Goal(type=GoalType.CRAFT_ITEM, resource=res, target_quantity=16),
                tasks=[Task(type=TaskType.CRAFT, resource=res, quantity=16)],
            ).model_dump(mode="json")

    # New goal selection, in priority order. Wood first: nothing builds without it.
    logs = _inventory_total(inv, "_log")
    if logs < 8:
        return Decision(
            reasoning_summary=(
                "The settlement has almost no wood in inventory; wood is the first "
                "prerequisite for tools, storage and construction. Gathering 16 oak logs."
            ),
            goal=Goal(type=GoalType.INCREASE_RESOURCE, resource="minecraft:oak_log",
                      target_quantity=16, description="stockpile oak logs"),
            tasks=[Task(type=TaskType.GATHER, resource="minecraft:oak_log", quantity=16)],
        ).model_dump(mode="json")

    if int(civ.get("food_reserve", 0) or 0) < 128:
        return Decision(
            reasoning_summary="Food reserves are below the safe threshold; expanding wheat production.",
            goal=Goal(type=GoalType.HARVEST_FOOD, description="raise food reserve above 128"),
            tasks=[Task(type=TaskType.HARVEST, resource="minecraft:wheat")],
        ).model_dump(mode="json")

    active = int(civ.get("active_projects", 0) or 0)
    if active > 0:
        return Decision(
            reasoning_summary="An active construction project needs labor; contributing to it.",
            goal=Goal(type=GoalType.BUILD_PROJECT, description="advance active project"),
            tasks=[Task(type=TaskType.BUILD)],
        ).model_dump(mode="json")

    # Wood is stockpiled but unusable as-is: convert a few logs into planks.
    # Purely local, consumes only what the citizen physically carries.
    planks = _inventory_total(inv, "_planks")
    if logs >= 8 and planks < 16:
        return Decision(
            reasoning_summary=(
                "Logs are useless for building until they are planks; crafting 16 oak "
                "planks from the logs already in the inventory."
            ),
            goal=Goal(type=GoalType.CRAFT_ITEM, resource="minecraft:oak_planks",
                      target_quantity=16, description="prepare building material"),
            tasks=[Task(type=TaskType.CRAFT, resource="minecraft:oak_planks",
                        quantity=16)],
        ).model_dump(mode="json")

    return Decision(
        reasoning_summary="No pressing need detected; resting while awaiting new work.",
        goal=Goal(type=GoalType.IDLE, description="nothing urgent"),
        tasks=[Task(type=TaskType.REST)],
    ).model_dump(mode="json")


class MockProvider:
    name = "mock"
    model = "mock-policy-v1"

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
