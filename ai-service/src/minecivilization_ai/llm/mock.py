"""Deterministic mock provider.

Lets every test, integration scenario and Forge dev-session run without Ollama.
Implements a simple, transparent decision policy that mirrors what a minimal
citizen brain should do — no randomness, no network.

Policy v2 — a role-aware bootstrap ladder, in priority order:

 1. loop breaker: repeated task failures -> REST (never a livelock)
 2. continue the current goal instead of churning — except on "prefetch",
    where the citizen is asking what to do *after* the job it is finishing
 3. personal hunger: a citizen with nothing to eat goes and eats — working a
    field normally, and hunting only in a real famine (nothing carried and
    nothing stored), because a penned animal feeds the colony for good while
    a hunted one feeds it once
 4. wood first: fewer than 8 logs -> gather 16 oak logs, unless the
    settlement warehouse already holds wood, in which case draw on it
 5. stock 16 oak planks from the logs already carried
 6. bootstrap: craft a crafting table, then PLACE it on the ground
    (remembered as the ``placed:minecraft:crafting_table`` memory key)
 7. bootstrap: sticks -> wooden pickaxe -> wooden axe -> wooden shovel
  9. farmers craft a hoe — without one they can never break ground for a field
10. miners: cobblestone (mined from stone) -> stone pickaxe/axe/shovel
11. colony food security — AFTER the tool bootstrap, and only once a container
    exists: with nowhere to store food the reserve reads 0 forever, and acting
    on that reading blocks the very rule that builds the chest. an empty
    larder is the normal state of a colony with no chest yet, and treating it
    as an emergency left every citizen farming forever with no tools at all
12. craft a furnace and PLACE it — nothing smelts without one in the world
13. miners go for iron: a single CRAFT goal, because the mod's craft planner
    resolves the whole tree itself (ore, coal, smelting, assembly)
15. organized roles: miners cut the colony's shared mine (one lit stair with a
    signed, chested landing at each ore's true depth); builders and crafters
    advance construction first,
    then each profession does its trade — lumberjacks chop, miners mine,
    farmers bake and share bread, shepherds herd and breed livestock,
    crafters convert surplus into planks —
    and everyone lends a hand on active projects from their surplus
14. colony infrastructure, above routine trade work: craft and PLACE a chest
    when the colony has no container (without one the shared stock is always
    empty), then light the district — unlit streets spawn the monsters that
    kill citizens, so this is survival, not decoration
15. organized roles (see above)
16. REST when nothing needs doing

Every step is gated on what the inventory can actually pay for, so a task can
finish: no free resources, no instant-complete decision loops.
"""

from __future__ import annotations

import json
import time
from typing import Any

from ..schemas import Decision, Goal, GoalType, Task, TaskType
from .base import LLMRawResult, LLMRequest

# Absolute CRAFT targets keyed by item id. Single source of truth: the ladder
# below and the goal-continuation branch must agree on the quantity, and every
# gate must ensure the carried materials can actually pay for the whole craft.
CRAFT_TARGETS: dict[str, int] = {
    "minecraft:oak_planks": 16,
    "minecraft:stick": 4,
    "minecraft:crafting_table": 1,
    "minecraft:wooden_pickaxe": 1,
    "minecraft:wooden_axe": 1,
    "minecraft:wooden_shovel": 1,
    "minecraft:stone_pickaxe": 1,
    "minecraft:stone_axe": 1,
    "minecraft:stone_shovel": 1,
    "minecraft:wooden_hoe": 1,
    "minecraft:wooden_sword": 1,
    "minecraft:stone_sword": 1,
    "minecraft:leather_chestplate": 1,
    "minecraft:iron_chestplate": 1,
    "minecraft:iron_helmet": 1,
    "minecraft:torch": 16,
    "minecraft:chest": 2,
    "minecraft:furnace": 1,
    "minecraft:iron_pickaxe": 1,
    "minecraft:bread": 6,
}

TABLE_PLACED_KEY = "placed:minecraft:crafting_table"
FURNACE_PLACED_KEY = "placed:minecraft:furnace"
CHEST_PLACED_KEY = "placed:minecraft:chest"

# How much of a material the settlement should hold before its trade stops
# producing more. Without these, every lumberjack chops forever and the colony
# ends up with ten thousand logs and no buildings.
WOOD_STOCKED = 512
STONE_STOCKED = 512


