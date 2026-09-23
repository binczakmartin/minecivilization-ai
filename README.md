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
./run.sh server    # start AI service + dedicated server (never pauses)
./run.sh build     # build the mod jar → mod/build/libs/
./run.sh test      # gradle build + Java tests + pytest + API smoke test
./run.sh service   # start only the AI service (blocks; Ctrl+C stops it)
./run.sh help      # usage
```

Underlying tools (run from `mod/`): `./gradlew build`,
`./gradlew runClient`, `./gradlew runServer`.
Underlying tools (run from `ai-service/`): `.venv/bin/pytest -q tests`.

---

## Letting the colony run while you do something else

A single-player world **pauses the moment the Esc menu opens**, and the
integrated server stops with it. A settlement simulation you can only watch is
not much of a simulation, so run it as a dedicated server instead:

```bash
./run.sh server     # terminal 1 — AI service + dedicated server, never pauses
./run.sh client     # terminal 2 — Multiplayer → Direct Connect → localhost
```

The first `./run.sh server` asks you to accept the
[Minecraft EULA](https://aka.ms/MinecraftEULA) — the script will not accept it
for you — then writes `mod/run-server/server.properties` (`online-mode=false`,
because the Gradle dev client logs in as the offline user `Dev`) and ops `Dev`
so `/mciv` works as soon as you connect.

The server keeps its world in `mod/run-server/world`, separate from the
single-player saves in `mod/run/saves`. Disconnecting the client leaves the
colony running; reconnect later and it will have moved on.

### …including when nobody is connected

Minecraft only ticks chunks near a player, so logging out would normally freeze
the settlement exactly where you left it. The mod keeps the town's own chunks
loaded (`ColonyChunkLoader`), so it carries on regardless.

That is a real cost — forced chunks tick forever whether or not anyone is
watching — so it is bounded and configurable:

| Config key | Default | Purpose |
|---|---|---|
| `colony.keepLoaded` | `true` | Keep the settlement ticking with no player nearby. |
| `colony.keepLoadedRadius` | `4` | Chunks either side of the town centre: 81 chunks, a 144-block town. |

`/mciv colony` reports how many chunks are currently held open. Turn
`colony.keepLoaded` off if you would rather the colony sleep while you are away.

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
| `ai.reflectionCooldownTicks` | `100` | Ticks between *periodic* requests; idle citizens ask at once. |
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
/mciv camp                             camp anchor (the player's bed) + house projects
/mciv storage                          warehouse: containers, shelves, settlement stock
/mciv colony                           districts, population, and why growth is held back
/mciv places                           every workshop, container and machine the colony knows
/mciv highlight on|off                 outline every citizen through terrain
/mciv debug on|off                     toggle verbose mod logging
```

Citizen names are tab-completed from the live population.

---

## Skills citizens can execute

The mod ships **25 skills**, all implemented (no `NOT_IMPLEMENTED` stubs left):

```
IDLE · MOVE_TO · TRAVERSE · FOLLOW · FIND_BLOCK · MINE_BLOCK · MINE_AREA · FELL_TREE
PICKUP_ITEM · PLACE_BLOCK · HARVEST_CROP · PLANT_CROP · FORAGE · TILL_SOIL
HERD_ANIMAL · BREED_ANIMALS · DECORATE · CRAFT_ITEM · SMELT_ITEM
DEPOSIT_ITEM · WITHDRAW_ITEM · EAT · SLEEP · BUILD_BLUEPRINT · DELIVER_ITEMS
```

`CRAFT_ITEM` uses real vanilla recipes (`RecipeManager`, 2×2 anywhere / 3×3 needs a
crafting table, atomic ingredient payment) and `SMELT_ITEM` runs a real furnace
(input + fuel + result collection, burn time via `ForgeHooks.getBurnTime`).

The cognition loop is `GOAL → TASK → SKILL`: the service returns a goal/decision,
the mod's `TaskExecutor` breaks it into tasks, and the skill state machine executes
them with timeouts, stuck detection, and structured failures that are reported back
(`task-result`) for skill XP and memory updates.

### Terrain is an obstacle, not a wall

`MOVE_TO` asks vanilla navigation *"can I walk there?"*. When the answer is no,
the task escalates to **`TRAVERSE`**, which asks a different question: *"what
would I have to build or break to walk there?"*

