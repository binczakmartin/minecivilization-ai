# MineCivilization AI Forge

An offline-first Minecraft **Forge 1.21.1** mod where NPC citizens are driven by a
local **AI service** (FastAPI + SQLite). Citizens observe the world, ask the service
for decisions, execute tasks, learn skills, and report results back — no cloud APIs,
everything runs on `127.0.0.1`.

```
┌─────────────────────────┐   HTTP (localhost:8765)   ┌──────────────────────────────┐
│  Minecraft (Forge mod)  │ ────────────────────────▶ │  AI service (FastAPI)        │
│  citizens · brain ·     │  register / observe /     │  decisions · memory · ledger │
│  skills · commands      │  decide / task-result     │  projects · mock or Ollama   │
└─────────────────────────┘                           └──────────────────────────────┘
```

---

## Quick start — one command

From the repository root:

```bash
./run.sh          # same as ./run.sh client
```

That's it. `run.sh` will, as needed:

1. detect a **JDK 21** (checks `JAVA_HOME`, `~/.local/jdk-21`, `/usr/libexec/java_home`),
2. create the Python **virtualenv** (`ai-service/.venv`) and install the service,
3. bootstrap a `.env` from `.env.example` (first run),
4. start the **AI service** in the background and wait for `/health`,
5. launch the Minecraft **client** (or server / build / tests),
6. stop the service cleanly when Minecraft exits.

> The **first client launch downloads vanilla assets** through Mojang (one-time);
> after that everything works offline.

### All commands

```bash
./run.sh client    # start AI service + Minecraft client   (default dev loop)
./run.sh server    # start AI service + dedicated server
./run.sh build     # build the mod jar → mod/build/libs/
./run.sh test      # gradle build + Java tests + pytest + API smoke test
./run.sh service   # start only the AI service (blocks; Ctrl+C stops it)
./run.sh help      # usage
```

Underlying tools (run from `mod/`): `./gradlew build`,
`./gradlew runClient`, `./gradlew runServer`.
Underlying tools (run from `ai-service/`): `.venv/bin/pytest -q tests`.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| **JDK 21** | Minecraft 1.21.1 requires Java 21. `run.sh` auto-detects common locations. |
| **Python 3.11+** | For the AI service (`run.sh` creates the venv for you). |
| **Network (first run only)** | Gradle dependencies + vanilla client assets. |

---

## Configuration

All configuration is environment-driven. On first run `run.sh` creates **`.env`**
from [`.env.example`](.env.example); edit it to taste. It is loaded automatically
for every command.

### AI service (Python)

| Variable | Default | Purpose |
|---|---|---|
| `MCIV_HOST` | `127.0.0.1` | Bind address — local only, never expose publicly. |
| `MCIV_PORT` | `8765` | API port. |
| `MINECIV_API_TOKEN` | *(from .env)* | Bearer token every route except `/health` requires. |
| `MCIV_DATABASE_URL` | `sqlite:///data/minecivilization.db` | SQLAlchemy URL (relative to the repo root). |
| `MCIV_SEED` | `42` | Deterministic seed for procedural content. |
| `MINECIV_LLM_PROVIDER` | `mock` | `mock` (offline, deterministic) or `ollama` (local LLM). |
| `OLLAMA_URL` | `http://127.0.0.1:11434` | Ollama endpoint (only when provider = `ollama`). |
| `OLLAMA_MODEL` | `qwen2.5:3b-instruct-q4_K_M` | Ollama model tag. |

The service is started **from the repo root**, so relative paths like
`data/minecivilization.db` resolve inside the repository.

### Minecraft mod (Java)

System properties are read first, then environment variables, then defaults.
`mod/build.gradle` forwards environment variables into the `runClient`/`runServer`
Gradle runs automatically.

| System property | Environment variable | Default | Purpose |
|---|---|---|---|
| `minecivilization.ai.baseUrl` | `MINECIV_AI_BASE_URL` | `http://127.0.0.1:8765` | Where the mod finds the AI service. |
| `minecivilization.ai.token` | `MINECIV_API_TOKEN` | *(empty)* | Bearer token (must match the service). |

The mod also exposes in-game config values (Forge common config, file
`config/minecivilization-common.toml` — also editable live via `/config`) for
AI enablement, debug logging, polling intervals and combat:

| Config key | Default | Purpose |
|---|---|---|
| `ai.enabled` | `true` | Master switch for AI-driven decisions. |
| `ai.timeoutMs` | `8000` | HTTP timeout toward the AI service. |
| `ai.decisionCooldownTicks` | `400` | Ticks between service decision requests. |
| `skills.timeoutTicks` / `skills.moveTimeoutTicks` / `skills.maxRepaths` | `1200` / `200` / `3` | Skill timeouts, bounded repaths → `TARGET_UNREACHABLE`. |
| `combat.enabled` | `true` | Deterministic self-defence against hostile mobs. |
| `combat.triggerRadius` | `12.0` | Base engagement radius (×0.5–1.5 by personality risk tolerance). |
| `combat.attackIntervalTicks` | `16` | Ticks between melee attacks. |
| `combat.chaseTimeoutTicks` | `200` | Ticks without a landed hit before an unreachable chase is abandoned. |
| `simulation.seed` | `42` | Personality/identity derivation seed. |
| `debug` | `false` | Verbose mod logging (same as `/mciv debug on`). |

