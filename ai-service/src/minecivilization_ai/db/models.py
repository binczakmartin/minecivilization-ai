"""SQLite persistence models (SQLModel). JSON columns only where flexibility is justified."""

from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import Column, JSON, Float
from sqlmodel import Field, SQLModel


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Civilization(SQLModel, table=True):
    __tablename__ = "civilizations"
    id: str = Field(primary_key=True)
    name: str
    seed: int = 0
    created_at: datetime = Field(default_factory=utcnow)
    # cached simulation snapshot (population, day, ...) — advisory, Minecraft is authoritative
    state_json: dict = Field(default_factory=dict, sa_column=Column(JSON))


class Settlement(SQLModel, table=True):
    __tablename__ = "settlements"
    id: str = Field(primary_key=True)
    civilization_id: str = Field(index=True)
    name: str
    position: str  # "x,y,z"
    created_at: datetime = Field(default_factory=utcnow)


class KnownLocation(SQLModel, table=True):
    """Civilization knowledge (NOT server omniscience). Only legitimately discovered assets."""

    __tablename__ = "known_locations"
    id: str = Field(primary_key=True)  # stable UUID assigned on discovery
    civilization_id: str = Field(index=True)
    kind: str  # STORAGE | MINE | FARM | BUILDING | ROAD | DEPOSIT | ...
    name: str
    position: str  # "x,y,z"
    discovered_by: str | None = None
    discovered_at: datetime = Field(default_factory=utcnow)
    meta_json: dict = Field(default_factory=dict, sa_column=Column(JSON))


class Citizen(SQLModel, table=True):
    __tablename__ = "citizens"
    id: str = Field(primary_key=True)  # persistent citizenId (UUID, independent of entity runtime id)
    name: str
    profession: str = "UNASSIGNED"
    employer_id: str | None = None
    company_id: str | None = None
    home_position: str | None = None
    workplace_position: str | None = None
    # lightweight state mirror for the dashboard
    health: float = 20.0
    hunger: float = 20.0
    energy: float = 1.0
    status: str = "IDLE"
    current_goal: str | None = None
    current_task: str | None = None
    position: str | None = None
    personality: dict = Field(default_factory=dict, sa_column=Column(JSON))
    skills: dict = Field(default_factory=dict, sa_column=Column(JSON))
    created_at: datetime = Field(default_factory=utcnow)
    last_seen: datetime | None = None
    last_decision_at: datetime | None = None


class CitizenMemory(SQLModel, table=True):
    __tablename__ = "citizen_memories"
    id: int | None = Field(default=None, primary_key=True)
    citizen_id: str = Field(index=True)
    kind: str = Field(index=True)  # WORKING | EPISODIC | SEMANTIC | SOCIAL
    content: str
    importance: float = 0.5
    created_at: datetime = Field(default_factory=utcnow, index=True)
    meta: dict = Field(default_factory=dict, sa_column=Column(JSON))


class CitizenRelationship(SQLModel, table=True):
    __tablename__ = "citizen_relationships"
    id: int | None = Field(default=None, primary_key=True)
    citizen_id: str = Field(index=True)
    other_id: str = Field(index=True)
    sentiment: float = 0.0  # -1..1
    note: str = ""
    updated_at: datetime = Field(default_factory=utcnow)


class Project(SQLModel, table=True):
    __tablename__ = "projects"
    id: str = Field(primary_key=True)
    civilization_id: str = Field(default="default")
    name: str
    blueprint_id: str | None = None
    status: str = "PROPOSED"  # PROPOSED PLANNED WAITING_FOR_RESOURCES BUILDING COMPLETED FAILED PAUSED
    origin: str | None = None  # "x,y,z" once placed in world
    progress: float = 0.0  # 0..1
    created_at: datetime = Field(default_factory=utcnow)
    updated_at: datetime = Field(default_factory=utcnow)
    meta: dict = Field(default_factory=dict, sa_column=Column(JSON))


class ProjectRequirement(SQLModel, table=True):
    __tablename__ = "project_requirements"
    id: int | None = Field(default=None, primary_key=True)
    project_id: str = Field(index=True)
    item: str  # "minecraft:stone_bricks"
    required: int
    provided: int = 0


class ProjectWorker(SQLModel, table=True):
    __tablename__ = "project_workers"
    id: int | None = Field(default=None, primary_key=True)
    project_id: str = Field(index=True)
    citizen_id: str = Field(index=True)
    role: str = "BUILDER"


class Company(SQLModel, table=True):
    __tablename__ = "companies"
    id: str = Field(primary_key=True)
    name: str
    owner_id: str | None = None
    created_at: datetime = Field(default_factory=utcnow)
    meta: dict = Field(default_factory=dict, sa_column=Column(JSON))


