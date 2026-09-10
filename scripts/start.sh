#!/usr/bin/env bash
# start.sh — start Chrome + browser-mcp, then send a query to picoclaw agent.
#
# Usage:
#   ./scripts/start.sh "your query"        # one-shot query, then exit
#   ./scripts/start.sh                    # interactive REPL mode (no query)
#
# Environment (from .env or shell):
#   LLM_API_KEY   - your LLM API key (required)
#   LLM_API_BASE  - LLM API base URL
#   LLM_MODEL_ID  - model id (e.g. qwen3.8-max, gpt-5.4)
#   CDP_PORT      - Chrome remote debugging port (default 9222)
#   MCP_PORT      - browser-mcp listen port (default 19002)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bold() { printf "\033[1m%s\033[0m\n" "$*"; }
step() { printf "\n\033[1;34m▶ %s\033[0m\n" "$*"; }
ok()   { printf "\033[1;32m  ✓ %s\033[0m\n" "$*"; }
warn() { printf "\033[1;33m  ! %s\033[0m\n" "$*"; }
die()  { printf "\033[1;31m  ✗ %s\033[0m\n" "$*"; exit 1; }

MCP_PID=""
MCP_OWNED=""  # "yes" if we started it this run, "" if reusing an existing one

cleanup() {
  # Kill the browser-mcp we started this run only when MCP_KEEP_ALIVE=0
  # (force-restart mode). The default (1) leaves it running so later runs reuse it.
  if [ -n "$MCP_PID" ] && [ "$MCP_OWNED" = "yes" ] && [ "${MCP_KEEP_ALIVE:-1}" = "0" ]; then
    kill "$MCP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# Load .env
if [ -f "$ROOT/.env" ]; then
  set -a; source "$ROOT/.env"; set +a
fi

CDP_PORT="${CDP_PORT:-9222}"
MCP_PORT="${MCP_PORT:-19002}"
PICOCRAW_HOME="${PICOCRAW_HOME:-$HOME/.picoclaw}"
PICOCRAW_BIN="$ROOT/picoclaw/build/picoclaw"

[ -x "$PICOCRAW_BIN" ] || die "picoclaw binary not built. Run ./scripts/setup.sh first."

# Sync skills (picks up any skill edits without re-running setup)
mkdir -p "$PICOCRAW_HOME/workspace/skills"
for skill_dir in "$ROOT"/skills/*/; do
  name=$(basename "$skill_dir")
  rm -rf "$PICOCRAW_HOME/workspace/skills/$name"
  cp -R "$skill_dir" "$PICOCRAW_HOME/workspace/skills/$name"
done

# Render config from template (picks up .env changes each run)
mkdir -p "$PICOCRAW_HOME"
if command -v envsubst >/dev/null 2>&1; then
  envsubst < "$ROOT/config/picoclaw.config.json" > "$PICOCRAW_HOME/config.json"
else
  python3 -c "
import os, sys, re
tpl = open('$ROOT/config/picoclaw.config.json').read()
def rep(m): return os.environ.get(m.group(1), m.group(0))
sys.stdout.write(re.sub(r'\\\$\{(\w+)\}', rep, tpl))
" > "$PICOCRAW_HOME/config.json"
fi
ok "picoclaw config: $PICOCRAW_HOME/config.json"

step "1/3: Starting Chrome (CDP on :$CDP_PORT)"
sh "$ROOT/scripts/launch-chrome.sh" about:blank

# Default keeps browser-mcp alive across runs (reused next run); "0" force-restarts.
: "${MCP_KEEP_ALIVE:=1}"; export MCP_KEEP_ALIVE

step "2/3: Starting browser-mcp (port :$MCP_PORT)"
if [ "$MCP_KEEP_ALIVE" = "0" ]; then
  # Force-restart: kill any existing browser-mcp (from a prior run), then start fresh.
  pkill -f "$ROOT/build/browser-mcp" 2>/dev/null || true
  i=0
  while curl -s "http://127.0.0.1:$MCP_PORT/" >/dev/null 2>&1 && [ "$i" -lt 20 ]; do
    sleep 0.1
    i=$((i+1))
  done
elif curl -s "http://127.0.0.1:$MCP_PORT/" >/dev/null 2>&1; then
  ok "browser-mcp already running on :$MCP_PORT, reusing (set MCP_KEEP_ALIVE=0 to force restart)"
fi

# Start a fresh one if nothing is listening on the port.
if ! curl -s "http://127.0.0.1:$MCP_PORT/" >/dev/null 2>&1; then
  MCP_PORT="$MCP_PORT" CDP_PORT="$CDP_PORT" "$ROOT/build/browser-mcp" > "$ROOT/.mcp.log" 2>&1 &
  MCP_PID=$!
  MCP_OWNED="yes"
  for _ in $(seq 1 30); do
    if curl -s "http://127.0.0.1:$MCP_PORT/" >/dev/null 2>&1; then
      ok "browser-mcp ready (pid $MCP_PID, log: $ROOT/.mcp.log)"
      break
    fi
    sleep 0.3
  done
  if ! curl -s "http://127.0.0.1:$MCP_PORT/" >/dev/null 2>&1; then
    die "browser-mcp failed to start. Check $ROOT/.mcp.log"
  fi
fi

step "3/3: Sending query to picoclaw agent"
# Extra flags: -d/--debug (verbose LLM + tool-call trace), -s/--session <name>
# Usage examples:
#   ./scripts/start.sh -d                          # interactive, debug on
#   ./scripts/start.sh -d -s fresh "你好"          # one-shot, debug, new session
#   ./scripts/start.sh -s work                    # interactive, named session
DEBUG_FLAG=""
SESSION_FLAG=""
QUERY=""
while [ $# -gt 0 ]; do
  case "$1" in
    -d|--debug)   DEBUG_FLAG="-d"; shift ;;
    -s|--session)
      # picoclaw only treats a session key as a true session key (not an alias)
      # if it starts with "sk_v1_" or "agent:". Auto-add the prefix so the user
      # can pass any name and still get an isolated session.
      case "$2" in
        sk_v1_*|agent:*) SESSION_FLAG="-s $2" ;;
        *)               SESSION_FLAG="-s sk_v1_$2" ;;
      esac
      shift 2 ;;
    -s=*)
      sval="${1#-s=}"
      case "$sval" in
        sk_v1_*|agent:*) SESSION_FLAG="-s $sval" ;;
        *)               SESSION_FLAG="-s sk_v1_$sval" ;;
      esac
      shift ;;
    -*)           echo "Unknown flag: $1" >&2; exit 1 ;;
    *)            QUERY="$1"; shift ;;
  esac
done

PICOCRAW_AGENT_ARGS="--no-color agent $DEBUG_FLAG $SESSION_FLAG"
if [ -n "$QUERY" ]; then
  bold "Query: $QUERY"
  eval PICOCLAW_CONFIG="\"$PICOCRAW_HOME/config.json\"" "\"$PICOCRAW_BIN\"" $PICOCRAW_AGENT_ARGS -m "\"$QUERY\""
else
  bold "Interactive mode. Type your queries; Ctrl-D to exit."
  bold "Debug: ${DEBUG_FLAG:-off}  Session: ${SESSION_FLAG:-cli:default (default)}"
  eval PICOCLAW_CONFIG="\"$PICOCRAW_HOME/config.json\"" "\"$PICOCRAW_BIN\"" $PICOCRAW_AGENT_ARGS
fi