---

## AI service API

Base URL: `http://127.0.0.1:8765`. All routes below `/v1` require
`Authorization: Bearer $MINECIV_API_TOKEN`.

| Method & path | Purpose |
|---|---|
| `GET /health` | Liveness + provider status (**no auth**). |
| `POST /v1/citizens/register` | Register a citizen joining the world. |
| `GET /v1/citizens/{id}` | Fetch a citizen's profile & state. |
| `POST /v1/citizens/{id}/observe` | Push an observation (world tick context). |
| `POST /v1/citizens/{id}/decision` | Ask for the next decision/goal. |
| `POST /v1/citizens/{id}/task-result` | Report task success/failure (skill XP, ledger). |
| `GET /v1/events` · `POST /v1/events` | Read / ingest world events. |
| `GET /v1/civilization/state` | Full civilization snapshot (citizens, stocks, tech). |
| `GET /v1/projects` · `POST /v1/projects` | List / create construction projects. |
| `GET /v1/technologies` | Research tree state. |
| `GET /v1/metrics` | Counters: decisions, latency, errors. |

Example:

```bash
source .env
curl -s http://127.0.0.1:8765/health
curl -s -H "Authorization: Bearer $MINECIV_API_TOKEN" \
     http://127.0.0.1:8765/v1/civilization/state
```

See [`ai-service/README.md`](ai-service/README.md) for request/response schemas.

---

## In-game commands

Permission level 2+ (cheats / op):

```
/mciv citizen spawn                    spawn a citizen at your position
/mciv citizen list                     list citizens (pos, hunger, status, goal)
/mciv citizen inspect <name|id>        full diagnostic: brain, skills, inventory, bridge
/mciv citizen think <name|id>          force a fresh AI decision now
/mciv citizen stop <name|id>           stop current work (re-enable with think)
/mciv ai status                        AI bridge status, base URL, pending tasks
/mciv ai reconnect                     reset the circuit breaker & re-probe the service
/mciv debug on|off                     toggle verbose mod logging
```

Citizen names are tab-completed from the live population.

---

## Skills citizens can execute

The mod ships **18 skills**, all implemented (no `NOT_IMPLEMENTED` stubs left):

```
IDLE · MOVE_TO · FOLLOW · FIND_BLOCK · MINE_BLOCK · MINE_AREA · PICKUP_ITEM
PLACE_BLOCK · HARVEST_CROP · PLANT_CROP · CRAFT_ITEM · SMELT_ITEM
DEPOSIT_ITEM · WITHDRAW_ITEM · EAT · SLEEP · BUILD_BLUEPRINT · DELIVER_ITEMS
```

`CRAFT_ITEM` uses real vanilla recipes (`RecipeManager`, 2×2 anywhere / 3×3 needs a
crafting table, atomic ingredient payment) and `SMELT_ITEM` runs a real furnace
(input + fuel + result collection, burn time via `ForgeHooks.getBurnTime`).

The cognition loop is `GOAL → TASK → SKILL`: the service returns a goal/decision,
the mod's `TaskExecutor` breaks it into tasks, and the skill state machine executes
them with timeouts, stuck detection, and structured failures that are reported back
(`task-result`) for skill XP and memory updates.

Beyond decisions, citizens run **deterministic local reflexes** that never wait
for the AI service:

- **Eating** when hunger drops (real food stacks, nutrition table).
- **Self-defence**: every 5th tick they scan for hostile mobs inside a
  personality-scaled radius (`combat.triggerRadius` × 0.5–1.5 × risk tolerance)
  or against whatever just hurt them (16-block attacker leash — even while
  starving), chase with throttled repaths, swing on a cooldown, and abandon an
  unreachable chase after `combat.chaseTimeoutTicks`. Kills are recorded in
  memory (`defeated:<type>`) and emitted as brain events; the current fight
  shows in `/mciv citizen inspect` as `combat=fighting <type>`.
- **Reachability-aware work**: block searches only accept blocks the citizen
  could actually stand beside or on top of (no logs buried in a leaf crown, no
  sealed-in furnace), and for solid targets the navigator lands on a real
  stand position around the block instead of pathing into it — this is what
  fixed the old *"stuck in the trees" / `TARGET_UNREACHABLE` loop*.

---

## Testing

```bash
./run.sh test
```

runs, in order:

1. **Gradle** compile + **70 JUnit tests** of the mod (plan parsing, decision schema,
   blueprints/BOM, starter warehouse, starter house, construction projects, skill registry,
   combat/reflex policy, block reachability & approach-search geometry),
2. **pytest** — **67 tests** covering schemas, API routes, memory, ledger, scheduler,
   mock provider (incl. CRAFT policy and the failure loop-breaker), and integration
   flows (in-memory SQLite, forced offline mock),