class CompanyMember(SQLModel, table=True):
    __tablename__ = "company_members"
    id: int | None = Field(default=None, primary_key=True)
    company_id: str = Field(index=True)
    citizen_id: str = Field(index=True)
    role: str = "MEMBER"
    joined_at: datetime = Field(default_factory=utcnow)


class Account(SQLModel, table=True):
    __tablename__ = "accounts"
    id: str = Field(primary_key=True)  # account UUID
    owner_type: str  # CITIZEN | COMPANY | TREASURY
    owner_id: str
    currency: str = "CIV"
    # cached denormalization; always derivable from transactions (Minecraft physical world stays authoritative)
    balance: float = 0.0
    created_at: datetime = Field(default_factory=utcnow)


class Transaction(SQLModel, table=True):
    """Append-only ledger entry. Never modified after insert."""

    __tablename__ = "transactions"
    id: str = Field(primary_key=True)
    timestamp: datetime = Field(default_factory=utcnow, index=True)
    from_account: str | None = None  # None = mint/burn (treasury ops only)
    to_account: str | None = None
    amount: float  # > 0
    reason: str
    reference_id: str | None = None


class Technology(SQLModel, table=True):
    __tablename__ = "technologies"
    id: str = Field(primary_key=True)
    name: str
    category: str  # agriculture mining storage transport smelting redstone logistics architecture defense infrastructure industry monuments
    description: str = ""
    inputs: list = Field(default_factory=list, sa_column=Column(JSON))
    outputs: list = Field(default_factory=list, sa_column=Column(JSON))
    mechanisms: list = Field(default_factory=list, sa_column=Column(JSON))
    blueprint_ids: list = Field(default_factory=list, sa_column=Column(JSON))
    verified: bool = False
    current_version: int = 1
    created_at: datetime = Field(default_factory=utcnow)


class TechnologyVersion(SQLModel, table=True):
    __tablename__ = "technology_versions"
    id: int | None = Field(default=None, primary_key=True)
    technology_id: str = Field(index=True)
    version: int
    notes: str = ""
    # metrics are nullable until Minecraft instrumentation exists — never fabricated
    construction_cost: float | None = None
    footprint: float | None = None
    material_cost: float | None = None
    throughput: float | None = None
    reliability: float | None = None
    maintenance_complexity: float | None = None
    created_at: datetime = Field(default_factory=utcnow)


class Blueprint(SQLModel, table=True):
    __tablename__ = "blueprints"
    id: str = Field(primary_key=True)
    name: str
    width: int
    height: int
    depth: int
    block_count: int
    # palette: {"minecraft:oak_planks": 428, ...}
    bom: dict = Field(default_factory=dict, sa_column=Column(JSON))
    source: str = "BUILTIN"  # BUILTIN | IMPORTED
    source_url: str | None = None
    sha256: str | None = None
    license: str | None = None
    author: str | None = None
    format: str | None = None  # nbt | schem | litematic | native
    minecraft_version: str | None = None
    created_at: datetime = Field(default_factory=utcnow)
    meta: dict = Field(default_factory=dict, sa_column=Column(JSON))


class ResearchExperiment(SQLModel, table=True):
    __tablename__ = "research_experiments"
    id: str = Field(primary_key=True)
    technology_id: str | None = None
    blueprint_id: str | None = None
    status: str = "PLANNED"  # PLANNED CONSTRUCTING MEASURING COMPLETED REJECTED
    result: dict = Field(default_factory=dict, sa_column=Column(JSON))  # measured metrics (partial)
    created_at: datetime = Field(default_factory=utcnow)


class WorldEvent(SQLModel, table=True):
    __tablename__ = "events"
    id: int | None = Field(default=None, primary_key=True)
    type: str = Field(index=True)  # CITIZEN_SPAWNED, TASK_COMPLETED, ...
    civilization_id: str = Field(default="default", index=True)
    citizen_id: str | None = Field(default=None, index=True)
    payload: dict = Field(default_factory=dict, sa_column=Column(JSON))
    created_at: datetime = Field(default_factory=utcnow, index=True)


class LLMDecision(SQLModel, table=True):
    __tablename__ = "llm_decisions"
    id: int | None = Field(default=None, primary_key=True)
    citizen_id: str | None = Field(default=None, index=True)
    scope: str = "CITIZEN"  # CITIZEN | CIVILIZATION
    provider: str
    model: str | None = None
    priority: int = 5
    latency_ms: float | None = None
    accepted: bool = True
    reject_reason: str | None = None
    request_summary: dict = Field(default_factory=dict, sa_column=Column(JSON))
    decision: dict = Field(default_factory=dict, sa_column=Column(JSON))
    created_at: datetime = Field(default_factory=utcnow, index=True)


class SchemaVersion(SQLModel, table=True):
    __tablename__ = "schema_version"
    id: int = Field(primary_key=True)
    applied_at: datetime = Field(default_factory=utcnow)
    note: str = ""
