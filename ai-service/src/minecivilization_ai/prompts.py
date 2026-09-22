"""Prompt loading: prompts live in <repo>/prompts/*.md with embedded fallbacks."""

from __future__ import annotations

from .config import get_settings

_FALLBACKS: dict[str, str] = {
    "citizen_system": (
        "You are a citizen living inside a persistent Minecraft civilization.\n"
        "You are not controlling Minecraft directly.\n"
        "You choose realistic goals and tasks using only the capabilities and resources "
        "available to you. Resources are scarce and physical; never assume an item exists "
        "unless the observation says it exists. Prefer continuing existing useful work over "
        "constantly changing goals. Use known infrastructure, cooperate when useful, think "
        "economically. Return only the requested structured schema.\n"
    ),
    "civilization_system": (
        "You plan for the civilization as a whole: bottlenecks, food security, storage, "
        "major projects. Run infrequently. Return only the requested schema.\n"
    ),
    "architect_system": "You design buildable structures from the civilization's blueprint knowledge.\n",
    "researcher_system": "You evaluate technologies and experiments. Never fabricate metrics.\n",
}


def load_prompt(name: str) -> str:
    settings = get_settings()
    path = settings.prompts_path / f"{name}.md"
    try:
        if path.is_file():
            return path.read_text(encoding="utf-8")
    except OSError:
        pass
    return _FALLBACKS.get(name, _FALLBACKS["citizen_system"])
