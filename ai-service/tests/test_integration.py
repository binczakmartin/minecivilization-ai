"""Vertical integration scenario (Milestone: works headlessly with MockProvider).

spawn citizen
  -> register with AI service
  -> AI/mock returns structured task
  -> "Minecraft" accepts task
  -> deterministic skill executes
  -> task completion event sent back
"""

from tests.conftest import TOKEN

STEP_LOG: list[str] = []


def test_vertical_slice_round_trip(client):
    # 1. citizen spawns in Minecraft and registers
    r = client.post("/v1/citizens/register", headers=TOKEN, json={
        "citizen_id": "uuid-citizen-0001", "name": "Alex", "profession": "UNASSIGNED",
        "position": "0,64,0",
    })
    assert r.status_code == 200, r.text
    STEP_LOG.append("registered")

    # 2. citizen observes its situation and asks for a decision
    obs = {
        "observation": {
            "citizen": {"name": "Alex", "profession": "UNASSIGNED",
                        "health": 20.0, "hunger": 20.0, "energy": 1.0},
            "position": [0, 64, 0],
            "current_goal": None,
            "inventory": {},
            "nearby": {"resources": [{"type": "minecraft:oak_log", "distance": 47}]},
            "civilization": {"population": 1, "food_reserve": 200,
                             "active_projects": 0, "day": 1},
            "recent_events": [],
        },
        "priority": "HIGH",
        "reason": "no_goal",
    }
    r = client.post("/v1/citizens/uuid-citizen-0001/decision", json=obs, headers=TOKEN)
    assert r.status_code == 200, r.text
    decision = r.json()
    assert decision["accepted"] is True
    goal = decision["decision"]["goal"]
    tasks = decision["decision"]["tasks"]
    assert goal["type"] == "INCREASE_RESOURCE"
    assert goal["resource"] == "minecraft:oak_log"
    assert tasks and tasks[0]["type"] == "GATHER"

    # 3. Minecraft validates + accepts (Java mirrors this validation)
    from minecivilization_ai.schemas import Decision
    parsed = Decision.model_validate(decision["decision"])  # strict re-validation
    STEP_LOG.append("task accepted")

    # 4. deterministic skills execute (IDLE -> MOVE_TO -> MINE_BLOCK -> PICKUP_ITEM)
    for skill in ("MOVE_TO", "MINE_BLOCK", "PICKUP_ITEM"):
        r = client.post("/v1/citizens/uuid-citizen-0001/task-result", headers=TOKEN,
                        json={"task_type": "GATHER", "outcome": "IN_PROGRESS",
                              "progress": 0.5, "details": {"skill": skill}})
        assert r.status_code == 200
    STEP_LOG.append("skills executed")

    # 5. completion event flows back
    r = client.post("/v1/citizens/uuid-citizen-0001/task-result", headers=TOKEN,
                    json={"task_type": "GATHER", "outcome": "COMPLETED", "progress": 1.0,
                          "details": {"gathered": 16,
                                      "item": "minecraft:oak_log"}})
    assert r.status_code == 200
    STEP_LOG.append("completed")

    events = client.get("/v1/events", headers=TOKEN).json()
    assert any(e["type"] == "CITIZEN_SPAWNED" for e in events)
    assert any(e["type"] == "TASK_COMPLETED" for e in events)

    # memory retained the outcome for future decisions
    info = client.get("/v1/citizens/uuid-citizen-0001", headers=TOKEN).json()
    assert any("Completed task GATHER" in m for m in info["memories"])
    assert info["current_goal"] is not None

    # decision history is persisted for observability/science
    metrics = client.get("/v1/metrics", headers=TOKEN).json()
    assert metrics["total_decisions"] >= 1

    assert STEP_LOG == ["registered", "task accepted", "skills executed", "completed"]
