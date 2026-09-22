# MineCivilization AI — Implementation Plan

Date: 2026-09-21 (status updated 2026-09-22)
Target: Apple Silicon (M2, 16 GB), macOS, Minecraft Java 1.21.1, Forge 52.1.16, Java 21, Python 3.12+, Ollama (local only).

## Status (2026-09-22)

| Area | State |
|---|---|
| Phase 0 — Inspect | **done** |
| Phase 1 — Forge foundation | **done** (`./gradlew build` green) |
| Phase 2 — CitizenEntity | **done** (entity, identity, NBT, `/mciv citizen …`) |
| Phase 3 — Skill engine | **done** (all 18 skills implemented — incl. `CRAFT_ITEM`/`SMELT_ITEM`, no `NOT_IMPLEMENTED` stubs left — state machine, navigator) |
| Phase 4 — Local AI service | **done** (FastAPI + SQLite, all `/v1` routes, 55 pytest tests) |
| Phase 5 — Forge ↔ AI bridge | **done** (`AiBridge`: async HTTP, circuit breaker, main-thread queue; HTTP/1.1 forced — the default h2c upgrade was rejected by uvicorn and tripped the breaker, fixed 2026-09-22) |
| Combat & navigation | **done** — local self-defence reflex (`CombatPolicy`: personality-scaled radius, attacker retaliation, cooldown, bounded chase), reachability-aware block search + approach-fallback navigation (fixed the stuck-in-trees `TARGET_UNREACHABLE` loop), `nearby.hostiles` in observations, mock loop-breaker hardened |
| Testing | **done** — 57 Java JUnit tests (plain, no MC bootstrap) + 55 Python tests + `scripts/smoke-test.sh` |
| Phases 6–8 — Vertical slice | partial (construction/storage plumbing present; end-to-end gather→deposit loop to validate in-game) |
| Phases 9–11+ — Ollama multi-citizen, schematics, dashboard, economy, R&D | pending |
| Tooling | **done** — one-command launcher `./run.sh` (client/server/build/test/service/help), `scripts/smoke-test.sh`, root `README.md` |

Everything runs offline by default (`MINECIV_LLM_PROVIDER=mock`); Ollama is opt-in.

## Phase 0 — Inspect (done)

- Repository was empty (fresh git repo).
- Host: macOS arm64, Java 17 only → installed local Temurin JDK 21 at `~/.local/jdk-21` (no sudo).
- Python 3.13/3.12 available via Homebrew; Node 24 available; Ollama not installed (scripts detect and advise).
- Forge 1.21.1 latest = **52.1.16** (verified from Forge promotions).
- Official Forge MDK extracted into `mod/` (ForgeGradle 7, Gradle 9.3.1, official mappings).

## Phase 1 — Forge foundation

- Adapt MDK: mod id `minecivilization`, group `ai.minecivilization`, name `MineCivilization AI`.
- Get `./gradlew build` green before adding gameplay code. **Compilation is the source of truth.**

## Phase 2 — CitizenEntity

- `CitizenEntity` (extends `PathfinderMob`): persistent UUID `citizenId`, identity/state/personality/skills data
  stored via `SynchedEntityData` (lightweight) + NBT save/load. Full AI memory stays in the Python service.
- Humanoid renderer reusing vanilla player-like model via `PlayerRenderer`-style layer or `HumanoidMobRenderer`;
  custom name above head (`ALex` / profession two-line via `renderNameTag` override).
- `/mciv citizen spawn|list|inspect|think|stop` commands (Forge `Commands` registration, client+server).

## Phase 3 — Deterministic skill engine

- `CitizenSkill` interface: `canStart/start/tick/cancel`, `SkillResult` (RUNNING/COMPLETED/FAILED),
  structured `SkillFailure{code,message,recoverable}`, timeout + retry policy.
- Skills: IDLE, MOVE_TO, FOLLOW, FIND_BLOCK, MINE_BLOCK, MINE_AREA, PICKUP_ITEM, PLACE_BLOCK,
  HARVEST_CROP, PLANT_CROP, CRAFT_ITEM, SMELT_ITEM, DEPOSIT_ITEM, WITHDRAW_ITEM, EAT, SLEEP,
  BUILD_BLUEPRINT, DELIVER_ITEMS — **all 18 implemented** (CRAFT via vanilla `RecipeManager`
  recipes with atomic ingredient payment; SMELT drives a real furnace: input, fuel via
  `ForgeHooks.getBurnTime`, result collection).