def _stocked(stock: dict[str, int], *, suffix: str | None = None,
             items: tuple[str, ...] = ()) -> int:
    """How much of something the settlement's chests already hold."""
    total = sum(stock.get(item, 0) for item in items)
    if suffix:
        total += sum(n for k, n in stock.items() if k.endswith(suffix))
    return total


def _inventory_total(inv: dict[str, int], suffix: str) -> int:
    return sum(n for k, n in inv.items() if k.endswith(suffix))


def _plank_species(inv: dict[str, int]) -> str:
    """Planks matching the wood actually carried.

    Asking for oak planks while holding spruce logs sends the citizen off to
    find an oak tree that the biome may not contain — which is exactly how a
    colony in a taiga ends up standing still. Whatever came back from the
    forest is what gets converted.
    """
    best, best_count = None, 0
    for item, count in inv.items():
        if item.endswith("_log") and count > best_count:
            best, best_count = item, count
    if best is None:
        return "minecraft:oak_planks"
    return best[: -len("_log")] + "_planks"


def _craft_quantity(item: str) -> int:
    return CRAFT_TARGETS.get(item, 1)


def _dump(decision: Decision) -> dict[str, Any]:
    return decision.model_dump(mode="json")


def _gather(name: str, item: str, quantity: int, why: str,
            block: str | None = None) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} gathers {quantity} {item} — {why}",
        goal=Goal(type=GoalType.INCREASE_RESOURCE, resource=item,
                  target_quantity=quantity, description=why),
        tasks=[Task(type=TaskType.GATHER, resource=item, quantity=quantity,
                    block=block)],
    ))


def _craft(name: str, item: str, quantity: int, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} crafts {quantity} {item} — {why}",
        goal=Goal(type=GoalType.CRAFT_ITEM, resource=item,
                  target_quantity=quantity, description=why),
        tasks=[Task(type=TaskType.CRAFT, resource=item, quantity=quantity)],
    ))


def _place_block(name: str, block: str, why: str) -> dict[str, Any]:
    short = block.split(":")[-1].replace("_", " ")
    return _dump(Decision(
        reasoning_summary=f"{name} sets its {short} up on the ground — {why}",
        goal=Goal(type=GoalType.CRAFT_ITEM, resource=block,
                  target_quantity=1, description="workstation in use"),
        tasks=[Task(type=TaskType.PLACE, block=block)],
    ))


def _place_table(name: str, why: str) -> dict[str, Any]:
    return _place_block(name, "minecraft:crafting_table", why)


def _deliver(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} carries the surplus to shared storage — {why}",
        goal=Goal(type=GoalType.DELIVER_RESOURCE, description=why),
        tasks=[Task(type=TaskType.DELIVER)],
    ))


def _build(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} advances the active construction project — {why}",
        goal=Goal(type=GoalType.BUILD_PROJECT, description=why),
        tasks=[Task(type=TaskType.BUILD)],
    ))


def _harvest(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} works the fields — {why}",
        goal=Goal(type=GoalType.HARVEST_FOOD, description=why),
        tasks=[Task(type=TaskType.HARVEST, resource="minecraft:wheat")],
    ))


def _tend_livestock(name: str, why: str) -> dict[str, Any]:
    """Fetch what the pasture is missing, then breed what is already in it.

    One decision, two tasks, in that order: herding reports success when the
    herd is already complete, so a full pasture falls straight through to
    breeding instead of failing and asking for a new plan.
    """
    return _dump(Decision(
        reasoning_summary=f"{name} works the pasture — {why}",
        goal=Goal(type=GoalType.HARVEST_FOOD, description=why),
        tasks=[Task(type=TaskType.HERD), Task(type=TaskType.BREED, quantity=1)],
    ))


def _hunt(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} goes hunting — {why}",
        goal=Goal(type=GoalType.HARVEST_FOOD, description=why),
        tasks=[Task(type=TaskType.HUNT)],
    ))


def _mine_shaft(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} works the colony mine — {why}",
        goal=Goal(type=GoalType.INCREASE_RESOURCE, description=why),
        tasks=[Task(type=TaskType.MINE_SHAFT)],
    ))


def _decorate(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} lights and dresses the district — {why}",
        goal=Goal(type=GoalType.BUILD_PROJECT, description=why),
        tasks=[Task(type=TaskType.DECORATE)],
    ))


