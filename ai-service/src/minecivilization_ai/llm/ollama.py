"""Ollama provider — local inference via http://127.0.0.1:11434.

Uses JSON-schema structured output when supported, falls back to JSON mode.
Never raises on startup when Ollama is missing: the service stays up in
degraded mode and callers observe health().
"""

from __future__ import annotations

import asyncio
import json
import time
from typing import Any

import httpx

from ..config import get_settings
from ..schemas import Decision
from .base import LLMError, LLMRawResult, LLMRequest

_SCHEMAS: dict[str, dict[str, Any]] = {
    "decision": Decision.model_json_schema(),
}


class OllamaProvider:
    name = "ollama"

    def __init__(self, url: str | None = None, model: str | None = None,
                 timeout: float | None = None) -> None:
        s = get_settings()
        self.url = (url or s.ollama_url).rstrip("/")
        self.model = model or s.ollama_model
        self.timeout = timeout or s.llm_timeout_seconds
        self._client = httpx.AsyncClient(
            base_url=self.url,
            timeout=httpx.Timeout(self.timeout, connect=3.0),
        )

    async def list_models(self) -> list[str]:
        try:
            resp = await self._client.get("/api/tags")
            resp.raise_for_status()
            data = resp.json()
            return [m.get("name", "") for m in data.get("models", []) if m.get("name")]
        except (httpx.HTTPError, ValueError, KeyError):
            return []

    async def health(self) -> bool:
        try:
            resp = await self._client.get("/api/tags", timeout=httpx.Timeout(2.0, connect=1.5))
            return resp.status_code == 200
        except httpx.HTTPError:
            return False

    async def _generate(self, body: dict[str, Any]) -> dict[str, Any]:
        try:
            resp = await self._client.post("/api/generate", json=body)
        except httpx.HTTPError as exc:
            raise LLMError(f"ollama unreachable: {exc}") from exc
        if resp.status_code >= 400:
            raise LLMError(f"ollama HTTP {resp.status_code}: {resp.text[:300]}")
        try:
            payload = resp.json()
        except ValueError as exc:
            raise LLMError("ollama returned non-JSON response") from exc
        text = payload.get("response", "")
        return {"text": text, "done": payload.get("done", True)}

    async def generate_structured(self, request: LLMRequest) -> LLMRawResult:
        start = time.perf_counter()
        user = json.dumps(request.payload, separators=(",", ":"))
        base = {
            "model": self.model,
            "system": request.system,
            "prompt": user,
            "stream": False,
            "options": {"temperature": 0.2, "num_predict": request.max_tokens},
            "options_hint": request.schema_name,  # removed below if unused
        }
        base.pop("options_hint")

        schema = _SCHEMAS.get(request.schema_name)
        text = ""
        last_err: LLMError | None = None
        for fmt in ([schema], ["json"] if schema else [None]):
            body = dict(base)
            if fmt[0] is not None:
                body["format"] = fmt[0]
            try:
                out = await self._generate(body)
                text = out["text"]
                last_err = None
                break
            except LLMError as exc:
                last_err = exc
                await asyncio.sleep(0.2)
        if last_err is not None:
            raise last_err

        try:
            data = json.loads(text)
        except json.JSONDecodeError as exc:
            raise LLMError(f"ollama output is not valid JSON: {text[:200]}") from exc
        if not isinstance(data, dict):
            raise LLMError("ollama output is not a JSON object")

        return LLMRawResult(
            data=data,
            provider=self.name,
            model=self.model,
            latency_ms=(time.perf_counter() - start) * 1000.0,
            raw_text=text,
        )

    async def aclose(self) -> None:
        await self._client.aclose()