- `SkillStateMachine` on the entity: GOAL → TASK → SKILL, cheap per-tick checks only, no I/O in tick.
- `CitizenNavigator` wrapping vanilla `PathNavigation`: timeout, repath, stuck detection, unreachable → failure.

## Phase 4 — Local AI service (Python)

- FastAPI + SQLModel + SQLite (WAL) + httpx + pytest; binds `127.0.0.1` only; bearer token auth.
- Endpoints: `/health`, `/v1/citizens/register|observe|decision|task-result`, `/v1/citizens/{id}`,
  `/v1/events`, `/v1/civilization/state`, `/v1/projects` (GET/POST), `/v1/technologies`.
- `LLMProvider` Protocol → `OllamaProvider`, `MockProvider` (deterministic, used in tests/CI).
- `CognitionScheduler`: priority queue, `MAX_CONCURRENT_LLM_REQUESTS=1`, rate limit/min, backpressure.
- Memory: working/episodic/semantic/social in SQLite; text search + relevance scoring (no vector DB in MVP).
- Economy: append-only `transactions` ledger; balances derived. Companies tables modeled.
- Schematics: safe `.nbt`/`.schem` import behind size/depth/dimension/block-count limits (stub parser in V1,
  full parser in milestone 3).

## Phase 5 — Forge ↔ AI bridge

- Java `AiClient` using Java 21 `java.net.http.HttpClient` (async), short timeouts, bearer token.
- `CircuitBreaker` (3 consecutive failures → degraded, periodic health probe).
- Responses validated against a strict Java-side schema (`DecisionResponse.parse`, unknown action → reject).
- All world mutations enqueued to the server thread (`ServerTickEvent` drain); HTTP threads never touch world state.
- AI offline → citizens finish current deterministic work, then idle. Minecraft never blocks.

## Phase 6–8 — Vertical slice

1. **Gather**: LLM (or mock) issues `GATHER minecraft:oak_log 16` → FIND_BLOCK/MINE_BLOCK/PICKUP loop.
2. **Storage**: registered `StorageNode`s (chests); DEPOSIT_ITEM via container inventory, physically real items.
3. **Blueprint**: `Blueprint` (relative block states), `ConstructionProject` lifecycle
   PROPOSED→PLANNED→WAITING_FOR_RESOURCES→BUILDING→COMPLETED, BOM from palette, builders consume items
   from inventory/storage. Built-in original **Starter Warehouse** (~9×7×6) generated by code.

## Phase 9–10 — Ollama + multi-citizen

- Swap `LLM_PROVIDER=ollama`; startup detection, model listing, graceful fallback to mock.
- 5-citizen scenario script; civilization-level planning every 5–15 min (separate scope from citizen cognition).

## Phase 11+ — Schematics, dashboard, economy, R&D (after slice works)

## Testing

- Python: 55 pytest tests for schemas, API routes, ledger, scheduler, memory retrieval, mock
  provider (incl. CRAFT continuation/planking policy and the task-failure loop-breaker),
  malformed LLM output, integration flows.
- Java: 57 plain JUnit tests (no Minecraft bootstrap) for decision-schema parsing
  (`CitizenPlan`, `AiBridge` responses), blueprint BOM/placement order, starter warehouse,
  construction project lifecycle, skill registry completeness, the combat/reflex policy
  (`CombatPolicy`), and block reachability / approach-search geometry — run by `./gradlew test`.
- Integration smoke test: `scripts/smoke-test.sh` boots the real service on an isolated scratch
  port (`MCIV_SMOKE_PORT`, default 8766), checks `/health`, authorized and unauthorized requests.
  `./run.sh test` runs everything headlessly: build + Java tests + Python tests + smoke test.

## Non-negotiables

- No cloud APIs, no CUDA, no Docker requirement, no per-tick LLM calls, no schematic pasting,
  no free resources, no world mutation off the server thread, no raw world dumps to the LLM.
