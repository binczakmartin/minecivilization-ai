"""Validated wire schemas shared with the Forge mod (mirrored in Java).

Strict by design: unknown actions, malformed values and impossible requests are rejected.
The LLM never emits free-form Minecraft commands — only these structures.
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Annotated, Any, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


# --------------------------------------------------------------------------- enums

class GoalType(str, Enum):
    IDLE = "IDLE"
    INCREASE_RESOURCE = "INCREASE_RESOURCE"
    GATHER_RESOURCE = "GATHER_RESOURCE"
    DELIVER_RESOURCE = "DELIVER_RESOURCE"
    BUILD_PROJECT = "BUILD_PROJECT"
    HARVEST_FOOD = "HARVEST_FOOD"
    CRAFT_ITEM = "CRAFT_ITEM"
    REST = "REST"
    EXPLORE = "EXPLORE"


class TaskType(str, Enum):
    IDLE = "IDLE"
    REST = "REST"
    GATHER = "GATHER"        # mine/fetch N of a resource from the world
    HARVEST = "HARVEST"      # harvest ripe crops + replant
    PLANT = "PLANT"
    DELIVER = "DELIVER"      # move inventory to a registered storage / project site
    WITHDRAW = "WITHDRAW"
    BUILD = "BUILD"          # advance a construction project blueprint
    PLACE = "PLACE"          # set down a carried block (e.g. the crafting table)
    CRAFT = "CRAFT"
    SMELT = "SMELT"
    MOVE = "MOVE"
    INSPECT = "INSPECT"
    DECORATE = "DECORATE"    # light the streets, lay a hearth, plant flowers
    HUNT = "HUNT"            # kill for meat — last resort, never the penned herd
    MINE_SHAFT = "MINE_SHAFT"  # work the colony's shared mine
    TEND_LIVESTOCK = "TEND_LIVESTOCK"
    PREPARE_PEN = "PREPARE_PEN"
    TAME_WOLF = "TAME_WOLF"
    COLLECT = "COLLECT"
    HERD = "HERD"            # lead an animal home to the pasture
    BREED = "BREED"          # feed a pair so the herd grows
    ESCAPE = "ESCAPE"        # cut a staircase to the surface — the last resort when stranded
    SIGN = "SIGN"            # put up a signpost naming a place
    ROADWORK = "ROADWORK"    # lay, light or widen a stretch of the colony's roads
    EXPLORE = "EXPLORE"      # survey unknown ground and bring back what is there
    SHELTER = "SHELTER"      # wall in for the night; take the walls down at dawn
    HANDOVER = "HANDOVER"    # a courier hands a co-worker the materials it asked for


class SkillType(str, Enum):
    IDLE = "IDLE"
    MOVE_TO = "MOVE_TO"
    TRAVERSE = "TRAVERSE"   # move by changing the terrain: bridge, tunnel, pillar
    FOLLOW = "FOLLOW"
    FIND_BLOCK = "FIND_BLOCK"
    MINE_BLOCK = "MINE_BLOCK"
    MINE_AREA = "MINE_AREA"
    FELL_TREE = "FELL_TREE"
    DIG_MINE = "DIG_MINE"
    DIG_TO_SURFACE = "DIG_TO_SURFACE"   # the escape that always works
    PICKUP_ITEM = "PICKUP_ITEM"
    PLACE_BLOCK = "PLACE_BLOCK"
    PLACE_SIGN = "PLACE_SIGN"
    HARVEST_CROP = "HARVEST_CROP"
    PLANT_CROP = "PLANT_CROP"
    CULTIVATE = "CULTIVATE"
    LIGHT_FARM = "LIGHT_FARM"
    FORAGE = "FORAGE"
    EXPLORE = "EXPLORE"
    TILL_SOIL = "TILL_SOIL"
    TEND_LIVESTOCK = "TEND_LIVESTOCK"
    PREPARE_PEN = "PREPARE_PEN"
    TAME_WOLF = "TAME_WOLF"
    HERD_ANIMAL = "HERD_ANIMAL"
    BREED_ANIMALS = "BREED_ANIMALS"
    HUNT = "HUNT"
    DECORATE = "DECORATE"
    CRAFT_ITEM = "CRAFT_ITEM"
    SMELT_ITEM = "SMELT_ITEM"
    DEPOSIT_ITEM = "DEPOSIT_ITEM"
    WITHDRAW_ITEM = "WITHDRAW_ITEM"
    EAT = "EAT"
    SLEEP = "SLEEP"
    SHELTER = "SHELTER"
    HAND_OVER = "HAND_OVER"
    MAKE_PATH = "MAKE_PATH"
    BUILD_BLUEPRINT = "BUILD_BLUEPRINT"
    DELIVER_ITEMS = "DELIVER_ITEMS"


class TaskOutcome(str, Enum):
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    IN_PROGRESS = "IN_PROGRESS"
    CANCELLED = "CANCELLED"


class EventType(str, Enum):
    CITIZEN_SPAWNED = "CITIZEN_SPAWNED"
    CITIZEN_DIED = "CITIZEN_DIED"
    RESOURCE_DISCOVERED = "RESOURCE_DISCOVERED"
    STORAGE_REGISTERED = "STORAGE_REGISTERED"
    TASK_STARTED = "TASK_STARTED"
    TASK_COMPLETED = "TASK_COMPLETED"
    TASK_FAILED = "TASK_FAILED"
    PROJECT_CREATED = "PROJECT_CREATED"
    PROJECT_COMPLETED = "PROJECT_COMPLETED"
    BUILDING_COMPLETED = "BUILDING_COMPLETED"
    STORAGE_LOW = "STORAGE_LOW"
    STORAGE_FULL = "STORAGE_FULL"
    CONTRACT_COMPLETED = "CONTRACT_COMPLETED"
    TECHNOLOGY_LEARNED = "TECHNOLOGY_LEARNED"
    SKILL_FAILED = "SKILL_FAILED"


class Priority(str, Enum):
    LOW = "LOW"
    NORMAL = "NORMAL"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


PRIORITY_ORDER = {Priority.LOW: 3, Priority.NORMAL: 2, Priority.HIGH: 1, Priority.CRITICAL: 0}


# --------------------------------------------------------------------------- citizen

class CitizenPersonality(BaseModel):
    curiosity: float = Field(0.5, ge=0.0, le=1.0)
    ambition: float = Field(0.5, ge=0.0, le=1.0)
    sociability: float = Field(0.5, ge=0.0, le=1.0)
    patience: float = Field(0.5, ge=0.0, le=1.0)
    risk_tolerance: float = Field(0.5, ge=0.0, le=1.0)
    cooperation: float = Field(0.5, ge=0.0, le=1.0)
    creativity: float = Field(0.5, ge=0.0, le=1.0)


class CitizenSkills(BaseModel):
    mining: float = Field(0.0, ge=0.0, le=100.0)
    farming: float = Field(0.0, ge=0.0, le=100.0)
    building: float = Field(0.0, ge=0.0, le=100.0)
    crafting: float = Field(0.0, ge=0.0, le=100.0)
    logistics: float = Field(0.0, ge=0.0, le=100.0)
    redstone: float = Field(0.0, ge=0.0, le=100.0)
    architecture: float = Field(0.0, ge=0.0, le=100.0)
    research: float = Field(0.0, ge=0.0, le=100.0)
    trading: float = Field(0.0, ge=0.0, le=100.0)


class CitizenRegister(BaseModel):
    """POST /v1/citizens/register — sent once when a citizen entity spawns."""
    citizen_id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=48)
    profession: str = "UNASSIGNED"
    position: Optional[str] = None  # "x,y,z"
    personality: Optional[CitizenPersonality] = None
    skills: Optional[CitizenSkills] = None
    employer_id: Optional[str] = None
    company_id: Optional[str] = None


class CitizenStateUpdate(BaseModel):
    health: Optional[float] = Field(None, ge=0.0, le=1024.0)
    hunger: Optional[float] = Field(None, ge=0.0, le=20.0)
    energy: Optional[float] = Field(None, ge=0.0, le=1.0)
    status: Optional[str] = None
    current_goal: Optional[str] = None
    current_task: Optional[str] = None
    position: Optional[str] = None
    profession: Optional[str] = None


class CitizenInfo(BaseModel):
    """GET /v1/citizens/{id}"""
    citizen_id: str
    name: str
    profession: str
    status: str
    current_goal: Optional[str] = None
    current_task: Optional[str] = None
    position: Optional[str] = None
    health: float
    hunger: float
    energy: float
    personality: CitizenPersonality
    skills: CitizenSkills
    memories: list[str] = []
    created_at: datetime
    last_decision_at: Optional[datetime] = None


# --------------------------------------------------------------------------- observation

class NearbyStorage(BaseModel):
    id: str
    distance: float
    position: Optional[str] = None


class NearbyResource(BaseModel):
    type: str
    distance: float
    # only legitimately discovered/visible resources are ever included
    position: Optional[str] = None


class CivilizationSummary(BaseModel):
    population: int = 0
    food_reserve: int = 0
    active_projects: int = 0
    day: int = 0
    known_storage: int = 0
    bottlenecks: list[str] = []
    # Largest stockpiles in the settlement's registered containers. Lets a
    # policy answer "do we already own this?" before sending anyone mining.
    stock: dict[str, int] = {}
    # What the settlement physically is, as opposed to what it owns. Without
    # these a policy cannot tell a colony that has built a town from one that
    # has merely accumulated a very large pile of logs.
    buildings_complete: int = 0
    buildings_underway: int = 0
    districts: int = 0
    roads_known: int = 0
    roads_built: int = 0
    signs: int = 0
    # Share of citizens doing something useful, 0..100. The single number that
    # says whether the colony is working or waiting.
    productive_percent: int = 100
    citizens_idle: int = 0
    citizens_stuck: int = 0
    citizens_lost: int = 0
    # Materials an unfinished building is waiting for, as "24x oak planks".
    awaiting_materials: list[str] = []


class ObservationCitizen(BaseModel):
    name: str
    profession: str = "UNASSIGNED"
    health: float = 20.0
    # 0..100, where 100 is full and below ~15 is starving. NOT the vanilla
    # 0..20 food bar: the mod tracks its own finer-grained scale, and reading
    # this as 0..20 makes every hunger rule fire only at death's door.
    hunger: float = 100.0
    energy: float = 1.0


class ColonyZoneRef(BaseModel):
    type: str
    distance: int = 0
    id: Optional[str] = None
    center: Optional[list[int]] = None
    # FOREST zones only: the tree species the colony has brought home and replants
    species: list[str] = []


class KnownPlace(BaseModel):
    """Somewhere the colony remembers: a workbench, a furnace, a machine.

    Shared across every citizen, so one citizen's workshop is everyone's — and
    unbounded in distance, because "far away" is a problem for the walk, not
    for the memory.
    """

    count: int = 0
    distance: Optional[int] = None
    nearest: Optional[list[int]] = None


class ColonySpace(BaseModel):
    """Where the citizen stands in relation to its colony.

    Without this a citizen cannot tell that it has wandered two hundred blocks
    from home, nor where its trade is supposed to be practised.
    """

    center: Optional[list[int]] = None
    distance_from_center: int = 0
    town_radius: int = 0
    in_zone: Optional[str] = None
    work_zone: Optional[ColonyZoneRef] = None
    zones: list[ColonyZoneRef] = []
    # Workstations, containers and machinery the colony knows the location of,
    # keyed by kind (CRAFTING_TABLE, FURNACE, STONECUTTER, MECHANISM, ...).
    known_places: dict[str, KnownPlace] = {}


class Observation(BaseModel):
    """Compact observation — never a raw world dump. Token efficiency matters."""

    model_config = ConfigDict(extra="forbid")

    colony: ColonySpace = Field(default_factory=ColonySpace)
    citizen: ObservationCitizen
    position: Optional[list[int]] = Field(None, min_length=3, max_length=3)
    current_goal: Optional[str] = None
    current_task: Optional[str] = None
    task_failures: int = 0
    inventory: dict[str, int] = Field(default_factory=dict)
    nearby: dict[str, list[Any]] = Field(default_factory=dict)
    civilization: CivilizationSummary = Field(default_factory=CivilizationSummary)
    recent_events: list[str] = Field(default_factory=list)
    known_memories: list[str] = Field(default_factory=list)

    @field_validator("inventory")
    @classmethod
    def _nonneg_counts(cls, v: dict[str, int]) -> dict[str, int]:
        for k, n in v.items():
            if not k or ":" not in k:
                raise ValueError(f"invalid item id: {k!r}")
            if n < 0:
                raise ValueError(f"negative count for {k}")
        return v


# --------------------------------------------------------------------------- decisions

class Goal(BaseModel):
    type: GoalType
    resource: Optional[str] = None
    target_quantity: Optional[int] = Field(None, ge=0, le=1_000_000)
    project_id: Optional[str] = None
    description: Optional[str] = Field(None, max_length=280)

    @field_validator("resource")
    @classmethod
    def _item_id(cls, v: Optional[str]) -> Optional[str]:
        if v is not None and (":" not in v or " " in v):
            raise ValueError(f"invalid resource id: {v!r}")
        return v


class Task(BaseModel):
    type: TaskType
    resource: Optional[str] = None
    quantity: Optional[int] = Field(None, ge=1, le=100_000)
    target: Optional[str] = None        # storage id, project id, citizen id...
    block: Optional[str] = None         # block id for BUILD/PLANT
    position: Optional[list[int]] = Field(None, min_length=3, max_length=3)
    project_id: Optional[str] = None

    @field_validator("resource", "block")
    @classmethod
    def _id(cls, v: Optional[str]) -> Optional[str]:
        if v is not None and (":" not in v or " " in v):
            raise ValueError(f"invalid id: {v!r}")
        return v


class Decision(BaseModel):
    """The ONLY shape the LLM may return. Anything else is rejected."""

    model_config = ConfigDict(extra="forbid")

    reasoning_summary: str = Field(min_length=1, max_length=600)
    goal: Goal
    tasks: list[Task] = Field(default_factory=list, max_length=12)

    @field_validator("tasks")
    @classmethod
    def _at_least_nothing_silly(cls, v: list[Task]) -> list[Task]:
        # A non-idle goal must come with at least one task.
        return v


class DecisionRequest(BaseModel):
    observation: Observation
    priority: Priority = Priority.NORMAL
    reason: str = Field(default="periodic", max_length=120)  # why cognition was triggered


class DecisionResponse(BaseModel):
    decision: Decision
    accepted: bool = True
    reject_reason: Optional[str] = None
    provider: str
    model: Optional[str] = None
    latency_ms: float
    queued_behind: int = 0


# --------------------------------------------------------------------------- task results

class SkillFailure(BaseModel):
    code: str = Field(max_length=64)
    message: str = Field(max_length=500)
    recoverable: bool = True


class TaskResult(BaseModel):
    task_type: TaskType
    outcome: TaskOutcome
    skill: Optional[SkillType] = None       # which deterministic skill reported this
    failure: Optional[SkillFailure] = None
    progress: float = Field(0.0, ge=0.0, le=1.0)
    details: dict[str, Any] = Field(default_factory=dict)


# --------------------------------------------------------------------------- events / projects

class EventIn(BaseModel):
    type: EventType
    civilization_id: str = "default"
    citizen_id: Optional[str] = None
    payload: dict[str, Any] = Field(default_factory=dict)
    created_at: Optional[datetime] = None


class ProjectCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    blueprint_id: Optional[str] = None
    origin: Optional[str] = None  # "x,y,z"
    civilization_id: str = "default"


class ProjectInfo(BaseModel):
    id: str
    name: str
    blueprint_id: Optional[str]
    status: str
    origin: Optional[str]
    progress: float
    requirements: dict[str, int]      # BOM total
    provided: dict[str, int]          # gathered so far
    missing: dict[str, int]
    workers: list[str]
    created_at: datetime


class TechnologyInfo(BaseModel):
    id: str
    name: str
    category: str
    description: str
    inputs: list[str]
    outputs: list[str]
    mechanisms: list[str]
    blueprint_ids: list[str]
    verified: bool
    current_version: int


class CivilizationState(BaseModel):
    civilization_id: str
    name: str
    seed: int
    population: int
    day: int
    active_projects: int
    completed_projects: int
    companies: int
    technologies: int
    stored_resources: dict[str, int] = {}
    accounts: dict[str, float] = {}
    bottlenecks: list[str] = []
    recent_events: list[dict[str, Any]] = []


class HealthResponse(BaseModel):
    status: Literal["ok", "degraded"]
    version: str
    provider: str
    provider_available: bool
    model: Optional[str] = None
    queue_depth: int = 0
    decisions_last_minute: int = 0
    uptime_seconds: float = 0.0
