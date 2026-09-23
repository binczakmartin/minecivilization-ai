"""Runtime configuration. Everything local, everything overridable by environment."""

from __future__ import annotations

import os
from pathlib import Path

from pydantic import AliasChoices, Field as PydanticField
from pydantic_settings import BaseSettings, SettingsConfigDict


def _default_repo_root() -> Path:
    # src/minecivilization_ai/config.py -> repo root is 3 levels up from ai-service/
    return Path(__file__).resolve().parents[3]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=os.environ.get("MINECIV_ENV_FILE", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # --- service ---
    # MCIV_HOST/MCIV_PORT only: generic HOST/PORT env vars are unreliable on developer machines
    host: str = PydanticField("127.0.0.1", validation_alias=AliasChoices("MCIV_HOST"))
    port: int = PydanticField(8765, validation_alias=AliasChoices("MCIV_PORT"))
    api_token: str = PydanticField(  # MINECIV_API_TOKEN
        "dev-local-token",
        validation_alias=AliasChoices("MINECIV_API_TOKEN", "API_TOKEN"),
    )
    require_auth: bool = True

    # --- storage ---
    database_url: str = PydanticField(  # MCIV_DATABASE_URL
        "sqlite:///data/minecivilization.db",
        validation_alias=AliasChoices("MCIV_DATABASE_URL", "MINECIV_DATABASE_URL",
                                      "DATABASE_URL"),
    )
    repo_root: Path = _default_repo_root()

    # --- reproducibility ---
    seed: int = PydanticField(42, validation_alias=AliasChoices("MCIV_SEED", "SEED"))

    # --- LLM ---
    llm_provider: str = PydanticField(  # LLM_PROVIDER: mock | ollama
        "mock",
        validation_alias=AliasChoices("LLM_PROVIDER", "MCIV_LLM_PROVIDER",
                                      "MINECIV_LLM_PROVIDER"),
    )
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_model: str = "qwen2.5:3b-instruct-q4_K_M"
    llm_timeout_seconds: float = 60.0

    # --- scheduler / backpressure ---
    max_concurrent_llm_requests: int = 1  # MAX_CONCURRENT_LLM_REQUESTS
    # Sized for the offline mock, which is deterministic, local and free: with
    # eleven citizens asking for work the moment they finish a job, thirty a
    # minute is a queue, and a queued decision is a citizen standing still.
    # Lower this when pointing at Ollama, where each decision costs real time.
    max_llm_decisions_per_minute: int = 600  # MAX_LLM_DECISIONS_PER_MINUTE
    cognition_queue_limit: int = 128

    # --- internet (must stay false for offline operation) ---
    allow_internet: bool = False  # ALLOW_INTERNET

    # --- prompts ---
    prompts_dir: Path | None = None  # MCIV_PROMPTS_DIR

    # --- scheduling of civilization-level planning (advisory, seconds) ---
    civilization_plan_interval_seconds: int = 600

    @property
    def prompts_path(self) -> Path:
        return self.prompts_dir or (self.repo_root / "prompts")

    @property
    def sqlite_path(self) -> Path:
        if self.database_url.startswith("sqlite:///"):
            return Path(self.database_url[len("sqlite:///"):])
        return Path("data/minecivilization.db")


_settings: Settings | None = None


def get_settings() -> Settings:
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


def reset_settings() -> None:
    """Test helper: force re-read of environment."""
    global _settings
    _settings = None
