#!/usr/bin/env python3
"""Test generic webview agent tools against real websites."""

import json
import urllib.request
import urllib.error
import time
import sys

MCP = "http://127.0.0.1:19002/mcp"
_seq = 0

def call(tool, args=None):
    global _seq
    _seq += 1
    payload = {
        "jsonrpc": "2.0",
        "id": _seq,
        "method": "tools/call",
        "params": {"name": tool, "arguments": args or {}}
    }
    try:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(MCP, data=data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            result = json.loads(resp.read().decode("utf-8"))
        if "error" in result:
            return {"ERROR": result["error"]}
        return result.get("result", {})
    except Exception as e:
        return {"ERROR": str(e)}

def text_result(result):
    """Extract text from MCP content array."""
    content = result.get("content", [])
    if isinstance(content, list) and len(content) > 0:
        return content[0].get("text", str(content))
    return str(result)

def parse_json_result(result):
    """Parse JSON from MCP text result."""
    t = text_result(result)
    if isinstance(t, dict):
        return t
    try:
        return json.loads(t)
    except:
        return {"raw": t}

# ── Initialize ──
print("=== MCP Initialize ===")
r = call("initialize")
print(f"  Server: {r.get('serverInfo', {})}")

# ── List available tools ──
r = call("tools/list")
tools = r.get("tools", [])
tool_names = [t["name"] for t in tools]
print(f"  {len(tools)} tools registered")
new_tools = [n for n in tool_names if n in [
    "search_and_extract_links", "media_status", "media_play", "media_pause",
    "wait_for_element", "dismiss_overlays", "get_page_text", "hover", "drag"
]]
print(f"  New tools: {new_tools}")
print()

# ────────────────────────────────────────────────────────────
# TEST 1: YouTube - search and find video links
# ────────────────────────────────────────────────────────────
print("=" * 60)
print("TEST 1: YouTube - search 'lofi hip hop'")
print("=" * 60)

# Open YouTube search
r = call("new_page", {"url": "https://www.youtube.com/results?search_query=lofi+hip+hop"})
page_id = parse_json_result(r).get("pageId", 1000)
print(f"  new_page → pageId={page_id}")

time.sleep(3)

# Dismiss cookie consent
r = call("dismiss_overlays", {"pageId": page_id})
d = parse_json_result(r)
print(f"  dismiss_overlays → dismissed={d.get('dismissed', '?')}")
time.sleep(1)

# Extract video links using new helper tool
r = call("search_and_extract_links", {
    "pageId": page_id,
    "selector": "ytd-video-renderer a#video-title, ytd-grid-video-renderer a#video-title"
})
d = parse_json_result(r)
count = d.get("count", 0)
links = d.get("links", [])
print(f"  search_and_extract_links → {count} links found")
for i, link in enumerate(links[:5]):
    print(f"    [{i+1}] {link.get('text', '')[:60]} → href={link.get('href', '')[:80]}")

# Get page text
r = call("get_page_text", {"pageId": page_id})
d = parse_json_result(r)
print(f"  get_page_text → title={d.get('title', '')[:60]}")
headings = d.get("headings", [])
print(f"  headings: {headings[:3]}")

print()

# ────────────────────────────────────────────────────────────
# TEST 2: YouTube - click first video and check media
# ────────────────────────────────────────────────────────────
print("=" * 60)
print("TEST 2: YouTube - play first video result")
print("=" * 60)

if links:
    first_link = links[0]
    video_url = first_link.get("href", "")
    print(f"  Navigating to: {video_url[:80]}")
    r = call("navigate_page", {"pageId": page_id, "url": video_url})
    time.sleep(4)

    # Wait for video element
    r = call("wait_for_element", {"pageId": page_id, "selector": "video", "timeout": 5000})
    d = parse_json_result(r)
    print(f"  wait_for_element(video) → found={d.get('found')}, visible={d.get('visible')}")

    # Check media status
    r = call("media_status", {"pageId": page_id})
    d = parse_json_result(r)
    print(f"  media_status → found={d.get('found')}, type={d.get('type')}, paused={d.get('paused')}, playing={d.get('playing')}")

    # Try to play
    if d.get("found") and d.get("paused"):
        r = call("media_play", {"pageId": page_id})
        d = parse_json_result(r)
        print(f"  media_play → ok={d.get('ok')}, method={d.get('method')}")
        time.sleep(2)

        # Verify playing
        r = call("media_status", {"pageId": page_id})
        d = parse_json_result(r)
        print(f"  media_status (after play) → playing={d.get('playing')}, currentTime={d.get('currentTime')}")
    elif d.get("playing"):
        print(f"  Already playing! currentTime={d.get('currentTime')}")

    # Screenshot with DPR metadata
    r = call("take_screenshot", {"pageId": page_id})
    content = r.get("content", [])
    if isinstance(content, list) and len(content) > 0:
        meta = content[0]
        print(f"  take_screenshot → type={meta.get('type')}, dpr={meta.get('devicePixelRatio')}, "
              f"viewport={meta.get('viewportWidth')}x{meta.get('viewportHeight')}, "
              f"screenshot={meta.get('screenshotWidth')}x{meta.get('screenshotHeight')}")
else:
    print("  SKIP: No links found to click")

print()

# ────────────────────────────────────────────────────────────
# TEST 3: Spotify - search
# ────────────────────────────────────────────────────────────
print("=" * 60)
print("TEST 3: Spotify Web Player - search 'Bohemian Rhapsody'")
print("=" * 60)

r = call("navigate_page", {"pageId": page_id, "url": "https://open.spotify.com/search/Bohemian%20Rhapsody"})
time.sleep(4)

# Dismiss overlays
r = call("dismiss_overlays", {"pageId": page_id})
d = parse_json_result(r)
print(f"  dismiss_overlays → dismissed={d.get('dismissed', '?')}")
time.sleep(1)

# Extract track links
r = call("search_and_extract_links", {
    "pageId": page_id,
    "selector": "a[href*='/track/'], a[href*='/album/'], a[href*='/artist/']"
})
d = parse_json_result(r)
count = d.get("count", 0)
links = d.get("links", [])
print(f"  search_and_extract_links → {count} links found")
for i, link in enumerate(links[:5]):
    print(f"    [{i+1}] {link.get('text', '')[:50]} → href={link.get('href', '')[:80]}")

# Get page text
r = call("get_page_text", {"pageId": page_id})
d = parse_json_result(r)
print(f"  get_page_text → title={d.get('title', '')[:60]}")

print()

# ────────────────────────────────────────────────────────────
# TEST 4: Apple Music - search
# ────────────────────────────────────────────────────────────
print("=" * 60)
print("TEST 4: Apple Music - search 'Taylor Swift'")
print("=" * 60)

r = call("navigate_page", {"pageId": page_id, "url": "https://music.apple.com/search?term=Taylor%20Swift"})
time.sleep(5)

# Dismiss overlays
r = call("dismiss_overlays", {"pageId": page_id})
d = parse_json_result(r)
print(f"  dismiss_overlays → dismissed={d.get('dismissed', '?')}")

# Extract song/album links
r = call("search_and_extract_links", {
    "pageId": page_id,
    "selector": "a[href*='/song/'], a[href*='/album/'], a[href*='/artist/']"
})
d = parse_json_result(r)
count = d.get("count", 0)
links = d.get("links", [])
print(f"  search_and_extract_links → {count} links found")
for i, link in enumerate(links[:5]):
    print(f"    [{i+1}] {link.get('text', '')[:50]} → href={link.get('href', '')[:80]}")

# Get page text
r = call("get_page_text", {"pageId": page_id})
d = parse_json_result(r)
print(f"  get_page_text → title={d.get('title', '')[:60]}")

print()

# ────────────────────────────────────────────────────────────
# TEST 5: Bilibili - search video
# ────────────────────────────────────────────────────────────
print("=" * 60)
print("TEST 5: Bilibili - search '编程教程'")
print("=" * 60)

r = call("navigate_page", {"pageId": page_id, "url": "https://search.bilibili.com/all?keyword=%E7%BC%96%E7%A8%8B%E6%95%99%E7%A8%8B"})
time.sleep(3)

# Dismiss overlays
r = call("dismiss_overlays", {"pageId": page_id})
d = parse_json_result(r)
print(f"  dismiss_overlays → dismissed={d.get('dismissed', '?')}")

# Extract video links
r = call("search_and_extract_links", {"pageId": page_id})
d = parse_json_result(r)
count = d.get("count", 0)
links = d.get("links", [])
print(f"  search_and_extract_links (all links) → {count} links found")
for i, link in enumerate(links[:5]):
    print(f"    [{i+1}] {link.get('text', '')[:50]} → href={link.get('href', '')[:80]}")

print()

# ────────────────────────────────────────────────────────────
# TEST 6: Google Search
# ────────────────────────────────────────────────────────────
print("=" * 60)
print("TEST 6: Google Search - 'MacBook Pro M4 review'")
print("=" * 60)

r = call("navigate_page", {"pageId": page_id, "url": "https://www.google.com/search?q=MacBook+Pro+M4+review"})
time.sleep(3)

# Dismiss cookie consent
r = call("dismiss_overlays", {"pageId": page_id})
d = parse_json_result(r)
print(f"  dismiss_overlays → dismissed={d.get('dismissed', '?')}")

# Extract search result links
r = call("search_and_extract_links", {
    "pageId": page_id,
    "selector": "div.g a[href]:not([href*='google.com'])"
})
d = parse_json_result(r)
count = d.get("count", 0)
links = d.get("links", [])
print(f"  search_and_extract_links → {count} links found")
for i, link in enumerate(links[:5]):
    print(f"    [{i+1}] {link.get('text', '')[:60]} → href={link.get('href', '')[:80]}")

# Get page text for quick answer extraction
r = call("get_page_text", {"pageId": page_id})
d = parse_json_result(r)
print(f"  get_page_text → title={d.get('title', '')[:60]}")
headings = d.get("headings", [])
print(f"  headings: {headings[:3]}")

print()
print("=" * 60)
print("ALL TESTS COMPLETE")
print("=" * 60)
