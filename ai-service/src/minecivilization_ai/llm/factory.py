"""Provider selection + startup detection."""

from __future__ import annotations

import logging

from ..config import Settings
from .base import LLMProvider
from .mock import MockProvider
from .ollama import OllamaProvider

log = logging.getLogger("mineciv.llm")


async def create_provider(settings: Settings) -> LLMProvider:
    kind = settings.llm_provider.strip().lower()
    if kind == "mock":
        log.info("LLM provider: mock (deterministic, offline)")
        return MockProvider()
    if kind == "ollama":
        provider = OllamaProvider()
        healthy = await provider.health()
        if healthy:
            models = await provider.list_models()
            if provider.model not in models:
                log.warning(
                    "Ollama is running but model '%s' is not installed (available: %s). "
                    "Decisions will fail until it is pulled: ollama pull %s",
                    provider.model, ", ".join(models) or "<none>", provider.model,
                )
            else:
                log.info("LLM provider: ollama model=%s", provider.model)
        else:
            log.warning(
                "Ollama not reachable at %s — starting in degraded mode "
                "(citizens will idle safely). Start with: ollama serve", provider.url,
            )
        return provider
    log.warning("Unknown LLM provider '%s', falling back to mock", kind)
    return MockProvider()
