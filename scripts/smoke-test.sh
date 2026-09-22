#!/usr/bin/env bash
# Smoke test: boot the AI service, hit its endpoints, shut it down.
# Usage: scripts/smoke-test.sh   (expects ai-service/.venv to exist)
# Port: MCIV_SMOKE_PORT (default 8766) — intentionally independent of MCIV_PORT
# so it never clashes with a dev instance started via ./run.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Dedicated scratch port: never inherit MCIV_PORT so the smoke test cannot
# clash with a dev instance (run.sh exports MCIV_PORT from .env = 8765).
PORT="${MCIV_SMOKE_PORT:-8766}"
TOKEN="${MINECIV_API_TOKEN:-dev-local-token}"
BASE="http://127.0.0.1:$PORT"
VENV="$ROOT/ai-service/.venv"
LOG="$(mktemp -t mciv-smoke.XXXXXX)"
PID=""

cleanup() {
  if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  rm -f "$LOG"
}
trap cleanup EXIT INT TERM

fail() { echo "SMOKE FAIL: $*" >&2; [[ -f "$LOG" ]] && tail -n 40 "$LOG" >&2; exit 1; }

[[ -x "$VENV/bin/python" ]] || fail "ai-service/.venv missing — run ./run.sh once or 'python3 -m venv ai-service/.venv && ai-service/.venv/bin/pip install -e ai-service[dev]'"

echo "[smoke] starting AI service on port $PORT"
# `exec` so $! IS the service process — without it the subshell's child can
# survive cleanup (that's how orphaned instances once hijacked the dev port).
( cd "$ROOT" && exec env MCIV_PORT="$PORT" MCIV_HOST=127.0.0.1 \
    MINECIV_API_TOKEN="$TOKEN" "$VENV/bin/minecivilization-ai" >"$LOG" 2>&1 ) &
PID=$!

for i in $(seq 1 60); do
  curl -fsS "$BASE/health" >/dev/null 2>&1 && break
  kill -0 "$PID" 2>/dev/null || fail "service died on boot"
  sleep 0.5
  [[ "$i" == 60 ]] && fail "service not healthy after 30s"
done

echo "[smoke] GET /health"
health="$(curl -fsS "$BASE/health")"
echo "$health" | grep -q '"status":"ok"' || fail "/health not ok: $health"

echo "[smoke] authorized request"
code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$BASE/v1/civilization/state")"
[[ "$code" == "200" ]] || fail "GET /v1/civilization/state with token -> $code (expected 200)"

echo "[smoke] unauthorized request is rejected"
code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v1/civilization/state")"
[[ "$code" == "401" || "$code" == "403" ]] || fail "GET /v1/civilization/state without token -> $code (expected 401/403)"

echo "[smoke] OK"
