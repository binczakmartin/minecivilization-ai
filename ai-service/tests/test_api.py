"""API tests: auth, register/observe/decision/task-result, events, projects, technologies."""

from tests.conftest import TOKEN


def obs_body(**kwargs):
    base = {
        "citizen": {"name": "Alex", "profession": "BUILDER", "health": 20, "hunger": 18},
        "position": [100, 64, -20],
        "inventory": {},
        "civilization": {"population": 1, "food_reserve": 400, "active_projects": 0},
        "current_goal": None,
        "recent_events": [],
    }
    base.update(kwargs)
    return {"observation": base, "priority": "NORMAL", "reason": "no_goal"}


def register(client, cid="c-alex", name="Alex"):
    r = client.post("/v1/citizens/register",
                    json={"citizen_id": cid, "name": name, "profession": "BUILDER"},
                    headers=TOKEN)
    assert r.status_code == 200, r.text
    return r.json()


# ---------------------------------------------------------------- health/auth

def test_health_no_auth_needed(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert body["provider"] == "mock"
    assert body["provider_available"] is True


def test_endpoints_require_token(client):
    assert client.get("/v1/citizens/anything").status_code == 401
    assert client.post("/v1/citizens/register", json={}).status_code == 401
    assert client.get("/v1/projects",
                      headers={"Authorization": "Bearer wrong"}).status_code == 401


# ---------------------------------------------------------------- citizens

def test_register_creates_citizen_with_deterministic_personality(client):
    info = register(client)
    assert info["name"] == "Alex"
    assert info["status"] == "IDLE"
    p = info["personality"]
    assert all(0.0 <= p[k] <= 1.0 for k in p)

    # re-registering the same id is idempotent and returns identical personality
    again = register(client)
    assert again["personality"] == p


def test_get_citizen_unknown_404(client):
    assert client.get("/v1/citizens/nope", headers=TOKEN).status_code == 404


def test_observe_updates_state(client):
    register(client)
    r = client.post("/v1/citizens/c-alex/observe", headers=TOKEN, json={
        "state": {"health": 17.0, "status": "WORKING", "position": "1,2,3"},
        "events": [{"type": "TASK_STARTED", "payload": {"task": "MOVE_TO"}}],
    })
    assert r.status_code == 200
    info = client.get("/v1/citizens/c-alex", headers=TOKEN).json()
    assert info["health"] == 17.0
    assert info["status"] == "WORKING"
    assert info["position"] == "1,2,3"


# ---------------------------------------------------------------- decisions

def test_decision_returns_valid_structured_output(client):
    register(client)
    r = client.post("/v1/citizens/c-alex/decision", json=obs_body(), headers=TOKEN)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["accepted"] is True
    assert body["provider"] == "mock"
    assert body["decision"]["goal"]["type"] == "INCREASE_RESOURCE"
    assert body["decision"]["tasks"][0]["type"] == "GATHER"
    assert body["decision"]["tasks"][0]["quantity"] == 16
    assert body["decision"]["reasoning_summary"]


def test_decision_for_unknown_citizen_404(client):
    r = client.post("/v1/citizens/ghost/decision", json=obs_body(), headers=TOKEN)
    assert r.status_code == 404


def test_malformed_observation_rejected_422(client):
    register(client)
    bad = obs_body()
    bad["observation"]["inventory"] = {"minecraft:oak_log": -1}
    r = client.post("/v1/citizens/c-alex/decision", json=bad, headers=TOKEN)
    assert r.status_code == 422


def test_malformed_llm_output_yields_accepted_false(client):
    register(client)

    class BadProvider:
        name = "bad"
        model = "bad-1"

        async def generate_structured(self, request):
            from minecivilization_ai.llm.base import LLMRawResult
            return LLMRawResult(data={"reasoning_summary": "hi"}, provider=self.name,
                                model=self.model)

        async def health(self):
            return True

        async def list_models(self):
            return []

    original = client.app.state.scheduler.provider
    client.app.state.scheduler.provider = BadProvider()
    try:
        r = client.post("/v1/citizens/c-alex/decision", json=obs_body(), headers=TOKEN)
        assert r.status_code == 200
        body = r.json()
        assert body["accepted"] is False
        assert "rejected" in body["reject_reason"]
        # safe idle fallback included so Minecraft always gets a valid shape
        assert body["decision"]["goal"]["type"] == "IDLE"
    finally:
        client.app.state.scheduler.provider = original


def test_queue_full_returns_429(client):
    register(client)
    sched = client.app.state.scheduler
    old = sched.queue_limit
    sched.queue_limit = 0
    try:
        r = client.post("/v1/citizens/c-alex/decision", json=obs_body(), headers=TOKEN)
        assert r.status_code == 429
    finally:
        sched.queue_limit = old


# ---------------------------------------------------------------- task results / events

def test_task_failure_recorded_in_memory_and_events(client):
    register(client)
    r = client.post("/v1/citizens/c-alex/task-result", headers=TOKEN, json={
        "task_type": "GATHER",
        "skill": "MOVE_TO",
        "outcome": "FAILED",
        "failure": {"code": "TARGET_UNREACHABLE",
                    "message": "Could not reach block after 3 repaths.",
                    "recoverable": True},
        "progress": 0.3,
    })
    assert r.status_code == 200
    info = client.get("/v1/citizens/c-alex", headers=TOKEN).json()
    assert any("TARGET_UNREACHABLE" in m for m in info["memories"])
    events = client.get("/v1/events", headers=TOKEN).json()
    assert any(e["type"] == "TASK_FAILED" for e in events)


def test_task_completion_stores_event(client):
    register(client)
    r = client.post("/v1/citizens/c-alex/task-result", headers=TOKEN, json={
        "task_type": "GATHER", "outcome": "COMPLETED", "progress": 1.0,
        "details": {"gathered": 16},
    })
    assert r.status_code == 200
    events = client.get("/v1/events", headers=TOKEN).json()
    assert any(e["type"] == "TASK_COMPLETED" for e in events)


def test_events_endpoint(client):
    register(client)
    r = client.post("/v1/events", headers=TOKEN, json={
        "type": "RESOURCE_DISCOVERED", "citizen_id": "c-alex",
        "payload": {"resource": "minecraft:iron_ore", "position": "820,12,-312"},
    })
    assert r.status_code == 200
    events = client.get("/v1/events", headers=TOKEN).json()
    assert events[0]["type"] == "RESOURCE_DISCOVERED"


def test_unknown_event_type_rejected(client):
    r = client.post("/v1/events", headers=TOKEN,
                    json={"type": "CHEAT_GIVE_ITEMS"})
    assert r.status_code == 422


# ---------------------------------------------------------------- projects

def test_project_crud(client):
    r = client.post("/v1/projects", headers=TOKEN, json={"name": "Starter Warehouse"})
    assert r.status_code == 200
    p = r.json()
    assert p["status"] == "PLANNED"
    assert p["requirements"] == {}

    listed = client.get("/v1/projects", headers=TOKEN).json()
    assert len(listed) == 1 and listed[0]["name"] == "Starter Warehouse"


def test_project_with_blueprint_generates_bom_and_waiting_status(client):
    # insert a blueprint directly (import path is exercised separately)
    from minecivilization_ai.db.models import Blueprint
    from minecivilization_ai.db.session import session_scope
    with session_scope() as s:
        s.add(Blueprint(id="starter_warehouse", name="Starter Warehouse",
                        width=9, height=6, depth=7, block_count=90,
                        bom={"minecraft:oak_planks": 40, "minecraft:stone_bricks": 60}))
        s.commit()

    r = client.post("/v1/projects", headers=TOKEN,
                    json={"name": "Warehouse A", "blueprint_id": "starter_warehouse"})
    assert r.status_code == 200
    p = r.json()
    assert p["status"] == "WAITING_FOR_RESOURCES"
    assert p["requirements"] == {"minecraft:oak_planks": 40, "minecraft:stone_bricks": 60}
    assert p["missing"] == {"minecraft:oak_planks": 40, "minecraft:stone_bricks": 60}


def test_project_unknown_blueprint_404(client):
    r = client.post("/v1/projects", headers=TOKEN,
                    json={"name": "X", "blueprint_id": "ghost"})
    assert r.status_code == 404


# ---------------------------------------------------------------- civilization / tech

def test_civilization_state(client):
    register(client, "c-1", "Alice")
    register(client, "c-2", "Bob")
    state = client.get("/v1/civilization/state", headers=TOKEN).json()
    assert state["population"] == 2
    assert state["seed"] == 42
    assert state["civilization_id"] == "default"


def test_technologies_list(client):
    from minecivilization_ai.db.models import Technology, TechnologyVersion
    from minecivilization_ai.db.session import session_scope
    with session_scope() as s:
        s.add(Technology(id="auto_sugar_cane_v1", name="Automatic Sugar Cane Farm",
                         category="agriculture",
                         outputs=["minecraft:sugar_cane"],
                         mechanisms=["observer detects growth", "piston breaks crop",
                                     "water/hopper collects items"],
                         verified=False))
        s.add(TechnologyVersion(technology_id="auto_sugar_cane_v1", version=1,
                                notes="initial",
                                construction_cost=None,   # unknown until measured
                                throughput=None))
        s.commit()
    rows = client.get("/v1/technologies", headers=TOKEN).json()
    assert rows[0]["id"] == "auto_sugar_cane_v1"
    assert rows[0]["verified"] is False
    assert rows[0]["mechanisms"]


def test_metrics_endpoint(client):
    register(client)
    client.post("/v1/citizens/c-alex/decision", json=obs_body(), headers=TOKEN)
    m = client.get("/v1/metrics", headers=TOKEN).json()
    assert m["total_decisions"] >= 1
    assert m["provider"] == "mock"
    assert m["avg_latency_ms"] >= 0
