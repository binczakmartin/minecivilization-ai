"""Engine/session management: SQLite in WAL mode, batch-friendly synchronous engine
wrapped for async routes (SQLite writes are fast and local; they never touch the
Minecraft server thread)."""

from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path

from sqlalchemy import event
from sqlmodel import Session, create_engine

from ..config import get_settings
from .models import SQLModel, SchemaVersion

_engine = None


def get_engine():
    global _engine
    if _engine is None:
        settings = get_settings()
        url = settings.database_url
        if url.startswith("sqlite:///"):
            path = Path(url[len("sqlite:///"):])
            if str(path) not in (":memory:",) and not path.is_absolute():
                # resolve relative to repo root for stable behavior from any cwd
                path = settings.repo_root / path
            path.parent.mkdir(parents=True, exist_ok=True)
            url = f"sqlite:///{path}"
        _engine = create_engine(
            url,
            connect_args={"check_same_thread": False, "timeout": 30},
            echo=False,
        )
        if url.endswith(":memory:"):
            from sqlalchemy.pool import StaticPool
            # a single shared connection so in-memory DBs survive across sessions/threads
            _engine.dispose()
            _engine = create_engine(
                url,
                connect_args={"check_same_thread": False},
                poolclass=StaticPool,
                echo=False,
            )

        @event.listens_for(_engine, "connect")
        def _set_sqlite_pragma(dbapi_conn, _record):
            cur = dbapi_conn.cursor()
            cur.execute("PRAGMA journal_mode=WAL")
            cur.execute("PRAGMA synchronous=NORMAL")
            cur.execute("PRAGMA foreign_keys=ON")
            cur.close()
    return _engine


def init_db() -> None:
    engine = get_engine()
    SQLModel.metadata.create_all(engine)
    with Session(engine) as session:
        if session.get(SchemaVersion, 1) is None:
            session.add(SchemaVersion(id=1, note="initial schema"))
            session.commit()


def get_session():
    """FastAPI dependency."""
    with Session(get_engine()) as session:
        yield session


@contextmanager
def session_scope():
    """Plain context manager for scripts/tests."""
    with Session(get_engine()) as session:
        yield session


def reset_engine() -> None:
    """Test helper."""
    global _engine
    if _engine is not None:
        _engine.dispose()
    _engine = None
