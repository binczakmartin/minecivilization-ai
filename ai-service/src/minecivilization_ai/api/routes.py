"""HTTP API — binds 127.0.0.1 only, bearer-token protected (except /health)."""

from __future__ import annotations

import logging
import time
import uuid
from datetime import datetime, timezone
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import ValidationError
from sqlmodel import Session, func, select

from .. import __version__
from .. import memory as memory_store
from ..agents.cognition import QueueFullError
from ..config import get_settings
from ..db.models import (
    Citizen,
    Civilization,
    LLMDecision,
    Project,
    ProjectRequirement,
    ProjectWorker,
    Technology,
    WorldEvent,
)
from ..db.session import get_session
from ..economy.ledger import get_or_create_account
from ..knowledge import store as knowledge_store
from ..llm.base import LLMError, LLMRequest
from ..llm.mock import MockProvider
from ..prompts import load_prompt
from ..schemas import (
    CitizenInfo,
    CitizenPersonality,
    CitizenRegister,
    CitizenSkills,
    CivilizationState,
    Decision,
    DecisionRequest,
    DecisionResponse,
    EventIn,
    Goal,
    GoalType,
    HealthResponse,
    Observation,
    PRIORITY_ORDER,
    ProjectCreate,
    ProjectInfo,
    Task,
    TaskResult,
    TaskType,
    TechnologyInfo,
)

log = logging.getLogger("mineciv.api")

router = APIRouter(prefix="/v1")

SAFE_IDLE = Decision(
    reasoning_summary="safe idle decision (cognition unavailable or rejected)",
    goal=Goal(type=GoalType.IDLE),
    tasks=[Task(type=TaskType.REST)],
)


# --------------------------------------------------------------------------- auth