3. **smoke test** ([`scripts/smoke-test.sh`](scripts/smoke-test.sh)) — boots the real
   service on an isolated scratch port (`MCIV_SMOKE_PORT`, default 8766, so it never
   clashes with a dev instance on `MCIV_PORT`), checks `/health`, authorized and
   unauthorized requests.

Run pieces individually:

```bash
cd ai-service && .venv/bin/pytest -q tests     # Python suite only
./scripts/smoke-test.sh                        # end-to-end API smoke test
cd mod && ./gradlew build                      # mod build only
```

---

## Project layout

```
minecivilization-ai/
├── run.sh                     # one-command launcher (client/server/build/test/service)
├── .env.example               # configuration template → copied to .env
├── scripts/smoke-test.sh      # end-to-end API smoke test
├── mod/                       # Forge 1.21.1 mod (Java 21, Gradle)
│   └── src/main/java/ai/minecivilization/
│       ├── MineCivilizationAiForge.java   # mod entrypoint
│       ├── config/ModConfig.java          # env/system-property resolution
│       ├── entity/                        # CitizenEntity, CitizenIndex, brain
│       ├── citizen/                       # identity, needs, inventory, professions
│       ├── skills/                        # 18 skill implementations (incl. CRAFT/SMELT)
│       ├── combat/CombatPolicy.java       # deterministic self-defence rules
│       ├── construction/                  # build sites & project goals
│       ├── network/AiBridge.java          # HTTP client, circuit breaker, main-thread queue
│       ├── commands/McivCommands.java     # /mciv …
│       └── registry/                      # entities, items, blocks, sounds
│   └── src/test/java/…                    # 70 JUnit tests (plain, no MC bootstrap)
├── ai-service/                # local AI service (Python, FastAPI)
│   ├── src/minecivilization_ai/
│   │   ├── main.py                         # app factory
│   │   ├── config.py                       # pydantic settings (env aliases)
│   │   ├── api/routes.py                   # /health + /v1/…
│   │   ├── domain/                         # citizens, memory, ledger, tech, projects
│   │   ├── cognition/                      # decision engine, prompts, providers
│   │   ├── providers/                      # mock (offline) & ollama adapters
│   │   └── db/                             # SQLAlchemy + SQLite
│   └── tests/                              # 67 pytest tests
└── docs/IMPLEMENTATION_PLAN.md # phased roadmap & status
```

---

## Offline by design

- Default provider is **`mock`**: fully deterministic decisions, zero external calls.
- Switch to a **local Ollama** model when you want richer cognition — still no cloud:

  ```bash
  # .env
  MINECIV_LLM_PROVIDER=ollama
  OLLAMA_MODEL=qwen2.5:3b-instruct-q4_K_M
  ```

  The service refuses non-local providers; `allow_internet` stays off unless you
  explicitly enable it.
- The service binds `127.0.0.1` only, and every `/v1` route is bearer-token protected.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `No JDK 21 found` | Install a JDK 21 and set `JAVA_HOME`, or put it in `~/.local/jdk-21`. |
| `GLFW may only be used on the main thread … -XstartOnFirstThread` | macOS-only; already fixed in `mod/build.gradle` (the `client` run appends the flag on macOS). If you still see it, make sure you pulled the latest `build.gradle` and re-run — do **not** add the flag on Linux/Windows, those JVMs reject it. |
| `/mciv ai status` → `DEGRADED … lastError=register HTTP 400` | The mod's Java HTTP client now forces HTTP/1.1 (`AiBridge`): Java's default HTTP/2 cleartext upgrade (`Upgrade: h2c`) is rejected by the service, which trips the circuit breaker. Rebuild (`./run.sh build`) and restart the client; then `/mciv ai reconnect`. |
| Citizens idle, service healthy, `total_decisions: 0` | Usually the row above (bridge degraded). Check `/mciv ai status`, then `mod/run/logs/latest.log` for `[Circuit]` lines. |
| Mod can't reach AI service | Check `/health`: `curl http://127.0.0.1:8765/health`. Then `/mciv ai status` in-game. |
| `401/403` from the API | `MINECIV_API_TOKEN` mismatch — same `.env` is used by both sides; restart `./run.sh service` after edits. |
| Service won't start (port in use) | Change `MCIV_PORT` in `.env`, and mirror it in `MINECIV_AI_BASE_URL`. |
| First `runClient` is slow | Vanilla assets are being downloaded once; subsequent launches are offline. |
| Citizens idle / no decisions | `/mciv citizen think <name>` forces a decision; `./run.sh service` must be running. |
| Python deps broken | `rm -rf ai-service/.venv && ./run.sh service` rebuilds it. |
| Logs | Service: `ai-service.log` (repo root). Mod/server: console + `mod/run/logs/`. |

---

## Roadmap

Current status and the full phased plan live in
[`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) — phases cover world
seeding → citizen lifecycle → cognition loop → skills/construction → society systems
(ledger, tech tree, projects) → tooling. In short: **phases 0–5 are done** (including
CRAFT/SMELT and the Java + Python test suites), the vertical slice is partially
wired (gather → deposit → build loop to validate in-game), and Ollama multi-citizen,
schematics, dashboard, economy, and R&D are pending. Check that doc for details.
