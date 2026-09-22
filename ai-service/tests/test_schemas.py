"""Schema strictness: the LLM's only allowed output shapes."""

import pytest
from pydantic import ValidationError

from minecivilization_ai.schemas import (
    Decision,
    Goal,
    GoalType,
    Observation,
    Task,
    TaskType,
)


def make_decision(**overrides) -> Decision:
    base = {
        "reasoning_summary": "wood is needed",
        "goal": {"type": "INCREASE_RESOURCE", "resource": "minecraft:oak_log",
                 "target_quantity": 16},
        "tasks": [{"type": "GATHER", "resource": "minecraft:oak_log", "quantity": 16}],
    }
    base.update(overrides)
    return Decision.model_validate(base)


def test_valid_decision():
    d = make_decision()
    assert d.goal.type == GoalType.INCREASE_RESOURCE
    assert d.tasks[0].type == TaskType.GATHER


def test_unknown_field_rejected():
    with pytest.raises(ValidationError):
        Decision.model_validate({
            "reasoning_summary": "x",
            "goal": {"type": "IDLE"},
            "tasks": [],
            "shell_command": "rm -rf /",  # never allowed
        })


def test_unknown_goal_type_rejected():
    with pytest.raises(ValidationError):
        Goal.model_validate({"type": "SPAWN_DIAMONDS"})


def test_unknown_task_type_rejected():
    with pytest.raises(ValidationError):
        Task.model_validate({"type": "TELEPORT"})


def test_resource_must_be_item_id():
    with pytest.raises(ValidationError):
        Goal.model_validate({"type": "GATHER_RESOURCE", "resource": "oak log"})
    with pytest.raises(ValidationError):
        Task.model_validate({"type": "GATHER", "resource": "minecraft:; rm"})


def test_quantity_bounds():
    with pytest.raises(ValidationError):
        Task.model_validate({"type": "GATHER", "quantity": 0})
    with pytest.raises(ValidationError):
        Task.model_validate({"type": "GATHER", "quantity": -5})
    with pytest.raises(ValidationError):
        Task.model_validate({"type": "GATHER", "quantity": 10_000_000})


def test_empty_reasoning_rejected():
    with pytest.raises(ValidationError):
        make_decision(reasoning_summary="")


def test_observation_rejects_negative_inventory():
    with pytest.raises(ValidationError):
        Observation.model_validate({
            "citizen": {"name": "Alex"},
            "inventory": {"minecraft:oak_log": -3},
        })


def test_observation_rejects_junk_item_ids():
    with pytest.raises(ValidationError):
        Observation.model_validate({
            "citizen": {"name": "Alex"},
            "inventory": {"evil": 3},
        })


def test_observation_forbids_extra_fields():
    with pytest.raises(ValidationError):
        Observation.model_validate({
            "citizen": {"name": "Alex"},
            "all_ore_positions": [[1, 2, 3]],  # omniscience leak — rejected
        })
