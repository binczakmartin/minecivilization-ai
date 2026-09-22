"""LLM provider abstraction. The project never couples to a single model/vendor."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable


class LLMError(RuntimeError):
    """Raised when a provider cannot produce output (network, timeout, malformed)."""


@dataclass
class LLMRequest:
    system: str
    payload: dict[str, Any]
    schema_name: str  # "decision" | "civilization_plan"
    scope: str = "CITIZEN"
    max_tokens: int = 768


@dataclass
class LLMRawResult:
    data: dict[str, Any]
    provider: str
    model: str | None = None
    latency_ms: float = 0.0
    raw_text: str = ""


@runtime_checkable
class LLMProvider(Protocol):
    name: str

    async def generate_structured(self, request: LLMRequest) -> LLMRawResult:
        """Return a dict matching the requested schema, or raise LLMError."""
        ...

    async def health(self) -> bool:
        """True when the backing inference server is reachable."""
        ...

    async def list_models(self) -> list[str]:
        ...
