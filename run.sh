#!/usr/bin/env bash
# MineCivilization AI Forge — one-command launcher for the whole project.
#
# Usage:
#   ./run.sh            # start AI service + Minecraft client (default)
#   ./run.sh client     # same as default
#   ./run.sh server     # start AI service + dedicated Minecraft server
#   ./run.sh build      # build the mod jar (no game)
#   ./run.sh test       # build + Java tests + Python tests + service smoke test
#   ./run.sh service    # only start the AI service (foreground, Ctrl+C stops it)
#   ./run.sh help       # print this usage
#
# Environment overrides (see .env / .env.example):
#   MCIV_HOST / MCIV_PORT           AI service bind address (127.0.0.1:8765)
#   MINECIV_AI_BASE_URL             AI service URL seen by the mod
#   MINECIV_API_TOKEN               shared token (default dev-local-token)
#   MINECIV_LLM_PROVIDER            mock (default, offline) | ollama
#   MCIV_SMOKE_PORT                 scratch port for scripts/smoke-test.sh (8766)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

AI_PORT="${MCIV_PORT:-8765}"
AI_HOST="${MCIV_HOST:-127.0.0.1}"
AI_BASE_URL="${MINECIV_AI_BASE_URL:-http://${AI_HOST}:${AI_PORT}}"
AI_TOKEN="${MINECIV_API_TOKEN:-dev-local-token}"
VENV="$ROOT/ai-service/.venv"
SERVICE_PID=""

red()  { printf '\033[31m%s\033[0m\n' "$*" >&2; }
info() { printf '\033[36m[mciv]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[mciv]\033[0m %s\n' "$*" >&2; }

die() { red "ERROR: $*"; exit 1; }

# ---------------------------------------------------------------- JDK 21 ----
find_java21() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    if "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
      echo "$JAVA_HOME"; return 0
    fi
  fi
  # common local install locations
  local candidates=(
    "$HOME/.local/jdk-21/Contents/Home"   # mise/jabba-style macOS tarball
    "$HOME/.local/share/mise/installs/java"/*/
    "$HOME/.sdkman/candidates/java/21"*
    "/Library/Java/JavaVirtualMachines/"*21*/Contents/Home
    "/usr/lib/jvm/"*21*
  )
  local c
  for c in ${candidates[@]+"${candidates[@]}"}; do
    if [[ -x "$c/bin/java" ]] && "$c/bin/java" -version 2>&1 | grep -q '"21'; then
      echo "$c"; return 0
    fi
  done
  # any java on PATH that is 21
  if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q '"21'; then
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
      /usr/libexec/java_home -v 21 2>/dev/null && return 0
    fi
    dirname "$(dirname "$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)")"
    return 0
  fi
  return 1
}

# ----------------------------------------------------------- .env bootstrap --
bootstrap_env() {
  if [[ ! -f .env ]]; then
    if [[ -f .env.example ]]; then
      cp .env.example .env
      info "created .env from .env.example"
    else
      warn "no .env.example found; using built-in defaults"
    fi
  fi
  # Export KEY=VALUE lines (comments/blank ignored); do not override already-set vars.
  if [[ -f .env ]]; then
    local line key val
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ "$line" =~ ^[[:space:]]*# ]] && continue
      [[ "$line" =~ ^[[:space:]]*$ ]] && continue
      [[ "$line" != *"="* ]] && continue
      key="${line%%=*}"; val="${line#*=}"
      key="$(echo "$key" | xargs)"
      # strip optional surrounding quotes
      val="${val%\"}"; val="${val#\"}"
      val="${val%\'}"; val="${val#\'}"
      if [[ -z "${!key+x}" ]]; then
        export "$key=$val"
      fi
    done < .env
  fi
  # re-resolve with .env applied
  AI_PORT="${MCIV_PORT:-8765}"
  AI_HOST="${MCIV_HOST:-127.0.0.1}"
  AI_BASE_URL="${MINECIV_AI_BASE_URL:-http://${AI_HOST}:${AI_PORT}}"
  AI_TOKEN="${MINECIV_API_TOKEN:-dev-local-token}"
  export MINECIV_AI_BASE_URL="$AI_BASE_URL"
  export MINECIV_API_TOKEN="$AI_TOKEN"
}

