#!/usr/bin/env bash
# Launch Google Chrome on macOS with remote debugging enabled.
# Usage: ./scripts/launch-chrome.sh [url]
#
# Port 9222 is the default CDP endpoint. The MCP server connects to it.
# If Chrome is already running with remote debugging, this script does nothing
# and prints the existing endpoint.

set -euo pipefail

CHROME_APP="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
CDP_PORT="${CDP_PORT:-9222}"
USER_DATA_DIR="${USER_DATA_DIR:-$HOME/.browser-mcp/chrome-profile}"
URL="${1:-about:blank}"

if ! [ -x "$CHROME_APP" ]; then
  echo "Google Chrome not found at $CHROME_APP" >&2
  exit 1
fi

# Check if CDP is already up
if curl -s "http://127.0.0.1:$CDP_PORT/json/version" >/dev/null 2>&1; then
  echo "[launch-chrome] CDP already up on :$CDP_PORT"
  # Clean up leftover tabs from previous case: close all but the first,
  # and reset the first to about:blank. This prevents pageId confusion
  # when the next case starts (e.g. JD payment tab left open → YouTube
  # agent picks wrong pageId → snapshot returns JD content).
  python3 - "$CDP_PORT" <<'PY'
import json, sys, urllib.request
port = sys.argv[1]
try:
    tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json"))
except Exception:
    sys.exit(0)
page_tabs = [t for t in tabs if t.get("type") == "page"]
# Close all but the first page tab.
for t in page_tabs[1:]:
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}/json/close/{t['id']}", timeout=2)
    except Exception:
        pass
# Navigate the remaining first tab to about:blank to reset state.
if page_tabs:
    ws_url = page_tabs[0].get("webSocketDebuggerUrl")
    # Use HTTP /json/navigate fallback (works without websocket client).
    try:
        import urllib.request as u
        u.urlopen(f"http://127.0.0.1:{port}/json/navigate", data=b'about:blank', timeout=2)
    except Exception:
        pass
print(f"[launch-chrome] closed {len(page_tabs)-1} leftover tabs, reset first tab to about:blank")
PY
  echo "[launch-chrome] open http://127.0.0.1:$CDP_PORT/json to inspect targets"
  exit 0
fi

mkdir -p "$USER_DATA_DIR"

echo "[launch-chrome] starting Chrome with --remote-debugging-port=$CDP_PORT"
echo "[launch-chrome] profile dir: $USER_DATA_DIR"

# Launch detached, with remote debugging
nohup "$CHROME_APP" \
  --remote-debugging-port="$CDP_PORT" \
  --user-data-dir="$USER_DATA_DIR" \
  --no-first-run \
  --no-default-browser-check \
  "$URL" \
  >/dev/null 2>&1 &

# Wait up to 10s for CDP endpoint
for _ in $(seq 1 20); do
  if curl -s "http://127.0.0.1:$CDP_PORT/json/version" >/dev/null 2>&1; then
    echo "[launch-chrome] CDP ready at http://127.0.0.1:$CDP_PORT"
    exit 0
  fi
  sleep 0.5
done

echo "[launch-chrome] failed to reach CDP at :$CDP_PORT after 10s" >&2
exit 1
