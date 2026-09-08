#!/usr/bin/env bash
# setup.sh — one-time setup for mac-web-agent
# Builds the two Go binaries: picoclaw + browser-mcp. No Node/pnpm needed.
#
# Usage: ./scripts/setup.sh
#
# Prerequisites (check before running):
#   - macOS (Intel or Apple Silicon)
#   - Homebrew:                    https://brew.sh
#   - Go 1.25+:                    brew install go
#   - Google Chrome:               https://www.google.com/chrome/
#
# After this script succeeds:
#   1. cp .env.example .env  (then edit .env to fill in your LLM API key)
#   2. ./scripts/start.sh "your query here"

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bold() { printf "\033[1m%s\033[0m\n" "$*"; }
step() { printf "\n\033[1;34m▶ %s\033[0m\n" "$*"; }
ok()    { printf "\033[1;32m  ✓ %s\033[0m\n" "$*"; }
warn()  { printf "\033[1;33m  ! %s\033[0m\n" "$*"; }
die()   { printf "\033[1;31m  ✗ %s\033[0m\n" "$*"; exit 1; }

step "Checking prerequisites"
command -v brew >/dev/null || die "Homebrew not found. Install: https://brew.sh"
command -v go   >/dev/null || die "Go 1.25+ not found. Install: brew install go"
[ -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ] \
  || die "Google Chrome not found at /Applications/Google Chrome.app"
ok "Prerequisites OK"
go version | awk '{print "  go", $3}'

step "Building picoclaw binary (~1-2 min, first run only)"
cd "$ROOT/picoclaw"
# picoclaw's default build tags include `goolm` (pure-Go libolm replacement)
# and `stdjson` — no native libolm headers needed.
if [ ! -x "$ROOT/picoclaw/build/picoclaw" ]; then
  make build
else
  warn "picoclaw binary already built, skipping (rm picoclaw/build/picoclaw to rebuild)"
fi
ok "picoclaw binary: $ROOT/picoclaw/build/picoclaw"

step "Building browser-mcp binary"
cd "$ROOT"
if [ ! -x "$ROOT/build/browser-mcp" ]; then
  go build -o build/browser-mcp ./browser-mcp
else
  warn "browser-mcp binary already built, skipping (rm build/browser-mcp to rebuild)"
fi
ok "browser-mcp binary: $ROOT/build/browser-mcp"

step "Syncing skills to ~/.picoclaw/workspace/skills"
PICOCRAW_HOME="${PICOCRAW_HOME:-$HOME/.picoclaw}"
mkdir -p "$PICOCRAW_HOME/workspace/skills"
# Copy each skill dir, overwriting stale copies. Doesn't touch other skills already there.
for skill_dir in "$ROOT"/skills/*/; do
  name=$(basename "$skill_dir")
  rm -rf "$PICOCRAW_HOME/workspace/skills/$name"
  cp -R "$skill_dir" "$PICOCRAW_HOME/workspace/skills/$name"
done
ok "Skills synced: $(ls "$PICOCRAW_HOME/workspace/skills" | tr '\n' ' ')"

step "Syncing workspace rules (SOUL.md, AGENT.md) to $PICOCRAW_HOME/workspace"
# These two files define the agent's behavior for THIS repo (mac-web-agent).
# We overwrite the workspace copies so a stale car-vehicle version doesn't bleed in.
# We deliberately do NOT touch IDENTITY.md, USER.md, HEARTBEAT.md, or memory/ —
# those are the user's personal config.
cp "$ROOT/picoclaw/workspace/SOUL.md" "$PICOCRAW_HOME/workspace/SOUL.md"
cp "$ROOT/picoclaw/workspace/AGENT.md" "$PICOCRAW_HOME/workspace/AGENT.md"
ok "Workspace rules synced (SOUL.md, AGENT.md)"

step "Writing picoclaw config from template"
cd "$ROOT"
if [ ! -f .env ]; then
  warn ".env not found, copying from .env.example"
  cp .env.example .env
  warn "Edit .env to fill in your LLM_API_KEY before running start.sh"
fi

# Render config/picoclaw.config.json → ~/.picoclaw/config.json, substituting ${VAR} from .env
PICOCRAW_HOME="${PICOCRAW_HOME:-$HOME/.picoclaw}"
mkdir -p "$PICOCRAW_HOME"
set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a
# envsubst if available, otherwise python fallback
if command -v envsubst >/dev/null 2>&1; then
  envsubst < "$ROOT/config/picoclaw.config.json" > "$PICOCRAW_HOME/config.json"
else
  python3 -c "
import os, sys, re
tpl = open('$ROOT/config/picoclaw.config.json').read()
def rep(m):
    return os.environ.get(m.group(1), m.group(0))
sys.stdout.write(re.sub(r'\\\$\{(\w+)\}', rep, tpl))
" > "$PICOCRAW_HOME/config.json"
fi
ok "Config written to $PICOCRAW_HOME/config.json"

step "Setup complete"
bold "Next steps:"
printf "  1. Edit %s/.env and fill in LLM_API_KEY (or set up Ollama)\n" "$ROOT"
printf "  2. Run:  %s/scripts/start.sh \"打开百度搜索猫\"\n" "$ROOT"
printf "  3. Chrome will open; the agent will drive it.\n"
