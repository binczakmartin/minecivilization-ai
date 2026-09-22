"""Civilization-level world knowledge (discovered assets only — never server omniscience)."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlmodel import Session, select

from ..db.models import KnownLocation

LOCATION_KINDS = (
    "STORAGE", "MINE", "FARM", "BUILDING", "ROAD", "DEPOSIT",
    "HOUSE", "WAREHOUSE", "FACTORY", "RAIL", "MONUMENT",
)


def register_location(
    session: Session,
    civilization_id: str,
    kind: str,
    name: str,
    position: str,
    discovered_by: str | None = None,
    meta: dict | None = None,
    location_id: str | None = None,
) -> KnownLocation:
    if kind not in LOCATION_KINDS:
        raise ValueError(f"unknown location kind: {kind}")
    loc = KnownLocation(
        id=location_id or str(uuid.uuid4()),
        civilization_id=civilization_id,
        kind=kind,
        name=name,
        position=position,
        discovered_by=discovered_by,
        discovered_at=datetime.now(timezone.utc),
        meta=meta or {},
    )
    session.add(loc)
    session.commit()
    session.refresh(loc)
    return loc


def list_locations(session: Session, civilization_id: str = "default",
                   kind: str | None = None) -> list[KnownLocation]:
    stmt = select(KnownLocation).where(KnownLocation.civilization_id == civilization_id)
    if kind:
        stmt = stmt.where(KnownLocation.kind == kind)
    return list(session.exec(stmt).all())


def nearest(session: Session, kind: str, position: tuple[int, int, int],
            civilization_id: str = "default") -> tuple[KnownLocation, float] | None:
    best: tuple[KnownLocation, float] | None = None
    for loc in list_locations(session, civilization_id, kind):
        try:
            x, y, z = (int(p) for p in loc.position.split(","))
        except (ValueError, AttributeError):
            continue
        d = ((x - position[0]) ** 2 + (y - position[1]) ** 2 + (z - position[2]) ** 2) ** 0.5
        if best is None or d < best[1]:
            best = (loc, d)
    return best
