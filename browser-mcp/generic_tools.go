package main

import (
	"encoding/json"
	"fmt"
	"time"
)

// ── search_and_extract_links ──────────────────────────────────────────

func toolSearchExtractLinks(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	selector, _ := getStr(args, "selector")
	script := fmt.Sprintf(searchExtractLinksScript, jsonSelectorArg(selector))
	raw, err := evalJSON(s, script)
	if err != nil {
		return nil, err
	}
	return raw, nil
}

// jsonSelectorArg returns a JSON-quoted string for the selector parameter.
// Empty selector means "all links" — the JS code falls through to
// document.querySelectorAll('a[href]') when sel is empty.
func jsonSelectorArg(sel string) string {
	if sel == "" {
		return `""`
	}
	return jsonQuote(sel)
}

// ── media_status ──────────────────────────────────────────────────────

func toolMediaStatus(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	// Anti-poll guard: if media_status was called on this page within
	// the last 5 seconds, return a hint instead of re-running the JS.
	// This stops agents from querying status 10+ times waiting for
	// "better" playback state (e.g. waiting for ads to finish).
	s.mediaMu.Lock()
	last := s.mediaLast
	s.mediaLast = time.Now()
	s.mediaMu.Unlock()
	if !last.IsZero() && time.Since(last) < 5*time.Second {
		return map[string]interface{}{
			"poll_warning": true,
			"message":      "刚才已查过此页媒体状态（5秒内）。视频在播就是完成了，不要反复查 media_status 等'正片'/'duration 变长'。立即向用户汇报当前结果即可。",
			"last_query_ago_ms": time.Since(last).Milliseconds(),
		}, nil
	}
	raw, err := evalJSON(s, mediaStatusScript)
	if err != nil {
		return nil, err
	}
	return raw, nil
}

// ── media_play ────────────────────────────────────────────────────────

func toolMediaPlay(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	// mediaPlayScript may internally call el.play() which returns a Promise.
	// We wrap the whole script so it handles the Promise internally and
	// always returns a plain JSON string. The evaluateJS call here uses
	// awaitPromise=false (default), so the IIFE must be synchronous.
	// Our script handles this: it catches Promise rejections and falls back
	// to button clicks, returning JSON.stringify in all paths.
	v, err := s.evaluateJS(mediaPlayScript, 8*time.Second)
	if err != nil {
		return nil, err
	}
	if str, ok := v.(string); ok {
		var out interface{}
		if err := json.Unmarshal([]byte(str), &out); err != nil {
			return map[string]interface{}{"raw": str}, nil
		}
		return out, nil
	}
	return v, nil
}

// ── media_pause ───────────────────────────────────────────────────────

func toolMediaPause(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	raw, err := evalJSON(s, mediaPauseScript)
	if err != nil {
		return nil, err
	}
	return raw, nil
}

// ── wait_for_element ──────────────────────────────────────────────────

func toolWaitForElement(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	selector, ok := getStr(args, "selector")
	if !ok || selector == "" {
		return nil, &toolError{Code: -32602, Message: "Missing selector"}
	}
	timeoutMs := 5000
	if v, ok := getNum(args, "timeout"); ok && v > 0 {
		timeoutMs = int(v)
	}

	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}

	// Poll: try immediately, then retry every 300ms until timeout.
	deadline := time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	first := true
	for {
		if !first {
			time.Sleep(300 * time.Millisecond)
		}
		first = false
		script := fmt.Sprintf(waitForElementScriptTemplate, jsonQuote(selector), timeoutMs)
		raw, err := evalJSON(s, script)
		if err != nil {
			return nil, err
		}
		if m, ok := raw.(map[string]interface{}); ok {
			if found, _ := m["found"].(bool); found {
				return m, nil
			}
		}
		if !time.Now().Before(deadline) {
			return map[string]interface{}{"found": false, "error": "timeout", "waited": timeoutMs}, nil
		}
	}
}

// ── dismiss_overlays ──────────────────────────────────────────────────

func toolDismissOverlays(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	raw, err := evalJSON(s, dismissOverlaysScript)
	if err != nil {
		return nil, err
	}
	// Wait briefly for any dismissed overlay animation to complete.
	time.Sleep(500 * time.Millisecond)
	return raw, nil
}

// ── get_page_text ─────────────────────────────────────────────────────

func toolGetPageText(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	raw, err := evalJSON(s, getPageTextScript)
	if err != nil {
		return nil, err
	}
	return raw, nil
}

// ── detect_login_wall ─────────────────────────────────────────────────

func toolDetectLoginWall(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	// Use evaluateJS directly (not evalJSON) so we get the raw return value.
	// The script returns JSON.stringify(...) which CDP delivers as a string;
	// we decode it here. If the script throws, evaluateJS returns an error
	// with the exception text, which we surface to the caller.
	v, err := s.evaluateJS(detectLoginWallScript, 12*time.Second)
	if err != nil {
		return nil, err
	}
	if v == nil {
		return map[string]interface{}{"has_login_wall": false, "signals": []interface{}{}, "error": "script returned null"}, nil
	}
	if str, ok := v.(string); ok {
		var out interface{}
		if err := json.Unmarshal([]byte(str), &out); err != nil {
			// Script returned a non-JSON string; return it raw so caller has info
			return map[string]interface{}{"has_login_wall": false, "raw": str[:200]}, nil
		}
		return out, nil
	}
	// Already a decoded map/slice
	return v, nil
}

// ── click_play_button ──────────────────────────────────────────────────

func toolClickPlayButton(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	v, err := s.evaluateJS(clickPlayButtonScript, 8*time.Second)
	if err != nil {
		return nil, err
	}
	str, _ := v.(string)
	if str == "" {
		return map[string]interface{}{"ok": false, "error": "empty result"}, nil
	}
	var loc struct {
		OK     bool    `json:"ok"`
		X      float64 `json:"x"`
		Y      float64 `json:"y"`
		Method string  `json:"method"`
	}
	if err := json.Unmarshal([]byte(str), &loc); err != nil {
		return map[string]interface{}{"raw": str[:200]}, nil
	}
	if !loc.OK {
		return map[string]interface{}{"ok": false, "method": loc.Method}, nil
	}
	// Use native CDP click (mouseMoved + mousePressed) so hover-revealed
	// buttons on Apple Music/Spotify actually trigger. JS .click() doesn't
	// fire the hover state needed for those buttons.
	if err := s.dispatchClick(loc.X, loc.Y); err != nil {
		return nil, err
	}
	return map[string]interface{}{"ok": true, "method": loc.Method, "x": loc.X, "y": loc.Y}, nil
}