def require_token(
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    settings = get_settings()
    if not settings.require_auth:
        return
    expected = f"Bearer {settings.api_token}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="invalid or missing bearer token")


Auth = Depends(require_token)
DbDep = Depends(get_session)


def _scheduler(request: Request):
    return request.app.state.scheduler


def _provider(request: Request):
    return request.app.state.provider


# --------------------------------------------------------------------------- health

health_router = APIRouter()


@health_router.get("/health", response_model=HealthResponse)
async def health(request: Request) -> HealthResponse:
    settings = get_settings()
    scheduler = request.app.state.scheduler
    provider = request.app.state.provider
    available = await provider.health()
    stats = scheduler.stats()
    started_at: float = getattr(request.app.state, "started_at", time.time())
    return HealthResponse(
        status="ok" if available else "degraded",
        version=__version__,
        provider=getattr(provider, "name", "unknown"),
        provider_available=available,
        model=getattr(provider, "model", None),
        queue_depth=stats.queue_depth,
        decisions_last_minute=stats.decisions_last_minute,
        uptime_seconds=round(time.time() - started_at, 1),
    )


# --------------------------------------------------------------------------- citizens

@router.post("/citizens/register", response_model=CitizenInfo, dependencies=[Auth])
async def register_citizen(
    body: CitizenRegister,
    request: Request,
    session: Session = DbDep,
) -> CitizenInfo:
    settings = get_settings()
    existing = session.get(Citizen, body.citizen_id)
    if existing is not None:
        return _citizen_info(session, existing)

    # Deterministic personality from (seed, citizenId) when not provided.
    personality = body.personality or _derived_personality(settings.seed, body.citizen_id)
    skills = body.skills or CitizenSkills()

    citizen = Citizen(
        id=body.citizen_id,
        name=body.name,
        profession=body.profession,
        employer_id=body.employer_id,
        company_id=body.company_id,
        position=body.position,
        personality=personality.model_dump(),
        skills=skills.model_dump(),
        created_at=datetime.now(timezone.utc),
    )
    session.add(citizen)
    _ensure_civilization(session, settings.seed)
    get_or_create_account(session, "CITIZEN", body.citizen_id)
    session.commit()
    session.refresh(citizen)

    session.add(WorldEvent(
        type="CITIZEN_SPAWNED", citizen_id=citizen.id,
        payload={"name": citizen.name, "profession": citizen.profession},
    ))
    memory_store.remember(
        session, citizen.id,
        f"{citizen.name} was born into the civilization as {citizen.profession}.",
        kind="EPISODIC", importance=0.8,
    )
    session.commit()
    log.info("citizen registered: %s (%s)", citizen.name, citizen.id)
    return _citizen_info(session, citizen)


@router.get("/citizens/{citizen_id}", response_model=CitizenInfo, dependencies=[Auth])
async def get_citizen(citizen_id: str, session: Session = DbDep) -> CitizenInfo:
    citizen = session.get(Citizen, citizen_id)
    if citizen is None:
        raise HTTPException(status_code=404, detail="unknown citizen")
    return _citizen_info(session, citizen)


@router.post("/citizens/{citizen_id}/observe", dependencies=[Auth])
async def observe(
    citizen_id: str,
    body: dict[str, Any],
    session: Session = DbDep,
) -> dict[str, Any]:
    """Store a state snapshot. Compact, never a world dump."""
    citizen = session.get(Citizen, citizen_id)
    if citizen is None:
        raise HTTPException(status_code=404, detail="unknown citizen")

    state = body.get("state") or {}
    for field in ("health", "hunger", "energy", "status", "current_goal",
                  "current_task", "position", "profession"):
        if field in state and state[field] is not None:
            setattr(citizen, field, state[field])
    citizen.last_seen = datetime.now(timezone.utc)
    session.add(citizen)

    events = body.get("events") or []
    stored = 0
    for ev in events[:20]:
        session.add(WorldEvent(
            type=str(ev.get("type", "UNKNOWN")), citizen_id=citizen_id,
            payload=ev.get("payload") or {},
        ))
        stored += 1
    for mem in (body.get("memories") or [])[:10]:
        content = mem.get("content")
        if content:
            memory_store.remember(
                session, citizen_id, str(content)[:500],
                kind=str(mem.get("kind", "EPISODIC")),
                importance=float(mem.get("importance", 0.5)),
            )
    session.commit()
    return {"stored": True, "events": stored}


@router.post("/citizens/{citizen_id}/decision", response_model=DecisionResponse,
             dependencies=[Auth])
async def decide(
    citizen_id: str,
    body: DecisionRequest,
    request: Request,
    session: Session = DbDep,
) -> DecisionResponse:
    citizen = session.get(Citizen, citizen_id)
    if citizen is None:
        raise HTTPException(status_code=404, detail="unknown citizen")

    scheduler = _scheduler(request)
    settings = get_settings()

    # Return known memories relevant to the request so the prompt stays compact.
    hints = memory_store.search(
        session, citizen_id,
        " ".join(body.observation.recent_events[:5]) or "goal task project",
        limit=5,
    )
    payload = {
        "citizen_id": citizen_id,
        "reason": body.reason,
        "observation": body.observation.model_dump(mode="json"),
        "known_memories": [m.content for m, _ in hints],
        "personality": citizen.personality,
        "skills": citizen.skills,
        "system_prompt": load_prompt("citizen_system"),
    }

    # Release the read transaction before awaiting inference/rate limiting. Holding
    # one connection per queued citizen exhausts the pool and blocks the event loop.
    session.rollback()
    priority = PRIORITY_ORDER[body.priority]
    try:
        result = await scheduler.submit(
            LLMRequest(
                system=payload.pop("system_prompt"),
                payload=payload,
                schema_name="decision",
                scope="CITIZEN",
            ),
            priority=priority,
            validator=lambda d: Decision.model_validate(d),
        )
    except QueueFullError:
        raise HTTPException(status_code=429, detail="cognition queue full — retry later")
    except LLMError as exc:
        raise HTTPException(status_code=503, detail=f"cognition unavailable: {exc}")

    provider_name = result.raw.provider if result.raw else "unknown"
    model = result.raw.model if result.raw else None

    if result.reject_reason is not None:
        session.add(LLMDecision(
            citizen_id=citizen_id, provider=provider_name, model=model,
            priority=priority, latency_ms=result.latency_ms, accepted=False,
            reject_reason=result.reject_reason,
            request_summary={"reason": body.reason},
            decision=result.raw.data if result.raw else {},
        ))
        session.commit()
        return DecisionResponse(
            decision=SAFE_IDLE, accepted=False, reject_reason=result.reject_reason,
            provider=provider_name, model=model, latency_ms=result.latency_ms,
            queued_behind=result.queued_behind,
        )

    citizen = session.get(Citizen, citizen_id)
    if citizen is None:
        raise HTTPException(status_code=404, detail="citizen removed while deciding")
    decision: Decision = result.parsed
    _apply_decision_side_effects(session, citizen, decision)
    session.add(LLMDecision(
        citizen_id=citizen_id, provider=provider_name, model=model,
        priority=priority, latency_ms=result.latency_ms, accepted=True,
        request_summary={"reason": body.reason},
        decision=decision.model_dump(mode="json"),
    ))
    session.commit()
    return DecisionResponse(
        decision=decision, accepted=True,
        provider=provider_name, model=model,
        latency_ms=result.latency_ms, queued_behind=result.queued_behind,
    )


@router.post("/citizens/{citizen_id}/task-result", dependencies=[Auth])
async def task_result(
    citizen_id: str,
    body: TaskResult,
    session: Session = DbDep,
) -> dict[str, Any]:
    citizen = session.get(Citizen, citizen_id)
    if citizen is None:
        raise HTTPException(status_code=404, detail="unknown citizen")

    ev_type = {
        "COMPLETED": "TASK_COMPLETED",
        "FAILED": "TASK_FAILED",
        "CANCELLED": "TASK_COMPLETED",
        "IN_PROGRESS": "TASK_STARTED",
    }.get(body.outcome.value, "TASK_COMPLETED")
    session.add(WorldEvent(
        type=ev_type, citizen_id=citizen_id,
        payload={"task": body.task_type.value, "outcome": body.outcome.value,
                 "skill": body.skill.value if body.skill else None,
                 "failure": body.failure.model_dump() if body.failure else None,
                 **body.details},
    ))

    if body.outcome.value == "FAILED" and body.failure is not None:
        memory_store.remember(
            session, citizen_id,
            f"Task {body.task_type.value} failed: [{body.failure.code}] {body.failure.message}",
            kind="EPISODIC",
            importance=0.7 if body.failure.recoverable else 0.9,
        )
        citizen.status = "BLOCKED" if not body.failure.recoverable else citizen.status
    elif body.outcome.value == "COMPLETED":
        memory_store.remember(
            session, citizen_id,
            f"Completed task {body.task_type.value}.",
            kind="EPISODIC", importance=0.3,
        )
        if citizen.status == "BLOCKED":
            citizen.status = "IDLE"
    session.add(citizen)
    session.commit()
    return {"stored": True}


# --------------------------------------------------------------------------- events

@router.post("/events", dependencies=[Auth])
async def ingest_event(body: EventIn, session: Session = DbDep) -> dict[str, Any]:
    session.add(WorldEvent(
        type=body.type.value,
        civilization_id=body.civilization_id,
        citizen_id=body.citizen_id,
        payload=body.payload,
        created_at=body.created_at or datetime.now(timezone.utc),
    ))
    if body.citizen_id and body.type.value in (
        "RESOURCE_DISCOVERED", "PROJECT_COMPLETED", "CITIZEN_DIED", "TECHNOLOGY_LEARNED"
    ):
        memory_store.remember(
            session, body.citizen_id,
            f"{body.type.value}: {str(body.payload)[:300]}",
            kind="EPISODIC", importance=0.8,
        )
    # Cache storage inventory snapshots when Minecraft reports them.
    if body.type.value in ("STORAGE_REGISTERED", "STORAGE_LOW", "STORAGE_FULL"):
        inv = body.payload.get("inventory")
        if isinstance(inv, dict):
            _update_stored_resources(session, body.civilization_id, inv)
    session.commit()
    return {"stored": True}


# --------------------------------------------------------------------------- civilization

@router.get("/civilization/state", response_model=CivilizationState, dependencies=[Auth])
async def civilization_state(session: Session = DbDep) -> CivilizationState:
    civ = session.get(Civilization, "default")
    if civ is None:
        return CivilizationState(civilization_id="default", name="unnamed", seed=0,
                                 population=0, day=0, active_projects=0,
                                 completed_projects=0, companies=0, technologies=0)

    population = session.exec(select(func.count()).select_from(Citizen)).one()
    projects = list(session.exec(select(Project)).all())
    active = sum(1 for p in projects if p.status in
                 ("PROPOSED", "PLANNED", "WAITING_FOR_RESOURCES", "BUILDING"))
    completed = sum(1 for p in projects if p.status == "COMPLETED")
    from ..db.models import Company
    companies = session.exec(select(func.count()).select_from(Company)).one()
    technologies = session.exec(select(func.count()).select_from(Technology)).one()
    recent = list(session.exec(
        select(WorldEvent).order_by(WorldEvent.created_at.desc()).limit(10)  # type: ignore[attr-defined]
    ).all())

    bottlenecks: list[str] = []
    for p in projects:
        if p.status == "WAITING_FOR_RESOURCES":
            missing = [
                f"{r.item} x{max(0, r.required - r.provided)}"
                for r in session.exec(
                    select(ProjectRequirement).where(ProjectRequirement.project_id == p.id)
                ).all() if r.provided < r.required
            ]
            if missing:
                bottlenecks.append(f"{p.name} missing: {', '.join(missing[:4])}")

    return CivilizationState(
        civilization_id=civ.id,
        name=civ.name,
        seed=civ.seed,
        population=population,
        day=int(civ.state_json.get("day", 0)),
        active_projects=active,
        completed_projects=completed,
        companies=companies,
        technologies=technologies,
        stored_resources=dict(civ.state_json.get("stored_resources", {})),
        bottlenecks=bottlenecks,
        recent_events=[
            {"type": e.type, "citizen_id": e.citizen_id, "created_at": e.created_at.isoformat()}
            for e in recent
        ],
    )


@router.get("/events", dependencies=[Auth])
async def list_events(limit: int = 50, session: Session = DbDep) -> list[dict[str, Any]]:
    rows = list(session.exec(
        select(WorldEvent).order_by(WorldEvent.created_at.desc()).limit(min(limit, 200))  # type: ignore[attr-defined]
    ).all())
    return [
        {"type": e.type, "citizen_id": e.citizen_id, "payload": e.payload,
         "created_at": e.created_at.isoformat()}
        for e in rows
    ]


# --------------------------------------------------------------------------- projects

def _project_info(session: Session, p: Project) -> ProjectInfo:
    reqs = list(session.exec(
        select(ProjectRequirement).where(ProjectRequirement.project_id == p.id)
    ).all())
    workers = list(session.exec(
        select(ProjectWorker).where(ProjectWorker.project_id == p.id)
    ).all())
    total = {r.item: r.required for r in reqs}
    provided = {r.item: r.provided for r in reqs}
    missing = {k: max(0, total[k] - provided.get(k, 0)) for k in total
               if provided.get(k, 0) < total[k]}
    return ProjectInfo(
        id=p.id, name=p.name, blueprint_id=p.blueprint_id, status=p.status,
        origin=p.origin, progress=p.progress,
        requirements=total, provided=provided, missing=missing,
        workers=[w.citizen_id for w in workers], created_at=p.created_at,
    )


@router.get("/projects", response_model=list[ProjectInfo], dependencies=[Auth])
async def list_projects(session: Session = DbDep) -> list[ProjectInfo]:
    projects = list(session.exec(select(Project)).all())
    return [_project_info(session, p) for p in projects]


@router.post("/projects", response_model=ProjectInfo, dependencies=[Auth])
async def create_project(body: ProjectCreate, session: Session = DbDep) -> ProjectInfo:
    from ..db.models import Blueprint

    project = Project(
        id=str(uuid.uuid4()),
        civilization_id=body.civilization_id,
        name=body.name,
        blueprint_id=body.blueprint_id,
        origin=body.origin,
        status="PLANNED",
    )
    session.add(project)
    session.flush()

    bom: dict[str, int] = {}
    if body.blueprint_id:
        bp = session.get(Blueprint, body.blueprint_id)
        if bp is None:
            session.rollback()
            raise HTTPException(status_code=404, detail="unknown blueprint")
        bom = {str(k): int(v) for k, v in bp.bom.items()}

    for item, qty in bom.items():
        session.add(ProjectRequirement(project_id=project.id, item=item,
                                       required=qty, provided=0))
    if bom:
        project.status = "WAITING_FOR_RESOURCES"

    session.add(WorldEvent(
        type="PROJECT_CREATED", citizen_id=None,
        payload={"project_id": project.id, "name": project.name, "bom": bom},
    ))
    session.commit()
    session.refresh(project)
    return _project_info(session, project)


# --------------------------------------------------------------------------- technologies

@router.get("/technologies", response_model=list[TechnologyInfo], dependencies=[Auth])
async def list_technologies(session: Session = DbDep) -> list[TechnologyInfo]:
    rows = list(session.exec(select(Technology)).all())
    return [
        TechnologyInfo(
            id=t.id, name=t.name, category=t.category, description=t.description,
            inputs=list(t.inputs or []), outputs=list(t.outputs or []),
            mechanisms=list(t.mechanisms or []), blueprint_ids=list(t.blueprint_ids or []),
            verified=t.verified, current_version=t.current_version,
        )
        for t in rows
    ]


# --------------------------------------------------------------------------- metrics

@router.get("/metrics", dependencies=[Auth])
async def metrics(request: Request) -> dict[str, Any]:
    stats = _scheduler(request).stats()
    return {
        "queue_depth": stats.queue_depth,
        "in_flight": stats.in_flight,
        "decisions_last_minute": stats.decisions_last_minute,
        "total_decisions": stats.total_decisions,
        "rejected": stats.rejected,
        "failures": stats.failures,
        "avg_latency_ms": stats.avg_latency_ms,
        "provider": getattr(_provider(request), "name", "unknown"),
    }


# --------------------------------------------------------------------------- helpers

def _citizen_info(session: Session, c: Citizen) -> CitizenInfo:
    mems = memory_store.recent(session, c.id, limit=8)
    return CitizenInfo(
        citizen_id=c.id, name=c.name, profession=c.profession, status=c.status,
        current_goal=c.current_goal, current_task=c.current_task,
        position=c.position, health=c.health, hunger=c.hunger, energy=c.energy,
        personality=CitizenPersonality(**(c.personality or {})),
        skills=CitizenSkills(**(c.skills or {})),
        memories=[m.content for m in mems],
        created_at=c.created_at, last_decision_at=c.last_decision_at,
    )


def _derived_personality(seed: int, citizen_id: str) -> CitizenPersonality:
    """Deterministic per-citizen personality from (seed, id) — reproducible runs."""
    import hashlib
    digest = hashlib.sha256(f"{seed}:{citizen_id}".encode()).digest()
    vals = [digest[i] / 255.0 for i in range(7)]
    return CitizenPersonality(
        curiosity=vals[0], ambition=vals[1], sociability=vals[2],
        patience=vals[3], risk_tolerance=vals[4], cooperation=vals[5],
        creativity=vals[6],
    )


def _ensure_civilization(session: Session, seed: int) -> Civilization:
    civ = session.get(Civilization, "default")
    if civ is None:
        civ = Civilization(id="default", name="MineCivilization", seed=seed)
        session.add(civ)
        session.commit()
    return civ


def _update_stored_resources(session: Session, civilization_id: str, inventory: dict) -> None:
    civ = session.get(Civilization, civilization_id)
    if civ is None:
        return
    stored = dict(civ.state_json.get("stored_resources", {}))
    for k, v in inventory.items():
        stored[str(k)] = int(v)
    civ.state_json = {**civ.state_json, "stored_resources": stored}
    session.add(civ)


def _apply_decision_side_effects(session: Session, citizen: Citizen, decision: Decision) -> None:
    citizen.current_goal = (
        f"{decision.goal.type.value}"
        + (f"|{decision.goal.resource}" if decision.goal.resource else "")
        + (f"|{decision.goal.project_id}" if decision.goal.project_id else "")
    )
    citizen.current_task = decision.tasks[0].type.value if decision.tasks else None
    citizen.last_decision_at = datetime.now(timezone.utc)
    citizen.status = "WORKING" if decision.goal.type != GoalType.IDLE else "IDLE"
    session.add(citizen)
    memory_store.set_working(
        session, citizen.id,
        f"Current goal: {decision.goal.type.value}"
        + (f" ({decision.goal.resource} x{decision.goal.target_quantity})"
           if decision.goal.resource else ""),
    )
