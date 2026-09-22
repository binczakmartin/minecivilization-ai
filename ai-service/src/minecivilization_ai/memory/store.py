"""Agent memory: working / episodic / semantic / social.

SQLite-backed with lightweight text-search relevance scoring (term overlap +
importance + recency). No vector DB in the MVP; the schema leaves room to add
local embeddings (Ollama/MLX) later without redesign.
"""

from __future__ import annotations

import math
import re
import time
from datetime import datetime, timezone

from sqlmodel import Session, select

from ..db.models import CitizenMemory

_TOKEN = re.compile(r"[a-z0-9_:]+")

KINDS = ("WORKING", "EPISODIC", "SEMANTIC", "SOCIAL")


def remember(
    session: Session,
    citizen_id: str,
    content: str,
    kind: str = "EPISODIC",
    importance: float = 0.5,
    meta: dict | None = None,
) -> CitizenMemory:
    if kind not in KINDS:
        raise ValueError(f"unknown memory kind: {kind}")
    mem = CitizenMemory(
        citizen_id=citizen_id,
        kind=kind,
        content=content,
        importance=max(0.0, min(1.0, importance)),
        meta=meta or {},
        created_at=datetime.now(timezone.utc),
    )
    session.add(mem)
    session.commit()
    session.refresh(mem)
    return mem


def _tokens(text: str) -> set[str]:
    return set(_TOKEN.findall(text.lower()))


def _recency(mem: CitizenMemory, now: float) -> float:
    created = mem.created_at
    if created is None:
        return 0.5
    if created.tzinfo is None:
        created = created.replace(tzinfo=timezone.utc)
    age_days = max(0.0, (now - created.timestamp()) / 86400.0)
    return math.exp(-age_days / 30.0)  # half-life ~21 days


def search(
    session: Session,
    citizen_id: str,
    query: str,
    kinds: tuple[str, ...] = KINDS,
    limit: int = 8,
) -> list[tuple[CitizenMemory, float]]:
    """Text search with relevance = 0.45 overlap + 0.35 importance + 0.20 recency."""
    rows = session.exec(
        select(CitizenMemory).where(CitizenMemory.citizen_id == citizen_id)
    ).all()
    q = _tokens(query)
    now = time.time()
    scored: list[tuple[CitizenMemory, float]] = []
    for mem in rows:
        if mem.kind not in kinds:
            continue
        overlap = 0.0
        if q:
            toks = _tokens(mem.content)
            overlap = len(q & toks) / len(q) if toks else 0.0
        elif mem.kind == "WORKING":
            overlap = 1.0
        score = 0.45 * overlap + 0.35 * (mem.importance or 0.5) + 0.20 * _recency(mem, now)
        if overlap == 0.0 and score < 0.35:
            continue
        scored.append((mem, round(score, 4)))
    scored.sort(key=lambda t: t[1], reverse=True)
    return scored[:limit]


def recent(session: Session, citizen_id: str, limit: int = 10,
           kinds: tuple[str, ...] = KINDS) -> list[CitizenMemory]:
    rows = session.exec(
        select(CitizenMemory)
        .where(CitizenMemory.citizen_id == citizen_id, CitizenMemory.kind.in_(kinds))
        .order_by(CitizenMemory.created_at.desc())  # type: ignore[attr-defined]
        .limit(limit)
    ).all()
    return list(rows)


def working_set(session: Session, citizen_id: str) -> list[str]:
    return [m.content for m in recent(session, citizen_id, 5, kinds=("WORKING",))]


def set_working(session: Session, citizen_id: str, content: str,
                importance: float = 0.9) -> CitizenMemory:
    """Replace the working-memory set with a single current-state entry."""
    for m in recent(session, citizen_id, 50, kinds=("WORKING",)):
        session.delete(m)
    session.commit()
    return remember(session, citizen_id, content, kind="WORKING", importance=importance)
