# MineCivilization AI — Implementation Plan

Date: 2026-09-21 (status updated 2026-09-22)
Target: Apple Silicon (M2, 16 GB), macOS, Minecraft Java 1.21.1, Forge 52.1.16, Java 21, Python 3.12+, Ollama (local only).

## Status (2026-09-22)

| Area | State |
|---|---|
| Phase 0 — Inspect | **done** |
| Phase 1 — Forge foundation | **done** (`./gradlew build` green) |
| Phase 2 — CitizenEntity | **done** (entity, identity, NBT, `/mciv citizen …`) |
| Phase 3 — Skill engine | **done** (all 19 skills implemented — incl. `TRAVERSE`/`CRAFT_ITEM`/`SMELT_ITEM`, no `NOT_IMPLEMENTED` stubs left — state machine, navigator) |
| Intelligence foundations | **done 2026-09-23** — (a) *terrain modification*: `TerrainPlanner` A* with bridge/tunnel/pillar moves, `ScaffoldMaterial`, `LevelBlockView`, `TraverseSkill`; `TaskExecutor` escalates `TARGET_UNREACHABLE` to `TRAVERSE` instead of failing the task. (b) *recursive crafting*: `CraftPlanner` + `VanillaRecipeSource` resolve a recipe tree to raw materials (fuel per batch, ingredient sets with lookahead, cycle backtracking, gather hoisting, bounded search); `CRAFT` tasks run each step in a nested `TaskExecutor`. Mock policy reaches the iron tier as a single goal. |
| Phase 4 — Local AI service | **done** (FastAPI + SQLite, all `/v1` routes, 67 pytest tests) |
| Phase 5 — Forge ↔ AI bridge | **done** (`AiBridge`: async HTTP, circuit breaker, main-thread queue; HTTP/1.1 forced — the default h2c upgrade was rejected by uvicorn and tripped the breaker, fixed 2026-09-22) |
| Combat & navigation | **done** — local self-defence reflex (`CombatPolicy`: personality-scaled radius, attacker retaliation, cooldown, bounded chase), reachability-aware block search + approach-fallback navigation (fixed the stuck-in-trees `TARGET_UNREACHABLE` loop), `nearby.hostiles` in observations, mock loop-breaker hardened |
| Testing | **done** — 319 Java JUnit tests (plain, no MC bootstrap) + 109 Python tests + `scripts/smoke-test.sh` |
| Phases 6–8 — Vertical slice | partial (construction/storage plumbing present; auto-planned **Starter House** camp row anchored on the player's bed — world spawn only before the first bed — + `PLACE` task landed 2026-09-22; end-to-end gather→deposit loop to validate in-game) |
| Sorted warehouse | **done 2026-09-23** — `StorageDiscovery` (place events + tick-sliced camp sweep + pruning) fixes the latent bug that **nothing ever registered a container**, so `known_storage` was always 0 and deliveries were no-ops. `ItemCategory` + chest claiming produce an emergent sorted warehouse; `DeliverItemsSkill` files a bag shelf by shelf; `DeliveryPolicy` keeps a personal food/scaffold reserve. `SettlementStock` reads chests as the source of truth, feeds `civilization.stock` in observations, and lets `CraftPlanner` emit WITHDRAW steps instead of re-mining. `/mciv storage` inspects it. |
| Spatial colony | **done 2026-09-23** — `ZoneLayout`/`ZonePlanner`/`ZoneManager`: districts on a town grid with streets, allotted as the population grows (civic core → warehouses/housing → fields/workshops → forestry/mining). `ZoneType.forProfession` gives every trade a place. Observations carry distance from centre, current district and work zone; citizens over 220 blocks out walk home by themselves via TRAVERSE. `/mciv colony`. |
| Forestry | **done 2026-09-23** — `FELL_TREE` takes the whole connected trunk (canopy test refuses to "fell" a log cabin), pillars up to high branches via TRAVERSE, replants a sapling on the stump, and reports the species to the managed forest. |
| Population | **done 2026-09-23** — `Population` (pure, tested) gates births on food in store, a spare bed and a cooldown; `ColonyLife` pays the food cost out of real containers and spawns; `ColonyCensus` counts beds and registers containers in one sweep; `ColonyData` persists births/deaths; `ColonyNotifier` announces births, deaths, new districts and new species. **Beds gate growth and beds need wool — livestock is the missing link.** |
| Play-session fixes (2026-09-23) | **done** — from real logs: (a) citizens had **no names** (`entity.minecivilization.citizen` everywhere) — identity now applied as a visible custom name, plus `/mciv highlight` and a distance/bearing citizen list; (b) **creeper suicide** — `CombatPolicy.assess` replaces the yes/no engage test with ENGAGE/FLEE/IGNORE per threat kind, health and armament; (c) `TARGET_UNREACHABLE` on trees — the gather move aimed at the log the search spotted, routinely mid-trunk, and now aims at the **tree base**; (d) infinite `no reachable wheat` loop — HARVEST falls through to FORAGE → TILL_SOIL → sow, and farmers craft a hoe first. |
| Decision ladder | **fixed 2026-09-23** — a session log showed 75 of 101 goals were `HARVEST_FOOD`: colony food security sat above the tool bootstrap, and a colony with no chest reads 0 food forever, so that rule fired every time and no citizen ever reached planks/table/tools. Split into personal hunger (still top) and the colony larder (now after the bootstrap, and gated on a hoe for farmers). Task starts/completions promoted to INFO + a per-minute `[Colony]` heartbeat. |
| Livestock | **done 2026-09-23** — `AnimalHusbandry` (species ↔ feed, what to fetch next), `AnimalPen` blueprint (closed ring, one gate, lit corners — tested for gaps and floating fence), `HERD_ANIMAL` (lead home, feed really spent, complete herd = success), `BREED_ANIMALS` (pairs inside the pasture only), `SHEPHERD` profession. |
| Decoration & lighting | **done 2026-09-23** — `DecorPlan` (deterministic torch lattice with no dark corners, civic hearth, flower beds) + `DECORATE` skill. Lighting ranks above routine trade work because unlit streets spawn the mobs that kill citizens. |
| Frozen-citizens fix (2026-09-23) | **done** — citizens stood still; the `[Colony]` heartbeat showed `searching (849000 checked)` and never finishing. Three compounding causes: (a) the policy named `minecraft:oak_log` in a spruce biome → `ResourceFamily` substitutes interchangeable species and counts them towards the request, and the mock crafts planks of the wood actually carried; (b) raster scan started in the far cube corner → `SpiralScan` walks shells nearest-first (`FindBlockSkill` + `BlockScanner`); (c) retries rescanned an identical cube 66 times → bounded to 3 attempts. |
| Craft/hunger/storage deadlocks (2026-09-23) | **done** — 108 identical `missing ingredients for minecraft:stick`: `CraftItemSkill` took the first recipe producing the target (bamboo, not planks) instead of one it could afford. Also: `isEligibleForWork` refused work below the starvation line, so a starving citizen could never feed itself; hunger crosses the wire 0..100 but every rule and fixture treated it as 0..20; and no container was ever built, pinning `known_storage` at 0 and blocking deliveries, the food reserve and births. |
| Dedicated server | **done 2026-09-23** — `run-server/` working dir, EULA prompt (the user accepts, not the script), `online-mode=false`, `Dev` pre-opped, and `ColonyChunkLoader` keeps the town's chunks ticking so the colony lives on with nobody connected. |
| Architecture | **done 2026-09-23** — `HouseBuilder`/`Palette`/`HouseCatalog` replace the plank box: terrain-catching stone skirt, log corner posts, banded walls, rhythmic symmetric windows, pitched stair roof with overhanging eaves and a slab ridge, closed gables, lit plinth threshold. Palette follows the colony's dominant wood; footprint varies per plot, fixed per plot. |
| Permanent-flee bug (2026-09-23) | **fixed** — retreat ended only at 24 blocks whatever the threat, so a citizen 12m from a creeper backed away forever with its goal cleared every tick. Safe distance is now per threat kind, plus a 200-tick cap for threats that cannot be escaped. |
| Food/storage deadlock (2026-09-23) | **fixed** — 74 of 95 goals were HARVEST_FOOD and miners were foraging for seeds: with no container the food reserve reads 0 forever, and that rule sat above the one that builds the chest. Colony food security is now gated on a container existing; chest and lighting restricted to the building trades. |
| Empty field (2026-09-23) | **fixed** — TILL_SOIL/sow reported success on failure, so 73 harvests "completed" having planted nothing. Failure is now reported, sowing walks to the tilled ground first, fields prefer ground within 4 blocks of water, and DECORATE lights fields first (crops need light 9). |
| Fuel model (2026-09-23) | **fixed** — only coal counted as fuel, making charcoal unproducible (it is smelted *from* a log). Wood is now fuel at its real value, and `payableNow` counts the fuel, so a colony with logs stops walking to a coal seam. |
| Mobs vs citizens (2026-09-23) | **fixed** — hostile mobs never targeted citizens (their goals name only players/villagers/golems/turtles), so the whole combat layer almost never fired. A `NearestAttackableTargetGoal` is added to each hostile on spawn. |
| Appearance & equipment | **done 2026-09-23** — `CitizenLook` gives each trade a distinct face from Minecraft's own default player skins; held tool and worn armour are shown as zero-drop copies of owned items (no duplication on death); `HumanoidArmorLayer` added to the renderer. |
| Hunting | **done 2026-09-23** — `HUNT` skill, gated by the policy on a real famine. Never touches animals inside the pasture (breeding stock), and prefers the wild animal furthest from it. |
| Trade reassignment | **done 2026-09-23** — `ColonyRoster` (pure, tested for convergence) moves one citizen into a trade nobody holds, only when another is genuinely crowded; `CitizenEntity.retrain` refreshes name, face and tools. |
| Idle-colony root cause (2026-09-23) | **fixed** — 445 of ~780 failures were citizens failing to walk home: terrain planning is box-bounded, so a 120-block goal was unplannable. `TravelLeg` splits long journeys into 40-block legs (tested for convergence). Also: stale config kept repaths at 3 (key renamed), and starving citizens with no hoe were sent to farm (now hunt). |
| Skins never changed (2026-09-23) | **fixed** — profession lived only in NBT, which never reaches the client, so the renderer saw UNASSIGNED for everyone. Now a synced entity-data field. |
| Scaffold teardown (2026-09-23) | **done** — citizens remember throwaway blocks they place (`rememberScaffold`) and FELL_TREE mines the pillar back down before replanting, returning the material. |
| Self-defence (2026-09-23) | **fixed** — citizens fled endermen, which teleport, so retreat was a slower death. Anything already hitting us within 5 blocks is now fought whatever we hold and however hurt; breaking off is for when there is distance to use. Creepers/wardens stay the exception. Policy now crafts a sword before trade work, and a chestplate once iron or leather allows. |
| Navigation inside skills (2026-09-23) | **fixed** — `SkillNavigation` escalates to TRAVERSE for walks inside a task (crafting table, furnace, chests), which previously failed outright and accounted for every remaining "Could not reach target". |
| Hoeless farming loop (2026-09-23) | **fixed** — the larder rule dispatched any trade to till but required a hoe only of farmers. Now whoever it would send makes a hoe first, and it only sends citizens who have one. |
| Shared gazetteer | **done 2026-09-23** — `LandmarkKind`/`LandmarkRegistry`: a world-saved, colony-wide record of workstations, containers and machinery. Learned from the town survey, Forge place events and citizens' own `setBlock` calls; pruned only in loaded chunks. `BlockScanner` consults it before searching the ground, so a workbench beyond one search radius is usable at last. Carried in observations as `known_places`; `/mciv places`. |
| District markers | **done 2026-09-23** — `Signpost` (pure, tested line wrapping) + `DistrictMarker`: every zone gets a signed post and a double chest, built as a real project; the sign is stamped on completion since a blueprint cannot carry text. Both register as landmarks immediately. |
| Shared mine | **done 2026-09-23** — `MineLayout` (pure: true ore depths, walkable stair, alternating legs, landings) + `MineWorks` (saved shared progress) + `DIG_MINE` skill: marked lit entrance, stair lit every 6 steps, a signed double-chest depot at each ore's real depth. Worked in shifts so many miners finish it together. Miners light and stock before cutting. |
| Idleness (2026-09-23) | **fixed** — heartbeat showed 57% of citizen time with no goal while failures were low: they were waiting, not failing. The 20s decision cooldown no longer gates "finished, what next?" (reflection only, now 5s); a lost reply costs seconds not a minute; citizens prefetch the next plan while finishing the current one; rests cut from 10s to 2s; service rate limit 30→600/min for the local mock. Also found: the goal-continuation rule split the label on `:` instead of `|`, so it had never fired. Heartbeat now reports `busy/total (N% idle)`. |
| Roads & terraforming | pending — `TerrainPlanner` can already bridge/tunnel/pillar for movement, but nothing yet levels ground or lays a road surface between districts |
| Shearing | pending — sheep can be bred but not yet sheared, so wool still depends on breeding drops |
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

- Python: 67 pytest tests for schemas, API routes, ledger, scheduler, memory retrieval, mock
  provider (incl. CRAFT continuation/planking policy and the task-failure loop-breaker),
  malformed LLM output, integration flows.
- Java: 75 plain JUnit tests (no Minecraft bootstrap) for decision-schema parsing
  (`CitizenPlan`, `AiBridge` responses), blueprint BOM/placement order, starter warehouse,
  starter house, camp row geometry (slots/overlap), citizen identity/role rotation, construction project lifecycle, skill registry completeness,
  the combat/reflex policy
  (`CombatPolicy`), and block reachability / approach-search geometry — run by `./gradlew test`.
- Integration smoke test: `scripts/smoke-test.sh` boots the real service on an isolated scratch
  port (`MCIV_SMOKE_PORT`, default 8766), checks `/health`, authorized and unauthorized requests.
  `./run.sh test` runs everything headlessly: build + Java tests + Python tests + smoke test.

## Non-negotiables

- No cloud APIs, no CUDA, no Docker requirement, no per-tick LLM calls, no schematic pasting,
  no free resources, no world mutation off the server thread, no raw world dumps to the LLM.