def _rest(name: str, why: str) -> dict[str, Any]:
    return _dump(Decision(
        reasoning_summary=f"{name} stands down — {why}",
        goal=Goal(type=GoalType.IDLE, description=why),
        tasks=[Task(type=TaskType.REST)],
    ))


def _mock_decision(payload: dict[str, Any]) -> dict[str, Any]:
    obs = payload.get("observation", {})
    citizen = obs.get("citizen", {})
    name = citizen.get("name", "citizen")
    profession = str(citizen.get("profession") or "UNASSIGNED").upper()
    inv: dict[str, int] = obs.get("inventory", {}) or {}
    civ = obs.get("civilization", {}) or {}
    current_goal = obs.get("current_goal")
    task_failures = int(obs.get("task_failures", 0) or 0)
    reason = str(payload.get("reason", ""))
    memories = set(obs.get("known_memories") or [])

    logs = _inventory_total(inv, "_log")
    planks = _inventory_total(inv, "_planks")
    sticks = inv.get("minecraft:stick", 0)
    wheat = inv.get("minecraft:wheat", 0)
    bread = inv.get("minecraft:bread", 0)
    table = inv.get("minecraft:crafting_table", 0)
    furnace = inv.get("minecraft:furnace", 0)
    cobble = inv.get("minecraft:cobblestone", 0)
    coal = inv.get("minecraft:coal", 0) + inv.get("minecraft:charcoal", 0)
    torches = inv.get("minecraft:torch", 0)
    has_wooden_pick = inv.get("minecraft:wooden_pickaxe", 0) > 0
    has_wooden_axe = inv.get("minecraft:wooden_axe", 0) > 0
    has_wooden_shovel = inv.get("minecraft:wooden_shovel", 0) > 0
    armed = any(inv.get(f"minecraft:{tier}_sword", 0) > 0
                for tier in ("wooden", "stone", "iron", "diamond", "netherite"))
    body_armour = any(inv.get(f"minecraft:{tier}_chestplate", 0) > 0
                      for tier in ("leather", "iron", "diamond", "netherite"))
    leather = inv.get("minecraft:leather", 0)
    iron = inv.get("minecraft:iron_ingot", 0)
    hunger = float(citizen.get("hunger", 20) or 20)
    has_hoe = any(inv.get(f"minecraft:{tier}_hoe", 0) > 0
                  for tier in ("wooden", "stone", "iron", "diamond", "netherite"))
    table_placed = TABLE_PLACED_KEY in memories
    furnace_placed = FURNACE_PLACED_KEY in memories
    chest = inv.get("minecraft:chest", 0)
    chest_placed = CHEST_PLACED_KEY in memories
    storage = int(civ.get("known_storage", 0) or 0)
    food_reserve = int(civ.get("food_reserve", 0) or 0)
    active = int(civ.get("active_projects", 0) or 0)
    stock: dict[str, int] = civ.get("stock", {}) or {}
    # What the colony owns, not what this citizen is carrying: the difference
    # between "we need wood" and "I personally have no wood".
    wood_stocked = _stocked(stock, suffix="_log") + _stocked(stock, suffix="_planks")
    stone_stocked = _stocked(stock, items=("minecraft:cobblestone", "minecraft:stone"))

    # 1. Loop breaker: repeated recent failures -> disengage safely. Fires even
    # when current_goal is None (the brain clears the goal right before asking
    # for a new decision after 3 task failures — that was the stuck-in-trees
    # livelock: without this the mock kept re-issuing the same GATHER).
    if task_failures >= 3:
        return _dump(Decision(
            reasoning_summary=(
                f"{name} repeatedly failed recent tasks; standing down to avoid "
                "burning resources. A new decision will be requested later."
            ),
            goal=Goal(type=GoalType.IDLE, description="recovering from repeated task failures"),
            tasks=[Task(type=TaskType.REST)],
        ))

    # 2. Continuing existing useful work beats constant goal churn.
    # "prefetch" means the citizen is about to finish and is asking what comes
    # next — continuing the goal it is already completing would just loop it.
    if current_goal and reason not in ("goal_completed", "no_goal", "prefetch"):
        # The label is "TYPE|minecraft:item" — split on the bar, not the colon.
        # Splitting on ":" yielded "INCREASE_RESOURCE|minecraft", which matched
        # nothing, so this whole branch never fired and citizens re-decided
        # from scratch on every check instead of finishing what they started.
        goal_type = str(current_goal).split("|", 1)[0]
        if goal_type == "INCREASE_RESOURCE" or goal_type == "GATHER_RESOURCE":
            res = str(current_goal).split("|")[-1] if "|" in str(current_goal) else "minecraft:oak_log"
            # cobblestone is mined from stone blocks: name both sides explicitly
            block = "minecraft:stone" if res == "minecraft:cobblestone" else None
            return _dump(Decision(
                reasoning_summary=f"{name} continues the ongoing goal without changing plans.",
                goal=Goal(type=GoalType.INCREASE_RESOURCE, resource=res, target_quantity=16),
                tasks=[Task(type=TaskType.GATHER, resource=res, quantity=16, block=block)],
            ))
        if goal_type == "BUILD_PROJECT":
            pid = str(current_goal).split("|")[-1] if "|" in str(current_goal) else None
            return _dump(Decision(
                reasoning_summary=f"{name} keeps working on the current construction project.",
                goal=Goal(type=GoalType.BUILD_PROJECT, project_id=pid),
                tasks=[Task(type=TaskType.BUILD, project_id=pid)],
            ))
        if goal_type == "HARVEST_FOOD":
            return _dump(Decision(
                reasoning_summary=f"{name} continues producing food.",
                goal=Goal(type=GoalType.HARVEST_FOOD),
                tasks=[Task(type=TaskType.HARVEST, resource="minecraft:wheat")],
            ))
        if goal_type == "CRAFT_ITEM":
            res = str(current_goal).split("|")[-1] if "|" in str(current_goal) \
                else "minecraft:oak_planks"
            if res == "minecraft:crafting_table" and table > 0:
                # crafted last decision but not down yet: place it, don't craft a second
                return _place_table(name, "the table is crafted and still in the inventory")
            if res == "minecraft:furnace" and furnace > 0:
                return _place_block(name, "minecraft:furnace",
                                    "the furnace is crafted and still in the inventory")
            qty = _craft_quantity(res)
            return _craft(name, res, qty, "keeping the ongoing craft goal on track")

    # 3. A citizen that is itself starving stops everything. This is personal
    #    hunger, not the colony's larder: the difference matters, because the
    #    colony's larder reads 0 until a chest exists, and treating that as an
    #    emergency is what kept every citizen farming forever and left the
    #    settlement with no tools and no buildings.
    # Hunger arrives on a 0..100 scale (100 = full); below about 15 a citizen
    # is starving. The threshold is deliberately well above that: by the time
    # someone is starving, walking to a field and waiting for a crop is already
    # too late.
    if hunger <= 35 and bread == 0 and wheat < 3:
        # Hunting is the last resort, not the first. An animal led home breeds
        # and feeds the colony for good; one killed in a field feeds it once.
        # So it takes a real famine — nothing carried and nothing in the larder
        # — before a citizen reaches for meat instead of a field.
        # Hunting when there is genuinely no other way to eat: an empty larder,
        # or no hoe — a citizen that cannot break ground cannot start a field,
        # and sending it to farm anyway produced a hundred and thirty identical
        # "no hoe" failures while it slowly starved.
        if food_reserve < 8 or not has_hoe:
            return _hunt(name, "nothing to eat, and no way to grow any: it is hunt or starve")
        return _harvest(name, "hungry, and carrying nothing to eat")

    # 4. Wood first: nothing builds without it. A citizen with a stocked
    #    warehouse behind it fetches rather than fells — the craft planner
    #    turns a CRAFT goal into a withdrawal when the colony already owns
    #    the material.
    if logs < 8 and planks < 4:
        if wood_stocked >= 32 and storage > 0:
            return _craft(name, _plank_species(inv), 16,
                          "the warehouse already holds wood; draw on it instead of felling more")
        return _gather(name, "minecraft:oak_log", 16,
                       "wood is the first prerequisite for tools, storage and construction")

    # 6. Logs are useless as-is: carry 16 planks (logs >= 8 is guaranteed here).
    if logs >= 8 and planks < 16:
        return _craft(name, _plank_species(inv), 16,
                      "planks are the working form of wood")

    # 7. Bootstrap: every citizen owns a workstation. Craft the table, then
    #    PLACE it — the placement is remembered in known_memories so it is
    #    never crafted twice.
    if not has_wooden_pick:
        if table > 0:
            return _place_table(name, "3x3 recipes need a table on the ground")
        if not table_placed and planks >= 4:
            return _craft(name, "minecraft:crafting_table", 1,
                          "the workstation unlocks every tool recipe")

    # 8. Bootstrap: sticks, then the wooden tool set (all need the placed table).
    if not (has_wooden_pick and has_wooden_axe and has_wooden_shovel):
        if sticks < 2 and planks >= 5:
            return _craft(name, "minecraft:stick", 4, "tool handles")
        if not has_wooden_pick and planks >= 3 and sticks >= 2:
            return _craft(name, "minecraft:wooden_pickaxe", 1, "the pickaxe opens mining")
        if not has_wooden_axe and planks >= 3 and sticks >= 2:
            return _craft(name, "minecraft:wooden_axe", 1, "an axe speeds up woodcutting")
        if not has_wooden_shovel and planks >= 1 and sticks >= 2:
            return _craft(name, "minecraft:wooden_shovel", 1, "a shovel clears soil fast")

    # 8b. A weapon, before anything else that is merely useful.
    #
    #     Bare-handed citizens lose every fight they cannot run from, and the
    #     combat rules make them retreat rather than trade a life for nothing —
    #     which means an unarmed colony simply gets chased around its own
    #     fields. Two planks and a stick ends that.
    if not armed and planks >= 2 and sticks >= 1:
        if cobble >= 2:
            return _craft(name, "minecraft:stone_sword", 1,
                          "something to fight back with")
        return _craft(name, "minecraft:wooden_sword", 1,
                      "even a wooden sword beats bare hands")

    # 8c. Armour, once the colony can spare the material. A chestplate is the
    #     single best piece for the cost, so it comes first.
    if not body_armour:
        if iron >= 8:
            return _craft(name, "minecraft:iron_chestplate", 1,
                          "iron plate turns a lost citizen into a wounded one")
        if leather >= 8:
            return _craft(name, "minecraft:leather_chestplate", 1,
                          "leather is what the herd is for")

    # 9. A farmer without a hoe cannot break ground, so it can never start a
    #     field — and a colony with no field reports "no reachable wheat"
    #     forever. The hoe is what makes farming bootstrappable at all.
    #     Not just farmers: whoever the larder rule is about to send into a
    #     field needs the tool for it. Sending a hoeless lumberjack to farm
    #     produced an endless run of "no hoe to break the ground with".
    needs_hoe = profession == "FARMER" or (storage > 0 and food_reserve < 128)
    if (needs_hoe and has_wooden_pick and not has_hoe
            and planks >= 2 and sticks >= 2):
        return _craft(name, "minecraft:wooden_hoe", 1,
                      "no hoe means no field, and no field means no food")

    # 10. Miners upgrade to stone gear before their trade proper.
    if profession == "MINER" and has_wooden_pick:
        if cobble < 16:
            return _gather(name, "minecraft:cobblestone", 16,
                           "stone is the next material tier", block="minecraft:stone")
        if sticks < 2 and planks >= 5:
            return _craft(name, "minecraft:stick", 4, "stone tool handles")
        if inv.get("minecraft:stone_pickaxe", 0) == 0 and cobble >= 3 and sticks >= 2:
            return _craft(name, "minecraft:stone_pickaxe", 1, "stone mines faster than wood")
        if inv.get("minecraft:stone_axe", 0) == 0 and cobble >= 3 and sticks >= 2:
            return _craft(name, "minecraft:stone_axe", 1, "a stone axe for the lumber trade")
        if inv.get("minecraft:stone_shovel", 0) == 0 and cobble >= 1 and sticks >= 2:
            return _craft(name, "minecraft:stone_shovel", 1, "a stone shovel for digging")

    # 11. Colony food security — now that the citizen is equipped. Placed here
    #     on purpose: before the tool bootstrap it starved the whole ladder,
    #     because an empty larder is the *normal* state of a colony that has
    #     not built a chest yet. A farmer needs its hoe first, or harvesting
    #     can never turn a meadow into a field.
    #
    #     Gated on the colony owning a container at all. With nowhere to store
    #     food the reserve reads 0 whatever the fields produce, so this rule
    #     fired on every decision forever and starved the infrastructure rule
    #     below it — which is what builds the chest that would fix the reading.
    #     Miners spent whole sessions foraging for wheat seeds because of it.
    #     An unknowable reserve is not an emergency.
    if storage > 0 and food_reserve < 128 and has_hoe:
        return _harvest(name, "the settlement's food reserve is below the safe threshold")

    # 12. A furnace is the gateway to metal. Nothing can be smelted until one
    #    is standing in the world, so it is crafted and placed like the table.
    if not furnace_placed:
        if furnace > 0:
            return _place_block(name, "minecraft:furnace",
                                "smelting needs a furnace on the ground")
        if cobble >= 8:
            return _craft(name, "minecraft:furnace", 1,
                          "metalwork starts with a furnace")

    # 13. Iron tier. This is a single decision on purpose: the mod resolves the
    #     recipe tree itself — mine the ore, mine the coal, smelt the ingots,
    #     assemble the tool — so the policy states the goal, not the procedure.
    if (profession == "MINER" and furnace_placed
            and inv.get("minecraft:iron_pickaxe", 0) == 0):
        return _craft(name, "minecraft:iron_pickaxe", 1,
                      "iron is the next tier; the craft planner works out the whole chain")

    # 14. Colony infrastructure, above routine trade work but below survival.
    #
    #     Storage first: a colony with no container has no shared stock at all,
    #     so deliveries are no-ops, the food reserve reads zero forever and
    #     nobody can withdraw what someone else gathered. Eight planks buys all
    #     of that.
    #     Only the building trades down tools for this: it takes one citizen to
    #     put a chest down, and eleven lumberjacks all stopping to make one is
    #     ten wasted afternoons.
    if (storage == 0 and not chest_placed
            and profession in ("BUILDER", "CRAFTER", "LOGISTICS", "UNASSIGNED")):
        if chest > 0:
            return _place_block(name, "minecraft:chest",
                                "the colony needs somewhere to put things")
        if planks >= 8:
            return _craft(name, "minecraft:chest", 1,
                          "nothing can be shared until something can be stored")

    # 15. Lighting the colony. Placed above the trades on purpose: an unlit
    #     settlement spawns hostile mobs inside its own streets after dark, and
    #     citizens die to things that should never have been there. Making the
    #     two-hundredth plank saves nobody.
    #     Charcoal counts as coal: a furnace and a few logs make torches, and a
    #     colony has wood long before it finds a coal seam. The craft planner
    #     works the chain out on its own — smelt a log, get charcoal, make
    #     torches — so the policy only has to say what it wants.
    #
    #     Like the chest, this is the building trades' job. Lighting outranks
    #     routine trade work, so letting everyone do it meant lumberjacks and
    #     miners downing tools to make torches for as long as they held a log.
    if profession in ("BUILDER", "CRAFTER", "LOGISTICS", "UNASSIGNED"):
        if torches < 8 and (coal >= 1 or (furnace_placed and logs >= 2)) and sticks >= 1:
            return _craft(name, "minecraft:torch", 16,
                          "an unlit colony breeds monsters in its own streets")
        if torches >= 8:
            return _decorate(name, "light the streets and make the place look lived in")

    # 16. Organized roles. Builders and crafters serve construction first; the
    #    other trades keep the raw material lines flowing.
    if active > 0 and profession in ("BUILDER", "CRAFTER", "UNASSIGNED", "LOGISTICS"):
        return _build(name, "an active construction project needs hands")

    if profession == "LUMBERJACK":
        if logs < 32 and wood_stocked < WOOD_STOCKED:
            return _gather(name, "minecraft:oak_log", 32,
                           "the settlement needs a steady wood supply")
        if storage > 0:
            return _deliver(name, "deposit logs and planks for the builders")
        if active > 0:
            return _build(name, "wood supply is caught up, help raise the buildings")
        if planks < 192:
            return _craft(name, _plank_species(inv), planks + 64,
                          "no chest yet: turn carried logs into building material")
    elif profession == "MINER":
        # The shared mine before the scratched hole. One stair, dug by
        # everybody, reaching the depths where the ore actually is — a miner
        # working alone finds ore by luck and never gets deep enough for iron,
        # let alone diamond. It needs torches to light the stair and chests for
        # the depots, so it waits until those are in hand.
        if torches >= 8 and chest >= 2:
            return _mine_shaft(name, "one deep stair serves the whole colony")
        if torches < 8 and (coal >= 1 or (furnace_placed and logs >= 2)) and sticks >= 1:
            return _craft(name, "minecraft:torch", 16, "a dark mine is a mob corridor")
        if chest < 2 and planks >= 16:
            return _craft(name, "minecraft:chest", 2, "every seam needs somewhere to put the ore")
        if cobble < 32 and stone_stocked < STONE_STOCKED:
            return _gather(name, "minecraft:cobblestone", 32,
                           "stone feeds the tools and the buildings",
                           block="minecraft:stone")
        if storage > 0:
            return _deliver(name, "deposit cobblestone for the builders")
        if active > 0:
            return _build(name, "stone supply is caught up, help raise the buildings")
        if cobble < 192 and stone_stocked < STONE_STOCKED:
            return _gather(name, "minecraft:cobblestone", cobble + 16,
                           "stockpiling stone locally until a chest exists",
                           block="minecraft:stone")
    elif profession == "FARMER":
        if bread < 6 and wheat >= (6 - bread) * 3:
            return _craft(name, "minecraft:bread", 6, "wheat becomes bread for everyone")
        if storage > 0 and bread >= 6:
            return _deliver(name, "share the baked bread with the settlement")
        if bread < 6:
            return _harvest(name, "keep the fields producing food")
        if active > 0:
            return _build(name, "everyone is fed, help raise the buildings")
    elif profession == "BUILDER":
        if logs < 32 and wood_stocked < WOOD_STOCKED:
            return _gather(name, "minecraft:oak_log", 32,
                           "collect building material for the next house")
        if storage > 0:
            return _deliver(name, "stock the chest with construction material")
        if planks < 192:
            return _craft(name, _plank_species(inv), planks + 64,
                          "keep converting logs into buildable planks")
    elif profession == "SHEPHERD":
        # Feed is the whole job: an empty-handed shepherd can neither lead an
        # animal home nor breed one, so the field comes before the pasture.
        feed = wheat + inv.get("minecraft:wheat_seeds", 0)
        if feed < 8:
            return _harvest(name, "no feed means no herd: work the field first")
        if storage > 0 and inv.get("minecraft:white_wool", 0) >= 8:
            return _deliver(name, "wool to the warehouse — beds are what the colony needs")
        return _tend_livestock(name, "wool makes beds, and beds are what cap the population")
    elif profession == "CRAFTER":
        if bread < 3 and wheat >= (3 - bread) * 3:
            return _craft(name, "minecraft:bread", 3, "turn spare wheat into food")
        if storage > 0 and planks >= 16:
            return _deliver(name, "move crafted planks to shared storage")
        if logs < 32 and wood_stocked < WOOD_STOCKED:
            return _gather(name, "minecraft:oak_log", 32, "raw material for the workshop")
        if planks < 192:
            return _craft(name, _plank_species(inv), planks + 64,
                          "keep the plank supply flowing")
    else:  # LOGISTICS / UNASSIGNED / unknown — a self-sufficient villager
        if storage > 0 and logs >= 32:
            return _deliver(name, "drop off surplus at storage")
        if logs < 32 and wood_stocked < WOOD_STOCKED:
            return _gather(name, "minecraft:oak_log", 32, "wood remains the base resource")
        if planks < 192:
            return _craft(name, _plank_species(inv), planks + 64,
                          "convert carried logs into usable planks")

    # Trade surplus is handled: from here, pitch in on construction or rest.
    if active > 0:
        return _build(name, "the trade is caught up, lend a hand building")

    return _rest(name, "no pressing need detected")


class MockProvider:
    name = "mock"
    model = "mock-policy-v2"

    async def generate_structured(self, request: LLMRequest) -> LLMRawResult:
        start = time.perf_counter()
        if request.schema_name == "decision":
            data = _mock_decision(request.payload)
        elif request.schema_name == "civilization_plan":
            civ = request.payload.get("civilization", {})
            data = {
                "reasoning_summary": "Civilization plan (mock): keep food and wood reserves positive, "
                                     "complete active projects before starting new ones.",
                "priorities": ["food_security", "wood_supply", "active_projects"],
                "bottlenecks": civ.get("bottlenecks", []),
                "proposed_projects": [],
            }
        else:
            data = {"reasoning_summary": "mock", "unknown_schema": request.schema_name}
        latency = (time.perf_counter() - start) * 1000.0
        return LLMRawResult(
            data=data,
            provider=self.name,
            model=self.model,
            latency_ms=latency,
            raw_text=json.dumps(data),
        )

    async def health(self) -> bool:
        return True

    async def list_models(self) -> list[str]:
        return [self.model]
