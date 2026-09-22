"""Application entrypoint: `minecivilization-ai` or `python -m minecivilization_ai.main`."""

from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI

from . import __version__
from .agents.cognition import CognitionScheduler
from .api.routes import health_router, router
from .config import get_settings
from .db.session import init_db
from .llm.factory import create_provider


def _configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    init_db()
    provider = await create_provider(settings)
    scheduler = CognitionScheduler(
        provider,
        max_concurrency=settings.max_concurrent_llm_requests,
        max_decisions_per_minute=settings.max_llm_decisions_per_minute,
        queue_limit=settings.cognition_queue_limit,
    )
    await scheduler.start()
    app.state.provider = provider
    app.state.scheduler = scheduler
    app.state.started_at = time.time()
    logging.getLogger("mineciv").info(
        "MineCivilization AI service v%s on http://%s:%d (db=%s, provider=%s)",
        __version__, settings.host, settings.port,
        settings.sqlite_path, getattr(provider, "name", "?"),
    )
    try:
        yield
    finally:
        await scheduler.stop()
        aclose = getattr(provider, "aclose", None)
        if aclose is not None:
            await aclose()


def create_app() -> FastAPI:
    app = FastAPI(
        title="MineCivilization AI",
        version=__version__,
        description="Local cognition service for MineCivilization AI (127.0.0.1 only)",
        lifespan=lifespan,
    )
    app.include_router(health_router)
    app.include_router(router)
    return app


app = create_app()


def cli() -> None:
    _configure_logging()
    settings = get_settings()
    uvicorn.run(
        "minecivilization_ai.main:app",
        host=settings.host,
        port=settings.port,
        log_level="info",
        access_log=False,
    )


if __name__ == "__main__":
    cli()
