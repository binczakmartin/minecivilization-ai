# minecivilization-ai (service)

Local cognition service for MineCivilization AI. FastAPI + SQLite + local LLM (Ollama or mock).
Binds to `127.0.0.1` only. Never call cloud APIs.

Run:

```bash
./.venv/bin/minecivilization-ai
# or
./.venv/bin/python -m minecivilization_ai.main
```

Tests: `./.venv/bin/pytest`
