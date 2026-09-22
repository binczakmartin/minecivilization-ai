"""Shared fixtures: in-memory DB, mock provider, FastAPI test client."""

from __future__ import annotations

import os

import pytest

# Must be set before importing application modules.
# Forced (not setdefault) so a developer's .env cannot leak into tests:
# a shared file DB would carry state between tests and break isolation.
os.environ["MCIV_DATABASE_URL"] = "sqlite:///:memory:"
os.environ["LLM_PROVIDER"] = "mock"
os.environ.pop("MINECIV_LLM_PROVIDER", None)
os.environ["MCIV_LLM_PROVIDER"] = "mock"
os.environ["MINECIV_API_TOKEN"] = "test-token"
os.environ["MCIV_SEED"] = "42"

from fastapi.testclient import TestClient  # noqa: E402

from minecivilization_ai.config import reset_settings  # noqa: E402
from minecivilization_ai.db.session import init_db, reset_engine  # noqa: E402
from minecivilization_ai.main import create_app  # noqa: E402

TOKEN = {"Authorization": "Bearer test-token"}


@pytest.fixture(autouse=True)
def fresh_db():
    """Every test gets a fresh in-memory DB with schema applied."""
    reset_settings()
    reset_engine()
    init_db()
    yield
    reset_engine()
    reset_settings()


@pytest.fixture()
def client():
    app = create_app()
    with TestClient(app) as c:
        yield c
