"""CognitionScheduler — the Mac-performance-critical piece.

Citizens request cognition only when meaningful. Requests enter a priority queue;
a small worker pool (default concurrency 1) calls the shared local inference
server. Rate limits + bounded queue provide backpressure; Minecraft never blocks
on any of this (the Forge side calls asynchronously with short timeouts).
"""

from __future__ import annotations

import asyncio
import heapq
import logging
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from pydantic import ValidationError

from ..llm.base import LLMError, LLMProvider, LLMRequest, LLMRawResult

log = logging.getLogger("mineciv.cognition")

Validator = Callable[[dict[str, Any]], Any]  # returns parsed model or raises ValidationError


class QueueFullError(RuntimeError):
    pass


@dataclass(order=True)
class _WorkItem:
    priority: int
    seq: int
    request: LLMRequest = field(compare=False)
    future: asyncio.Future = field(compare=False)
    validator: Validator | None = field(compare=False, default=None)
    enqueued_at: float = field(compare=False, default_factory=time.monotonic)


@dataclass
class CognitionResult:
    raw: LLMRawResult | None
    parsed: Any | None
    reject_reason: str | None
    latency_ms: float
    queued_behind: int


@dataclass
class SchedulerStats:
    queue_depth: int = 0
    in_flight: int = 0
    decisions_last_minute: int = 0
    total_decisions: int = 0
    rejected: int = 0
    failures: int = 0
    avg_latency_ms: float = 0.0


class CognitionScheduler:
    def __init__(
        self,
        provider: LLMProvider,
        max_concurrency: int = 1,
        max_decisions_per_minute: int = 30,
        queue_limit: int = 128,
        window_seconds: float = 60.0,
    ) -> None:
        self.provider = provider
        self.max_concurrency = max(1, max_concurrency)
        self.max_per_minute = max(1, max_decisions_per_minute)
        self.queue_limit = queue_limit
        self.window_seconds = max(0.1, window_seconds)

        self._heap: list[_WorkItem] = []
        self._seq = 0
        self._not_empty = asyncio.Event()
        self._workers: list[asyncio.Task] = []
        self._sem = asyncio.Semaphore(self.max_concurrency)
        self._started = False
        self._stop = asyncio.Event()

        self._window: deque[float] = deque()       # completion timestamps (rate limit)
        self._done_timestamps: deque[float] = deque()  # for decisions_last_minute
        self._latencies: deque[float] = deque(maxlen=100)
        self._total = 0
        self._rejected = 0
        self._failures = 0
        self._in_flight = 0

    # ---------------------------------------------------------------- lifecycle

    async def start(self) -> None:
        if self._started:
            return
        self._started = True
        self._stop.clear()
        for i in range(self.max_concurrency):
            self._workers.append(asyncio.create_task(self._worker(i), name=f"cognition-{i}"))
        log.info("CognitionScheduler started (concurrency=%d, limit=%d/min, queue<=%d)",
                 self.max_concurrency, self.max_per_minute, self.queue_limit)

    async def stop(self) -> None:
        if not self._started:
            return
        self._stop.set()
        self._not_empty.set()
        for t in self._workers:
            t.cancel()
        await asyncio.gather(*self._workers, return_exceptions=True)
        self._workers.clear()
        self._started = False

    # ---------------------------------------------------------------- public API

    def queue_depth(self) -> int:
        return len(self._heap)

    def stats(self) -> SchedulerStats:
        now = time.monotonic()
        while self._done_timestamps and now - self._done_timestamps[0] > self.window_seconds:
            self._done_timestamps.popleft()
        avg = sum(self._latencies) / len(self._latencies) if self._latencies else 0.0
        return SchedulerStats(
            queue_depth=len(self._heap),
            in_flight=self._in_flight,
            decisions_last_minute=len(self._done_timestamps),
            total_decisions=self._total,
            rejected=self._rejected,
            failures=self._failures,
            avg_latency_ms=round(avg, 1),
        )

    async def submit(
        self,
        request: LLMRequest,
        priority: int = 2,
        validator: Validator | None = None,
    ) -> CognitionResult:
        """Enqueue and await the validated result. Raises QueueFullError under backpressure."""
        if len(self._heap) >= self.queue_limit:
            raise QueueFullError(f"cognition queue full ({self.queue_limit})")
        if not self._started:
            await self.start()

        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        queued_behind = len(self._heap)
        self._seq += 1
        item = _WorkItem(
            priority=priority,
            seq=self._seq,
            request=request,
            future=fut,
            validator=validator,
        )
        heapq.heappush(self._heap, item)
        self._not_empty.set()
        log.debug("cognition queued priority=%d behind=%d scope=%s",
                  priority, queued_behind, request.scope)

        try:
            result: CognitionResult = await fut
        except asyncio.CancelledError:
            if not fut.done():
                fut.cancel()
            raise
        result.queued_behind = queued_behind
        return result

    # ---------------------------------------------------------------- internals

    async def _rate_limit(self) -> None:
        window = self.window_seconds
        while True:
            now = time.monotonic()
            while self._window and now - self._window[0] > window:
                self._window.popleft()
            if len(self._window) < self.max_per_minute:
                self._window.append(now)
                return
            wait = window - (now - self._window[0]) + 0.01
            log.debug("cognition rate limit: sleeping %.1fs", wait)
            await asyncio.sleep(max(wait, 0.05))

    async def _worker(self, _idx: int) -> None:
        while not self._stop.is_set():
            try:
                await self._not_empty.wait()
            except asyncio.CancelledError:
                return
            if self._stop.is_set():
                return
            if not self._heap:
                self._not_empty.clear()
                continue
            item = heapq.heappop(self._heap)
            if not self._heap:
                self._not_empty.clear()

            async with self._sem:
                await self._process(item)

    async def _process(self, item: _WorkItem) -> None:
        if item.future.cancelled():
            return
        self._in_flight += 1
        start = time.perf_counter()
        try:
            await self._rate_limit()
            raw: LLMRawResult = await self.provider.generate_structured(item.request)
            latency = (time.perf_counter() - start) * 1000.0

            parsed: Any = None
            reject: str | None = None
            if item.validator is not None:
                try:
                    parsed = item.validator(raw.data)
                except ValidationError as exc:
                    reject = f"malformed LLM output rejected: {exc.errors()[0].get('msg', 'invalid')}"
                except Exception as exc:  # defensive: validator bugs must not kill the worker
                    reject = f"validator error: {exc}"

            if reject is not None:
                self._rejected += 1
                log.warning("%s", reject)
            self._total += 1
            self._latencies.append(latency)
            self._done_timestamps.append(time.monotonic())
            log.debug("decision completed %.0fms provider=%s", latency, raw.provider)
            if not item.future.done():
                item.future.set_result(
                    CognitionResult(raw=raw, parsed=parsed, reject_reason=reject,
                                    latency_ms=latency, queued_behind=0)
                )
        except LLMError as exc:
            self._failures += 1
            log.warning("cognition failed: %s", exc)
            if not item.future.done():
                item.future.set_exception(exc)
        except asyncio.CancelledError:
            if not item.future.done():
                item.future.cancel()
            raise
        except Exception as exc:  # noqa: BLE001 — never let a worker die
            self._failures += 1
            log.exception("unexpected cognition error")
            if not item.future.done():
                item.future.set_exception(exc)
        finally:
            self._in_flight -= 1