`TerrainPlanner` runs A* over the world with three moves vanilla does not have —
**bridge** a gap (place a deck block), **tunnel** through an obstruction (dig at
body height), **pillar up** (place a block under one's own feet) — and returns a
plan the citizen then executes one block at a time through the ordinary
`MINE_BLOCK` / `PLACE_BLOCK` skills. Nothing is teleported or conjured: every
placed block is paid for out of the citizen's inventory.

The cost model keeps this honest — a dig is worth 4.5 walks and a placement 3.5,
so citizens only tunnel when going around is genuinely longer, and a small
climb surcharge stops them digging V-shaped bridges down into a ravine and back
out. `ScaffoldMaterial` decides *what* to spend: cheap and plentiful first
(dirt, cobble, planks), never sand or gravel (the deck would fall out from
underneath), never valuables or containers. `LevelBlockView` refuses to tunnel
through chests, furnaces, farmland or beds — a citizen mining its way out
through the warehouse wall is efficient and a disaster.

Plans are hypotheses, not commitments: operations another citizen already
performed are skipped, and anything unexpected triggers a bounded replan from
wherever the citizen now stands.

### Crafting resolves the whole tree

`CRAFT` used to mean *"combine what you are already holding"* — asked for an
iron pickaxe with an empty inventory, a citizen reported `MISSING_RESOURCE` and
gave up. `CraftPlanner` now walks the real recipe tree down to raw materials and
emits the steps in dependency order:

```
CRAFT minecraft:iron_pickaxe x1   ->   MINE  2x minecraft:oak_log
   (from an empty inventory)           MINE  3x minecraft:raw_iron
                                       MINE  1x minecraft:coal
                                       CRAFT 8x minecraft:oak_planks
                                       CRAFT 4x minecraft:stick
                                       SMELT 3x minecraft:iron_ingot
                                       CRAFT 1x minecraft:iron_pickaxe
```

Recipes come from the live `RecipeManager` (`VanillaRecipeSource`), so a
datapack that changes a recipe changes what citizens plan. Mining is only ever
offered for items no recipe produces — otherwise a citizen would "obtain" oak
planks by tearing them out of a neighbour's wall.

The planner handles what makes real recipe books awkward:

- **Fuel** is charged per batch, not per item: one coal carries eight smelts, so
  20 ingots need 3 coal, not 20. A citizen never plans a smelt it cannot light.
- **Ingredient sets** ("any log", "any plank") are resolved against the
  inventory with one level of lookahead, so a citizen carrying spruce does not
  walk off to find an oak tree.
- **Cycles** — ingots make blocks and blocks make ingots — are escaped by
  backtracking onto the next candidate recipe rather than looping.
- **Gathering is hoisted and merged**, so the wood for planks and the wood for
  sticks is one trip, not two.
- Everything is bounded (depth, plan length, search expansions), so planning can
  never stall a server tick.

Each plan step runs as an ordinary task in a nested `TaskExecutor`, which means
gathering *inside* a craft gets the same timeouts, retries and terrain
escalation as any other gathering.

### The warehouse sorts itself

Storage used to be a registry nothing ever wrote to: `StorageManager.register`
had no callers, so `known_storage` was permanently `0`, every delivery decision
was a no-op and the gather → deposit loop could not close. Containers are now
**discovered**, because in a living settlement they are put down as it grows
rather than declared up front:

- a chest placed by a player fires Forge's place event and registers at once;
- a chest placed by a citizen registers through `StorageDiscovery` directly,
  since `setBlock` bypasses that event;
- a slow, tick-sliced sweep around the camp anchor picks up containers that were
  already standing (an existing base, a village), and drops registrations whose
  container has since been broken. Unloaded chunks are skipped — *"I cannot see
  it"* is not *"it is gone"*.

Each container then earns a **specialty**. When a citizen needs somewhere to put
a category and no chest holds it yet, it claims the nearest unassigned one.
Nobody designs the warehouse; it is the emergent result of everyone filing
things. `DELIVER_ITEMS` groups the bag by `ItemCategory` and makes one trip per
shelf, biggest pile first, so an interrupted delivery has still done the most
useful run.

`ItemCategory` classification is pure string work on the item id — no registry,
no world — and handles the traps: an iron pickaxe is *tools*, not *ore*; a
golden apple is *food*, not *tools*; redstone dust is *machinery* while redstone
ore is *stock*.

Citizens keep a small personal reserve (`DeliveryPolicy`): four meals and eight
bridging blocks. A citizen that deposits its last loaf walks away hungry and
immediately asks for it back.

Inspect it in game with **`/mciv storage`**, which lists every container, its
shelf and its contents, plus the settlement's largest stockpiles.

### Citizens spend the colony's stock before digging for more

The craft planner sees two pools now, and spends them in the order a sensible
person would: **what is in hand** (free), then **what the settlement already
owns** (a walk to the warehouse), then **what must be dug out of the world**.

```
CRAFT minecraft:iron_pickaxe x1     ->   WITHDRAW 3x minecraft:iron_ingot
(3 ingots in the chest, 2 sticks in hand)  CRAFT    1x minecraft:iron_pickaxe
```

That single ordering is what stops a colony re-mining iron it has three chests
of. Withdrawals are hoisted ahead of everything else — the warehouse is on the
way out, not the way back — and stored material is reserved once, so two
branches of a recipe tree can never both spend the same chest. `WITHDRAW_ITEM`
picks its own container (the nearest one actually holding the item) and moves on
to the next if someone emptied it since the plan was made.

The offline policy uses the same knowledge from the other side: a lumberjack
with 600 logs in the settlement chests stops felling trees, and a newcomer with
a stocked warehouse behind it draws on that instead of walking to the forest.

### The colony has a shape

Citizens used to have no notion of where they were. A lumberjack walked in
whatever direction it happened to face; the "farm" was wherever someone once
planted wheat; nobody could tell they had drifted three hundred blocks out.

The colony now lays out **districts** on a town grid spiralling from its centre,
with four blocks of street between every pair of plots. Each `ZoneType` declares
the closest ring it may occupy, and that is the whole city plan: a quiet civic
core, warehouses and housing around it, fields and workshops beyond, forestry
and mining on the outskirts where the noise and the holes do no harm.

Land is allotted when the need appears, not all at once. `ZonePlanner` holds the
judgement — a two-person camp fences no pasture it cannot staff, housing keeps
pace with the population because reproduction depends on beds, and survival is
zoned before industry. One plot per check, so a town grows visibly rather than
materialising.

Citizens carry this in their observations: distance from the town centre, the
district they are standing in, and **where their trade is meant to be
practised** (`ZoneType.forProfession`). And they act on it without asking the AI
service — a citizen more than 220 blocks out walks home by itself, using
`TRAVERSE`, so it bridges and tunnels its way back across whatever it crossed on
the way out. The leash is generous on purpose: expeditions are how new tree
species and ore seams are found.

`/mciv colony` shows the districts, the population, and why the colony is or is
not growing.

### Felling a tree means felling the tree

Mining one log left a stump under a floating canopy, and the lumberjack walked
off to do it again elsewhere — a forest treated that way fills with debris and
never regrows. `FELL_TREE` takes the whole connected trunk, worked from the foot
upward, then plants a sapling on the stump.

The hard part is not the flood fill, it is knowing when to stop: **logs are also
a building material**, and a lumberjack that "fells" a player's log cabin is a
disaster. So a region only counts as a tree if it carries a canopy — real trees
are leafy, cabins are not — and the flood is bounded in every direction
regardless. A cabin wall yields exactly one block, the one that was aimed at.

Tall trees are out of reach from the ground, so felling leans on `TRAVERSE`: the
citizen pillars up to a high branch with its own blocks, exactly as it would to
cross a ravine. Branches it still cannot reach are left standing rather than
failing the job.

Every species felled is reported to the colony's managed forest, which is how an
expedition that finds spruce in the hills ends up with spruce growing at home.

### Citizens are born, and it costs something

Growth is the clearest signal that a settlement is working, so it is
deliberately not free. A birth spends **16 real food out of the warehouse** and
needs a **bed standing empty** for the newcomer. Both costs matter: food ties
growth to farming actually working, and the bed ties it to housing — which needs
wool, which needs livestock — so a colony that reaches twenty citizens has
genuinely built something rather than ticked a timer. The food bar rises with
the population, because a colony that grows until it starves is not one that is
doing well.

`ColonyCensus` makes this knowable: one slow, tick-sliced pass over the town
counts the beds *and* registers the containers, rather than walking the same
forty thousand cells twice to ask two questions about them. Until that first
pass finishes, "zero beds" is treated as ignorance rather than as a fact.

Births and deaths are announced in chat (`ColonyNotifier`), along with new
districts and newly discovered tree species. The everyday errands are not — a
settlement of twenty is a lot of small trips, and only the things that change
what the colony *is* are worth interrupting a player for.

### Fights worth taking, and fights worth leaving

A play session showed citizens charging creepers and dying to them, one for
one — a trade a settlement cannot afford. Threats are now *assessed* before
they are engaged:

| Threat | Response |
|---|---|
| Creeper | never engaged — backed away from inside 8 blocks, **even when it attacked first** |
| Warden, ravager, wither… | avoided at range; a villager with a stone axe does not fight one |
| Enderman, zombified piglin | ignored unless provoked — attacking one *creates* the problem |
| **Anything already hitting us, at arm's length** | **fought — whatever we hold, however hurt** |
| Anything, at under 40% health, with room to break off | disengage |
| Anything, bare-handed, at a distance | retreat: fists lose a fight you chose |
| Ordinary hostiles, armed and healthy | fought |

The first row is the one endermen taught us. "Disengage when hurt" and "retreat
when unarmed" both sound humane and both lose citizens, because a zombie is as
fast as a villager and an enderman **teleports**: running at arm's length just
turns the fight into a chase with free hits in the back. Breaking off is right
when there is distance to use, and only then. Creepers and wardens are the
exception to the exception — those are the ones to run from at any range.

Retreat aims away from the threat but biased **toward the colony** — help,
walls and daylight are all likelier at home — because running blindly corners
a citizen against the terrain.

### Finding your citizens

Citizens had no names. Every one of them was `entity.minecivilization.citizen`
in the world and in every log line, which made a settlement impossible to read.
They now carry their own name and trade on a visible tag, `/mciv citizen list`
sorts by distance with a compass bearing, and `/mciv highlight on` outlines
everyone through terrain — name tags work up close, an outline is what finds
someone at the bottom of a shaft.

### Farming bootstraps itself

Farmers spent entire sessions reporting *"no reachable wheat within 24 blocks"*,
over and over, because nothing anywhere could turn an empty meadow into a farm.
A search that comes back empty is no longer a failure — it is a colony without a
field yet, so the citizen goes and makes one:

```
no crop found  ->  FORAGE the long grass for seeds
               ->  TILL_SOIL: hoe open ground (in the farmland district)
               ->  sow, and from then on HARVEST normally
```

Grass is stingy, so foraging is budgeted rather than open-ended, and whatever
else falls out along the way — flowers, cane, mushrooms, saplings — is kept.
That is also how the colony's palette of materials widens: producing things at
home starts with having the ingredient at all. Farmers craft a hoe before
anything else, because without one the whole chain is impossible.

### Why the colony looked idle

A play session produced 101 goals and this distribution:

| Goal | Count |
|---|---|
| `HARVEST_FOOD` | **75** |
| `IDLE` | 14 |
| `INCREASE_RESOURCE` | 12 |

Three quarters of every citizen's life spent looking for wheat that did not
exist — and never once reaching the rules that make planks, a crafting table or
a pickaxe. The cause was ordering: *colony food security* sat above the tool
bootstrap, and a colony with no chest reads **zero food forever**, so that rule
fired on every single decision and starved everything below it.

Food security now sits **after** the bootstrap, and is split in two:

- **personal hunger** (a citizen with nothing to eat) still outranks everything;
- **the colony's larder** waits until the citizen is equipped — and for a
  farmer, until it holds a hoe, since harvesting without one can never turn a
  meadow into a field.

Task starts and completions are also logged at INFO now. Only failures were
visible before, which made a working colony look identical to a stuck one. A
per-minute `[Colony]` heartbeat lists every citizen, where they are and what
they are doing.

### Livestock

The pasture districts were laid out and empty, and that was the colony's
ceiling: wool makes beds, beds allow births. Citizens now **fetch and breed**:

- `HERD_ANIMAL` — walk up with feed, lead the animal home to the pasture. The
  feed is really spent, so bringing a cow home costs a little of the harvest.
  A herd that is already complete reports *success*, not failure, so a full
  pasture falls through to breeding instead of retrying forever.
- `BREED_ANIMALS` — feed a pair standing **inside** the pasture; breeding
  strays in the wild grows somebody else's herd.
- The **Animal Pen** blueprint is a closed fence ring with one gate and a torch
  on each corner. Animals escape through exactly two mistakes — a gap in the
  ring and a fence that does not reach the ground — and both are covered by
  tests, because a pen that does not hold is not a pen.
- `SHEPHERD` joins the role rotation sixth: livestock is what a fed and housed
  settlement reaches for, and what unlocks the tier above it.

Sheep are fetched first, because wool is beds and beds are what cap the
population. After that, variety beats depth — one species cannot supply
leather, eggs and mutton.

### A colony that looks lived in

`DECORATE` lights the streets, lays a hearth and plants flower beds. Half of
this is not decoration at all: an unlit settlement spawns hostile mobs **inside
its own streets** after dark, and citizens die to things that should never have
been there. The torch grid is the cheapest defence a colony has, so lighting
outranks routine trade work — making the two-hundredth plank saves nobody.

The layout is deterministic, so a citizen interrupted halfway through resumes
rather than re-scattering torches, and every district is covered: the test walks
each block and fails on a dark corner. Quarries get light but no flower beds.

### Searching in the right order, for the right thing

A session where citizens stood perfectly still turned out to be three bugs
compounding, all inside block search. The `[Colony]` heartbeat found them in
one line:

```
Jonas (MINER) — GATHER: searching (849000 checked) / INCREASE_RESOURCE / GATHER
```

**They were looking for a block the biome does not contain.** The policy asked
for `minecraft:oak_log` by name; the colony had spawned in a spruce forest.
`ResourceFamily` now answers requests with substitutes where they are genuinely
interchangeable — every log makes planks, every "cobblestone-like" block makes
stone tools — while refusing lookalikes: granite is not cobblestone. The species
asked for is still searched first, so an oak forest is still cut as oak, and
carried spruce now *counts* towards a request for oak, which it did not before:
a citizen came home with wood and registered no progress at all.

**They were searching from the wrong end.** A raster scan of a 48-block cube
starts in a corner — 48 down and 48 sideways, the least useful place to look —
so a tree five blocks away was reached only after roughly 450,000 empty cells.
`SpiralScan` walks the same cells grouped into shells, nearest first; the
regression test asserts a neighbouring cell is reached in under 3,000 steps.
The workstation scanner had the same flaw and the same fix.

**They were retrying a search that could not change.** The radius widens to its
cap after a few attempts; every attempt after that rescanned an identical cube
from an identical spot. Sixty-six of them, at fifteen seconds each. Searches now
give up after three and let the brain choose something else.

### Three ways a colony quietly locks up

A session where citizens built crafting tables and then made nothing produced
108 identical failures, and two silent deadlocks underneath them.

**A recipe it could not pay for.** A stick comes from planks *or* from bamboo,
and the skill took whichever recipe the book happened to list first. A citizen
holding a stack of planks and no bamboo picked bamboo and reported missing
ingredients forever. Recipe choice now prefers one the citizen can actually
afford, and reconsiders when materials run out mid-job — the planner already
ranked this way, but the skill re-picked its own recipe and disagreed.

**Starving citizens were forbidden from working.** The rule read sensibly —
*too hungry to work* — and was a death spiral: a starving citizen could not do
the one thing that would feed it, so its hunger kept falling and it stood still
until it died. A settlement with no food froze entirely and never recovered.
Hunger now shapes *what* a citizen does (eating outranks everything) instead of
stopping it from acting.

That bug was hidden by a second one: hunger crosses the wire on a **0..100**
scale, and every fixture and rule had been written as if it were the vanilla
0..20 food bar. Every hunger rule was firing only at death's door.

**Nowhere to put anything.** No container was ever built, so `known_storage`
stayed 0: deliveries were no-ops, the shared food reserve read zero forever,
nothing could be withdrawn, and births were permanently blocked on beds that
could never be stocked. The building trades now craft and place a **chest** as
colony infrastructure — eight planks, ranked above routine trade work, and only
the building trades stop for it, because eleven lumberjacks all making one chest
is ten wasted afternoons.

### Buildings that look designed

The colony's first house was a plank box: four flat walls, a flat lid, one
material. Scale does not fix that — a bigger box is a bigger box. `HouseBuilder`
generates buildings out of the moves that actually make architecture read:

- **a base that meets the ground** — a stone foundation with a skirt running
  three blocks below the floor, so a house on a slope sits *in* the hill rather
  than hanging over it on one corner;
- **corner posts** of log running the full height, the cheapest single thing
  that turns a wall into a facade;
- **a banded wall** — planks with a stripped-log course under the eaves, so the
  wall has a line in it instead of one flat field of texture;
- **windows on a rhythm**, evenly spaced and symmetric about the middle of each
  wall, never crowding a corner;
- **a real roof** — stairs in courses rising to a slab ridge, overhanging the
  walls, because the overhang is what casts the shadow line that makes a roof
  look like a roof;
- **closed gables**, the single most obvious mark of a build nobody finished;
- **a lit threshold**, with the lights standing on a plinth outside the wall.

The palette follows the wood the colony actually cuts (`HouseCatalog`), so a
spruce valley builds a spruce village, and the footprint varies from plot to
plot while staying fixed for any given plot — an interrupted build resumes
instead of becoming a different house.

The tests are the part that keeps this honest: they check no two blocks are
planned for one cell, that the facade is symmetric on both odd and even walls,
that the gables close, that the eaves overhang, that the two roof slopes face
opposite ways, and that a young colony can afford every material. Writing them
caught a lopsided even-width facade and a torch being placed *inside* the wall
it was meant to light.

### Felling a tree it cannot reach

Whole-tree felling only works if the citizen can get to the upper branches, and
a lumberjack that set out empty-handed had nothing to climb with — so it
abandoned everything above head height, leaving exactly the stump-and-canopy
mess the feature exists to prevent. It now **digs up the dirt it needs** from
the ground around it, the way a player would, and pillars to the branch.

### The deadlock that had miners farming

A session produced 74 `HARVEST_FOOD` goals against 9 `INCREASE_RESOURCE`, with
miners foraging for wheat seeds 140 blocks from town and nobody cutting stone.
The cause was circular:

> no container → the colony's food reserve reads **0** → the food-security rule
> fires on every decision → it sits *above* the infrastructure rule → the chest
> that would fix the reading never gets built.

Colony food security is now gated on the colony owning a container at all. With
nowhere to store food the reserve is *unknowable*, not zero, and acting on that
reading was what starved everything below it. Personal hunger is unaffected —
that rule still outranks everything.

Two smaller versions of the same mistake: the **chest** and the **lighting**
rules now belong to the building trades only. Lighting outranks routine trade
work, so letting everyone do it meant lumberjacks downing tools to make torches
for as long as they held a log.

### A field with nothing growing in it

Tilling and sowing reported *success* when they failed, which is how a colony
ended up with a field and nothing in it: 73 harvest tasks "completed" having
planted nothing. `TILL_SOIL` needs a hoe, and a citizen without one simply
cannot start a field — saying so lets the policy go and make one. Sowing also
now walks to the tilled ground before trying to plant on it, which it never did.

Fields are placed where water can reach them (`TILL_SOIL` prefers ground within
four blocks of water, and will walk past a dozen nearer tiles to get it, because
dry farmland reverts to dirt), and `DECORATE` now lights the nearest field ahead
of the town square — crops do not grow below light level nine, so an unlit field
produces nothing after dark.

### Wood burns

The fuel model knew only coal and charcoal, which made charcoal impossible to
produce: it is *made* by smelting a log, so a colony with no coal seam could
never make the charcoal it needed to smelt anything — including the charcoal
itself. Wood is fuel now, at its real value (a plank carries one item through
the furnace against coal's eight), so a furnace and a few logs are enough to
make torches.

Fixing that exposed a second bug: the planner judged a recipe affordable without
checking whether the citizen could *light* the furnace, so it rated a coal-fired
smelt as payable for a colony holding only wood and went off to mine a coal seam
with logs in its hands.

### Hostile mobs now know citizens exist

Zombies and their kind only hunt what their own target goals name — players,
villagers, iron golems, turtles. A modded mob is invisible to them, so citizens
walked through hordes untouched and the entire combat layer, threat assessment
and all, almost never fired in play. A target goal is now added to each hostile
as it spawns, which is the only hook that reaches mobs the mod does not own.

### You can see who is who, and what they carry

Every citizen was the same Steve in the same clothes, holding nothing. Two cheap
signals fix that:

- **a different face per trade** (`CitizenLook`), using Minecraft's own default
  player skins — they ship with the game and already differ in clothing as well
  as complexion, so no new art was invented;
- **the tool of the moment actually in hand**, plus armour on the back. The
  equipment slots hold *copies* of what the citizen owns with a zero drop
  chance, so nothing is duplicated when one dies — the real items stay in the
  inventory.

A fight outranks the job: a citizen with a sword draws it.

### Hunting, but only as a last resort

An animal led home breeds and feeds the colony for good; one killed in a field
feeds it once. So `HUNT` is gated on a real famine — nothing carried, no crop,
and an empty larder — and two rules stop it eating the colony's future:

- animals standing **inside the pasture are never hunted**, because that is
  breeding stock, and killing it to get through one hungry evening costs every
  meal it would have produced;
- among wild animals the one **furthest from the pasture** is chosen, so the
  unpenned herd nearby is left for the shepherd.

### Trades that fill their own gaps

Trades were handed out in spawn order, which never revisits a gap: a colony that
lost its only farmer would stop growing food permanently and nobody would
notice. `ColonyRoster` moves one citizen into a missing trade — but
conservatively, because changing trade throws away the tools and skill the
citizen built up, so it happens only when a trade is genuinely *absent* and
another genuinely *crowded*. A test applies the roster's own advice repeatedly
to prove it settles instead of flipping someone back and forth forever.

### The failure that made the colony look asleep

One session logged this:

```
445 failed: TARGET_UNREACHABLE: no route to 0,?,0 even after digging and bridging
194 failed: TARGET_UNREACHABLE: Could not reach target after 3 repaths
130 failed: MISSING_RESOURCE: no hoe to break the ground with
  0 × FELL_TREE
```

`0,?,0` is the town centre. Those were citizens trying to **walk home**.

Terrain planning searches a bounded box around the citizen, so a goal a hundred
and twenty blocks away cannot be planned for at all — not because no route
exists, but because none is visible from where it is standing. Every walk home
from a long expedition failed instantly, the brain churned, and the colony spent
its time resting. Long journeys are now walked in **legs** (`TravelLeg`): aim
forty blocks along the way, arrive, plan the next. One impossible question
becomes a series of answerable ones, and a test walks a citizen home from 140
blocks out to prove the legs converge instead of orbiting.

The second line was a stale config: the repath limit had been raised, but Forge
keeps existing config files, so worlds carried on using the old hair-trigger
value of 3. The key was renamed so the new default actually applies.

The third was a starving citizen with no hoe being sent to farm, failing, and
starving some more. It now hunts — being unable to break ground is part of
"cannot farm", not a reason to keep trying.

### Everyone had the same face

`CitizenLook` gave each trade a different skin, and it changed nothing: identity
lives in NBT, and **NBT never reaches the client**. The renderer read
`UNASSIGNED` for every citizen and drew them all the same. The trade is now
synced as entity data, which is the only version a renderer may trust.

### Felling, tidying up, replanting

The full cycle the job was always meant to be: take the whole trunk, digging up
dirt to pillar to the branches that are out of reach, then **mine the pillar
back down** — the material returns to the inventory, so climbing costs nothing
over a whole job — and plant a sapling on the stump. A felled forest dotted with
abandoned dirt towers is worse to look at than the stumps whole-tree felling was
meant to remove.

### Citizens arm themselves

The combat rules make an unarmed citizen retreat rather than trade a life for
nothing — which is correct, and which meant an unarmed colony simply got chased
around its own fields. Nobody was making weapons.

Arming now sits in the bootstrap ladder, above trade work: two planks and a
stick for a **sword** (stone when there is cobble), then a **chestplate** once
iron or leather can be spared — the single best piece for the cost. Leather is
what the herd is for, which ties defence back to the pasture.

### One stubbornness, everywhere

Tasks escalate to terrain modification when ordinary navigation gives up — but
the skills that walk somewhere *inside* a task called the navigator directly and
simply failed. In one session that was every remaining
`Could not reach target`: citizens standing a short walk from their own
workbench, reporting it unreachable because a fence or a step was in the way.

`SkillNavigation` gives the crafting table, the furnace and every chest the same
answer the task layer has: walk, and when walking genuinely will not do, bridge
or dig a way through.

### Nobody farms without a hoe

The larder rule sent *anyone* to work a field but only required a hoe of
farmers, so hoeless lumberjacks and shepherds were dispatched to till ground
they could not break — an endless run of `no hoe to break the ground with`.
Whoever the rule is about to send into a field now makes a hoe first, and the
rule itself only sends citizens who can actually work one.

### The colony remembers where things are

Citizens had no memory of places. Every craft re-scanned the ground for a
workbench, every smelt re-scanned for a furnace, and a workstation further away
than one search radius may as well not have existed — a citizen could stand in
a settlement full of them and report that it could not find one. Worse, the
search was per-citizen: a furnace one built was invisible to everybody else.

`LandmarkRegistry` is a shared, world-saved gazetteer of everywhere useful:

| Kind | Remembered |
|---|---|
| Workstations | crafting table, furnace, blast furnace, smoker, stonecutter, enchanting table, anvil, brewing stand, smithing table, grindstone, loom, cartography and fletching tables, composter |
| Storage | chests, barrels, trapped chests, shulker boxes |
| Machinery | levers, buttons, pressure plates, pistons, hoppers, droppers, dispensers, observers, comparators, repeaters, rails |
| Other | beds, torches, lanterns, campfires |

The three smelters are kept apart deliberately: a smoker cannot smelt ore and a
blast furnace cannot cook food, so lumping them together would send citizens to
the wrong building.

Places are learned three ways — the town survey already walks every cell, so it
writes down what it passes; anything a player places fires a Forge event; and
citizens place with `setBlock`, which fires no event, so they report directly.
Breaking a block forgets it, and the survey prunes anything that has gone —
only in loaded chunks, because *"I cannot see it from here"* is not *"it is
gone"*.

**Knowing where something is does not make it free to reach.** Distance is still
walked, and walking still escalates to bridging and digging. What this removes
is the need to rediscover the settlement's own infrastructure on every single
job. A remembered place is verified before a citizen is sent to it, so a furnace
that has since been mined out does not cause a long walk to nothing.

The gazetteer travels in observations as `known_places`, so the decision policy
can reason about what the colony owns, and `/mciv places` shows it.

### Districts that say what they are

A settlement of eighteen plots with nothing written on any of them is one
nobody can read — not a player walking through it, and not a citizen deciding
where to take a load of stone. Every district now gets a **post with a sign**
naming it and a **double chest** beside it, lit by a torch, built by citizens
out of real materials like anything else.

A blueprint can place a sign but cannot say anything on it, so the text is
written when the build finishes. Both the sign and the chest become landmarks
the moment they are placed, so the whole colony learns the district's depot at
once.

Sign text is four lines of fifteen characters and overruns are silently cut
off, so the wrapping is pure and tested: a long district name takes the whole
sign rather than being truncated, and a word too long for one line is cut
rather than dropped — a truncated label still says more than a blank sign.

### One mine, dug by everybody

Miners each scratching their own hole find ore by luck and never reach the deep
seams, because nobody digs far enough alone. The colony now cuts **one shared
mine**, and the thing that makes it shared is that its progress is saved state
(`MineWorks`): whoever turns up next carries on from where the last one stopped.

- **A marked, lit entrance** in the mining district — a sign saying what it is
  and torches either side, so it reads as a way in rather than a hole.
- **A walkable stair**, one block of run per block of drop, lit every six steps.
  A dark stair is a mob corridor into the colony.
- **A landing at the depth each ore actually peaks**, with a sign naming the
  seam and a double chest to leave it in. The depths are the real ones —
  iron at y=16, gold at −16, diamond and redstone at the bottom — because
  digging at the wrong level is most of why casual mining feels unproductive.
- **Legs that alternate direction**, so a mine to bedrock folds back on itself
  instead of running a hundred and twenty blocks away from the settlement.

Work happens in **shifts**: one job cuts about twenty-four blocks of stair and
ends cleanly. The mine is finished over many shifts by many people, which is
the point. Miners light the stair and make the depot chests before they start
cutting, so the mine is never dug ahead of the things that make it safe.

### Citizens that are not kept waiting

A session's heartbeat read 25 lines of `no goal / idle` out of 44 — **57% of
all citizen time spent with no goal at all** — while failures were down to 27.
They were not failing. They were waiting for permission to ask for work.

Four things were making them wait, and none of them had to:

- **A 20-second cooldown between decisions.** It exists so periodic reflection
  does not hammer the service, and it was also gating *"I have finished, what
  next?"*. Finishing a goal, failing a task and starting idle now ask
  immediately; the cooldown governs reflection alone and drops to 5 seconds.
- **A 60-second wait on a lost reply.** A request that never came back cost a
  citizen a full minute of standing about. Now a few seconds.
- **The round trip itself.** A citizen on the last task of a plan now asks what
  comes next *while still working*, at low priority, and the answer is waiting
  when the job ends. Asking early costs nothing; asking late costs the whole
  round trip, every single time.
- **Ten-second rests.** Resting is how a citizen breaks out of a failure loop,
  not a coffee break. Ten seconds times eleven citizens is most of a minute of
  colony time thrown away.

The service's own rate limit was 30 decisions a minute, sized for a cloud model.
For the offline mock — deterministic, local, free — with eleven citizens asking
the moment they finish, that is a queue, and a queued decision is a citizen
standing still. It is now 600; point it at Ollama and lower it again.

Writing the test for the prefetch turned up something else: the rule that makes
a citizen **continue its current goal** split the goal label on `:` when the
separator is `|`, so it matched nothing and had *never fired*. Citizens had been
re-deciding from scratch on every check instead of finishing what they started.

The heartbeat now leads with `busy/total citizens working (N% idle)`, because
that is the number that says whether a colony is working or waiting — and it
was invisible.

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

1. **Gradle** compile + **319 JUnit tests** of the mod (plan parsing, decision schema,
   blueprints/BOM, starter warehouse, starter house, camp row geometry, construction projects, skill registry,
   combat/reflex policy, block reachability & approach-search geometry, terrain
   planning over hand-drawn ravines/walls/shafts, scaffolding choice, recursive
   craft planning, warehouse sorting and delivery reserves, the town plan,
   colony growth rules, tree shapes and species, combat threat assessment,
   animal husbandry, pen integrity, district decoration, nearest-first search
   order, resource substitution),
2. **pytest** — **109 tests** covering schemas, API routes, memory, ledger, scheduler,
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
│       ├── skills/                        # 25 skill implementations (incl. TRAVERSE/FELL_TREE/HERD_ANIMAL)
│       ├── architecture/                  # generated houses: palettes, roofs, facades
│       ├── colony/                        # districts, town plan, census, births
│       ├── forestry/                      # tree shape, species, replanting, foraging
│       ├── mining/                        # shared mine: depths, stair, landings
│       ├── livestock/                     # which animals are kept, and on what
│       ├── crafting/                      # recursive recipe-tree planning
│       ├── storage/                       # container discovery, categories, settlement stock
│       ├── navigation/                    # path + terrain planning (bridge/tunnel/pillar)
│       ├── combat/CombatPolicy.java       # deterministic self-defence rules
│       ├── construction/                  # build sites & project goals
│       ├── network/AiBridge.java          # HTTP client, circuit breaker, main-thread queue
│       ├── commands/McivCommands.java     # /mciv …
│       └── registry/                      # entities, items, blocks, sounds
│   └── src/test/java/…                    # 319 JUnit tests (plain, no MC bootstrap)
├── ai-service/                # local AI service (Python, FastAPI)
│   ├── src/minecivilization_ai/
│   │   ├── main.py                         # app factory
│   │   ├── config.py                       # pydantic settings (env aliases)
│   │   ├── api/routes.py                   # /health + /v1/…
│   │   ├── domain/                         # citizens, memory, ledger, tech, projects
│   │   ├── cognition/                      # decision engine, prompts, providers
│   │   ├── providers/                      # mock (offline) & ollama adapters
│   │   └── db/                             # SQLAlchemy + SQLite
│   └── tests/                              # 109 pytest tests
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