# ------------------------------------------------------------ venv bootstrap -
bootstrap_venv() {
  if [[ ! -x "$VENV/bin/python" ]]; then
    info "creating Python venv at ai-service/.venv"
    local py=""
    for c in python3.13 python3.12 python3.11 python3; do
      if command -v "$c" >/dev/null 2>&1; then py="$c"; break; fi
    done
    [[ -n "$py" ]] || die "python3 not found (need Python >= 3.11)"
    "$py" -m venv "$VENV"
    info "installing ai-service (offline-friendly if wheels are cached)"
    "$VENV/bin/pip" install -e "$ROOT/ai-service[dev]"
  fi
}

# --------------------------------------------------------------- AI service --
service_health() {
  curl -fsS -H "Authorization: Bearer $AI_TOKEN" \
    "$AI_BASE_URL/health" 2>/dev/null
}

start_service() {
  if service_health >/dev/null 2>&1; then
    info "AI service already running at $AI_BASE_URL"
    return 0
  fi
  bootstrap_venv
  info "starting AI service on $AI_BASE_URL (token: ${AI_TOKEN:0:4}…)"
  # The service has no CLI flags: it reads MCIV_HOST/MCIV_PORT/MINECIV_API_TOKEN
  # from the environment (and .env in the cwd).
  ( cd "$ROOT" && MCIV_HOST="$AI_HOST" MCIV_PORT="$AI_PORT" \
      MINECIV_API_TOKEN="$AI_TOKEN" \
      "$VENV/bin/minecivilization-ai" \
      >> "$ROOT/ai-service.log" 2>&1 ) &
  SERVICE_PID=$!
  local i
  for i in $(seq 1 60); do
    if service_health >/dev/null 2>&1; then
      info "AI service healthy (pid $SERVICE_PID)"
      return 0
    fi
    if ! kill -0 "$SERVICE_PID" 2>/dev/null; then
      red "AI service exited early — see ai-service.log:"
      tail -n 30 "$ROOT/ai-service.log" >&2 || true
      exit 1
    fi
    sleep 0.5
  done
  red "AI service did not become healthy in 30s — see ai-service.log"
  exit 1
}

stop_service() {
  if [[ -n "$SERVICE_PID" ]] && kill -0 "$SERVICE_PID" 2>/dev/null; then
    info "stopping AI service (pid $SERVICE_PID)"
    kill "$SERVICE_PID" 2>/dev/null || true
    wait "$SERVICE_PID" 2>/dev/null || true
  fi
}

# ------------------------------------------------------------------ Gradle ---
gradlew() {
  local java21="$1"; shift
  ( cd "$ROOT/mod" && JAVA_HOME="$java21" ./gradlew "$@" )
}

usage() {
  # print the header comment of this script (lines after shebang, before code)
  awk 'NR == 1 { next } /^set -euo pipefail/ { exit } { sub(/^# ?/, ""); print }' "$0"
}

# -------------------------------------------------------------------- main ---
MODE="${1:-client}"

case "$MODE" in
  -h|--help|help) usage; exit 0 ;;
esac

bootstrap_env

case "$MODE" in
  build)
    JAVA21="$(find_java21)" || die "JDK 21 not found. Install it (e.g. 'mise use java@21') and re-run."
    info "building mod with JAVA_HOME=$JAVA21"
    gradlew "$JAVA21" build
    info "jar: mod/build/libs/"
    ;;
  test)
    JAVA21="$(find_java21)" || die "JDK 21 not found."
    gradlew "$JAVA21" build
    bootstrap_venv
    info "running Python tests"
    "$VENV/bin/pytest" -q "$ROOT/ai-service/tests"
    info "running API smoke test (scratch port ${MCIV_SMOKE_PORT:-8766})"
    bash "$ROOT/scripts/smoke-test.sh"
    ;;
  service)
    trap stop_service EXIT INT TERM
    start_service
    info "AI service running — Ctrl+C to stop"
    wait
    ;;
  client|server)
    JAVA21="$(find_java21)" || die "JDK 21 not found. Install it (e.g. 'mise use java@21') and re-run."
    info "using JAVA_HOME=$JAVA21"
    trap stop_service EXIT INT TERM
    start_service
    export MINECIV_AI_BASE_URL="$AI_BASE_URL"
    export MINECIV_API_TOKEN="$AI_TOKEN"
    if [[ "$MODE" == "client" ]]; then
      info "launching Minecraft client (first run downloads vanilla assets)…"
      gradlew "$JAVA21" runClient
    else
      info "launching Minecraft dedicated server…"
      gradlew "$JAVA21" runServer
    fi
    ;;
  *)
    red "unknown command: $MODE"; usage; exit 1
    ;;
esac
