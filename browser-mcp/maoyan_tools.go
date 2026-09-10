package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// ── date helpers (car getCinemas/getShows date resolution) ──────────────

// resolveTargetDate converts "today"/"tomorrow"/YYYYMMDD into YYYYMMDD using
// the local clock. Mirrors car mcp.js getShows date handling.
func resolveTargetDate(date string) string {
	now := time.Now()
	switch date {
	case "", "today":
		return fmt.Sprintf("%d%02d%02d", now.Year(), int(now.Month()), now.Day())
	case "tomorrow":
		t := now.AddDate(0, 0, 1)
		return fmt.Sprintf("%d%02d%02d", t.Year(), int(t.Month()), t.Day())
	default:
		return date
	}
}

// evalString runs a JS expression and returns its result coerced to string.
// Used by helpers that read location.href / small JSON strings.
func evalString(s *cdpSession, script string) (string, error) {
	v, err := s.evaluateJS(script, 10*time.Second)
	if err != nil {
		return "", err
	}
	if str, ok := v.(string); ok {
		return str, nil
	}
	b, _ := json.Marshal(v)
	return string(b), nil
}

// evalRaw runs JS and returns the raw JSON-decoded value (map/slice/string/...).
func evalRaw(s *cdpSession, script string) (interface{}, error) {
	return s.evaluateJS(script, 12*time.Second)
}

// evalJSON runs a JS expression that returns JSON.stringify(...) and
// unmarshals the resulting string into a Go value. All maoyan helper scripts
// use `return JSON.stringify(...)` so the CDP returnByValue path delivers a
// string; without this re-parse, type assertions like raw.([]interface{})
// silently fail and helpers return empty results.
func evalJSON(s *cdpSession, script string) (interface{}, error) {
	v, err := s.evaluateJS(script, 12*time.Second)
	if err != nil {
		return nil, err
	}
	str, ok := v.(string)
	if !ok {
		// already a Go map/slice (rare) — return as-is
		return v, nil
	}
	var out interface{}
	if err := json.Unmarshal([]byte(str), &out); err != nil {
		return nil, fmt.Errorf("evalJSON: bad json from script: %s (err: %v)", truncate(str, 200), err)
	}
	return out, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}

// substitute replaces placeholder tokens in a JS template with JSON-quoted values.
func substitute(tmpl string, pairs map[string]string) string {
	out := tmpl
	for k, v := range pairs {
		out = strings.ReplaceAll(out, k, v)
	}
	return out
}

// jsonQuote returns a JSON-quoted string (with surrounding quotes).
func jsonQuote(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// ── get_cinemas ──────────────────────────────────────────────────────────

func toolGetCinemas(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	// No scrollTo — querySelectorAll works regardless of scroll position,
	// and scrolling triggered re-render that broke matching.
	time.Sleep(800 * time.Millisecond)
	var raw interface{}
	deadline := time.Now().Add(3 * time.Second)
	for {
		raw, err = evalJSON(s, maoyanCinemaScript)
		if err == nil {
			if arr, ok := raw.([]interface{}); ok && len(arr) > 0 {
				break
			}
		}
		if !time.Now().Before(deadline) {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if err != nil {
		return nil, err
	}
	arr, ok := raw.([]interface{})
	if !ok {
		return []interface{}{}, nil
	}
	// sort: 朝阳区 first by distKm, then others by distKm (car behavior)
	var chaoyang, others []interface{}
	for _, c := range arr {
		cm, _ := c.(map[string]interface{})
		addr, _ := cm["address"].(string)
		if strings.Contains(addr, "朝阳") {
			chaoyang = append(chaoyang, c)
		} else {
			others = append(others, c)
		}
	}
	// stable enough; car sorts by distKm but Go map ordering of fields is lost
	// in JSON round-trip anyway, so we keep insertion order and just prepend 朝阳.
	sorted := append(chaoyang, others...)
	for i, c := range sorted {
		if cm, ok := c.(map[string]interface{}); ok {
			cm["index"] = i
		}
	}
	return map[string]interface{}{"cinemas": sorted}, nil
}

// ── get_shows ─────────────────────────────────────────────────────────────

func toolGetShows(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	date, _ := getStr(args, "date")
	movieId, _ := getStr(args, "movieId")
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	targetDate := resolveTargetDate(date)
	// if movieId missing, extract from URL (car behavior)
	if movieId == "" {
		urlStr, _ := evalString(s, `location.href`)
		if i := strings.Index(urlStr, "movieId="); i >= 0 {
			rest := urlStr[i+8:]
			end := strings.IndexAny(rest, "&\"")
			if end < 0 {
				end = len(rest)
			}
			movieId = rest[:end]
		}
	}
	// No scrollTo here — the showsByDate script uses querySelectorAll which
	// works regardless of scroll position. Scrolling caused xseats rows to
	// re-render/lazy-load and broke the parent-chain time lookup.
	time.Sleep(500 * time.Millisecond) // brief settle after navigation
	// Poll up to 3s for xseats links to render — on SPA navigation the links
	// appear asynchronously and a one-shot eval returns empty.
	script := substitute(maoyanShowsByDateScriptTemplate, map[string]string{
		"TARGET_DATE": jsonQuote(targetDate),
		"MOVIE_ID":    jsonQuote(movieId),
	})
	var raw interface{}
	deadline := time.Now().Add(3 * time.Second)
	for {
		raw, err = evalJSON(s, script)
		if err == nil {
			if m, ok := raw.(map[string]interface{}); ok {
				if shows, _ := m["shows"].([]interface{}); len(shows) > 0 {
					break
				}
			}
		}
		if !time.Now().Before(deadline) {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if err != nil {
		return nil, err
	}
	m, ok := raw.(map[string]interface{})
	if !ok {
		return map[string]interface{}{"count": 0, "shows": []interface{}{}, "targetDate": targetDate, "movieId": movieId}, nil
	}
	// filter passed shows when date is today (car behavior)
	isToday := date == "" || date == "today"
	shows, _ := m["shows"].([]interface{})
	if isToday {
		var keep []interface{}
		for _, sh := range shows {
			if sm, ok := sh.(map[string]interface{}); ok {
				if passed, _ := sm["passed"].(bool); !passed {
					keep = append(keep, sh)
				}
			}
		}
		shows = keep
		m["shows"] = shows
	}
	m["count"] = len(shows)
	m["targetDate"] = targetDate
	m["movieId"] = movieId
	return m, nil
}

// ── click_show ────────────────────────────────────────────────────────────

func toolClickShow(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	t, _ := getStr(args, "time")
	movieId, _ := getStr(args, "movieId")
	date, _ := getStr(args, "date")
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	targetDate := resolveTargetDate(date)
	// extract movieId/date from URL if missing (car behavior)
	if movieId == "" {
		urlStr, _ := evalString(s, `location.href`)
		if i := strings.Index(urlStr, "movieId="); i >= 0 {
			rest := urlStr[i+8:]
			end := strings.IndexAny(rest, "&\"")
			if end < 0 {
				end = len(rest)
			}
			movieId = rest[:end]
		}
	}
	script := substitute(maoyanClickShowScriptTemplate, map[string]string{
		"CLICK_TIME":     jsonQuote(t),
		"CLICK_MOVIE_ID": jsonQuote(movieId),
		"CLICK_DATE":     jsonQuote(targetDate),
	})
	raw, err := evalJSON(s, script)
	if err != nil {
		return nil, err
	}
	m, _ := raw.(map[string]interface{})
	found, _ := m["found"].(bool)
	if !found {
		return map[string]interface{}{"ok": false, "error": "show_not_found_in_dom", "time": t, "movieId": movieId, "targetDate": targetDate}, nil
	}
	// wait for xseats navigation
	time.Sleep(4000 * time.Millisecond)
	urlAfter, _ := evalString(s, `location.href`)
	if strings.Contains(urlAfter, "/xseats/") {
		return map[string]interface{}{"ok": true, "xseatsUrl": urlAfter}, nil
	}
	if strings.Contains(urlAfter, "passport") || strings.Contains(urlAfter, "login") {
		return map[string]interface{}{"ok": false, "error": "redirected_to_login", "url": urlAfter}, nil
	}
	return map[string]interface{}{"ok": false, "error": "xseats_not_reached", "url": urlAfter}, nil
}

// ── query_seats ───────────────────────────────────────────────────────────

func toolQuerySeats(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	raw, err := evalJSON(s, maoyanSeatQueryScript)
	if err != nil {
		return nil, err
	}
	if raw == nil {
		return map[string]interface{}{"ok": false, "error": "query_failed"}, nil
	}
	if m, ok := raw.(map[string]interface{}); ok {
		m["ok"] = true
		return m, nil
	}
	return map[string]interface{}{"ok": false, "error": "query_failed"}, nil
}

// ── dismiss_modal ─────────────────────────────────────────────────────────
// Standalone "我知道了" closer. Returns {ok, modal:"none"|"closed"}.

func toolDismissModal(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	res, err := evalString(s, maoyanDismissModalScript)
	if err != nil {
		return nil, err
	}
	if res == "no-modal" || res == "no-btn" {
		return map[string]interface{}{"ok": true, "modal": "none"}, nil
	}
	if res == "isolated-warning" {
		return map[string]interface{}{"ok": false, "error": "isolated_seat"}, nil
	}
	var coord map[string]interface{}
	if err := json.Unmarshal([]byte(res), &coord); err != nil {
		return nil, fmt.Errorf("dismiss_modal: bad coord %q", res)
	}
	// try DOM click by text "我知道了" first (car domClick.byText), coord fallback
	clicked := domClickByText(s, []string{"我知道了"}, 400)
	if !clicked {
		x, _ := coord["x"].(float64)
		y, _ := coord["y"].(float64)
		if err := s.dispatchClick(x, y); err != nil {
			return nil, err
		}
	}
	time.Sleep(800 * time.Millisecond)
	return map[string]interface{}{"ok": true, "modal": "closed"}, nil
}

// domClickByText runs the maoyanDomClickScriptTemplate and returns true if an
// element with one of the exact texts was .click()-ed.
func domClickByText(s *cdpSession, texts []string, maxWidth int) bool {
	tj, _ := json.Marshal(texts)
	script := substitute(maoyanDomClickScriptTemplate, map[string]string{
		"TEXTS_JSON": string(tj),
		"MAX_WIDTH":  fmt.Sprintf("%d", maxWidth),
	})
	raw, err := evalJSON(s, script)
	if err != nil {
		return false
	}
	m, ok := raw.(map[string]interface{})
	if !ok {
		return false
	}
	c, _ := m["clicked"].(bool)
	return c
}

// ── select_seat ───────────────────────────────────────────────────────────
// Port of car selectSeat: query seats → pick by rowHint/colHint → click →
// verify → autoConfirmSeat (dismiss modal loop + click 确认选座) → return.

func toolSelectSeat(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	rowHint, _ := getStr(args, "rowHint")
	colHint, _ := getStr(args, "colHint")
	if rowHint == "" {
		rowHint = "middle"
	}
	if colHint == "" {
		colHint = "center"
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	// 1. query seats
	qraw, err := evalJSON(s, maoyanSeatQueryScript)
	if err != nil {
		return nil, err
	}
	info, ok := qraw.(map[string]interface{})
	if !ok {
		return map[string]interface{}{"ok": false, "error": "query_failed"}, nil
	}
	availCount, _ := info["availableCount"].(float64)
	if int(availCount) == 0 {
		return map[string]interface{}{"ok": false, "error": "no_selectable_seats"}, nil
	}
	rows, _ := info["rows"].([]interface{})
	n := len(rows)
	cx, _ := info["centerX"].(float64)

	// 2. pick row index by hint
	var idx int
	switch rowHint {
	case "front":
		idx = int(float64(n) * 0.15)
	case "back":
		idx = int(float64(n) * 0.85)
	default:
		idx = int(float64(n) * 0.5)
	}
	if idx >= n {
		idx = n - 1
	}
	if idx < 0 {
		idx = 0
	}

	// find a row with selectable seats, expanding outward (car behavior)
	var row map[string]interface{}
	pickRow := func(i int) map[string]interface{} {
		if i < 0 || i >= n {
			return nil
		}
		if r, ok := rows[i].(map[string]interface{}); ok {
			if c, _ := r["count"].(float64); c > 0 {
				return r
			}
		}
		return nil
	}
	row = pickRow(idx)
	if row == nil {
		for d := 1; d < n; d++ {
			if r := pickRow(idx + d); r != nil {
				row = r
				break
			}
			if r := pickRow(idx - d); r != nil {
				row = r
				break
			}
		}
	}
	if row == nil {
		return map[string]interface{}{"ok": false, "error": "no_row_found"}, nil
	}

	seats, _ := row["seats"].([]interface{})
	allSeats, _ := row["allSeats"].([]interface{})
	if len(seats) == 0 {
		return map[string]interface{}{"ok": false, "error": "no_selectable_seats"}, nil
	}

	// 3. pick seat within row by colHint
	bestIdx := 0
	switch colHint {
	case "left":
		bestIdx = 0
	case "right":
		bestIdx = len(seats) - 1
	default:
		// nearest to center x
		bestIdx = 0
		bestSeatX, _ := seats[0].(map[string]interface{})
		minDist := absFloat(toF(bestSeatX["x"]) - cx)
		for i := 1; i < len(seats); i++ {
			sm, _ := seats[i].(map[string]interface{})
			d := absFloat(toF(sm["x"]) - cx)
			if d < minDist {
				minDist = d
				bestIdx = i
			}
		}
	}

	best, _ := seats[bestIdx].(map[string]interface{})
	bestX := toF(best["x"])
	bestY := toF(best["y"])

	// 4. wouldIsolate check — if picking bestX leaves a single gap, slide
	if len(allSeats) > 1 && wouldIsolate(allSeats, bestX) {
		for d := 1; d < len(seats); d++ {
			hi := bestIdx + d
			lo := bestIdx - d
			if hi < len(seats) {
				sm, _ := seats[hi].(map[string]interface{})
				if !wouldIsolate(allSeats, toF(sm["x"])) {
					best = sm
					bestX = toF(sm["x"])
					bestY = toF(sm["y"])
					break
				}
			}
			if lo >= 0 {
				sm, _ := seats[lo].(map[string]interface{})
				if !wouldIsolate(allSeats, toF(sm["x"])) {
					best = sm
					bestX = toF(sm["x"])
					bestY = toF(sm["y"])
					break
				}
			}
		}
	}

	// 5. click the seat (DOM click by class first? car uses domClick.at which is
	//    coordinate-based; on PC dispatchClick is reliable, use it)
	if err := s.dispatchClick(bestX, bestY); err != nil {
		return nil, err
	}
	time.Sleep(1200 * time.Millisecond)

	// 6. verify selection
	vraw, _ := evalJSON(s, maoyanSeatVerifyScript)
	v, _ := vraw.(map[string]interface{})
	selectedNow := 0
	if v != nil {
		selectedNow = int(toF(v["selectedNow"]))
	}
	if selectedNow == 0 {
		// retry click once
		if err := s.dispatchClick(bestX, bestY); err != nil {
			return nil, err
		}
		time.Sleep(1500 * time.Millisecond)
		vraw, _ = evalJSON(s, maoyanSeatVerifyScript)
		v, _ = vraw.(map[string]interface{})
		if v != nil {
			selectedNow = int(toF(v["selectedNow"]))
		}
	}

	if selectedNow > 0 {
		var orderBtn map[string]interface{}
		if v != nil {
			orderBtn, _ = v["orderBtn"].(map[string]interface{})
		}
		confirmed := autoConfirmSeat(s, orderBtn)
		// isolated-seat retry path: car deselects and tries adjacent
		if cerr, _ := confirmed["error"].(string); cerr == "isolated_seat" {
			evalRaw(s, `(function(){ var s=document.querySelector('.seat.selected'); if(s) s.click(); })()`)
			time.Sleep(500 * time.Millisecond)
			altIdx := bestIdx + 1
			if altIdx >= len(seats) {
				altIdx = bestIdx - 1
			}
			if altIdx >= 0 && altIdx < len(seats) {
				alt, _ := seats[altIdx].(map[string]interface{})
				ax := toF(alt["x"])
				ay := toF(alt["y"])
				s.dispatchClick(ax, ay)
				time.Sleep(1500 * time.Millisecond)
				vraw2, _ := evalJSON(s, maoyanSeatVerifyScript)
				v2, _ := vraw2.(map[string]interface{})
				if v2 != nil {
					if sn := int(toF(v2["selectedNow"])); sn > 0 {
						ob2, _ := v2["orderBtn"].(map[string]interface{})
						c2 := autoConfirmSeat(s, ob2)
						return map[string]interface{}{
							"ok":           true,
							"selectedNow":  sn,
							"price":        v2["price"],
							"orderBtn":     v2["orderBtn"],
							"chosenRowIdx": row["rowIdx"],
							"confirmed":    c2,
						}, nil
					}
				}
			}
		}
		return map[string]interface{}{
			"ok":            true,
			"selectedNow":   selectedNow,
			"price":         iface(v, "price"),
			"orderBtn":      v["orderBtn"],
			"chosenRowIdx":  row["rowIdx"],
			"chosenSeatX":   bestX,
			"confirmed":     confirmed,
		}, nil
	}

	// fallback: CSS selector click
	evalRaw(s, `document.querySelector('span.seat.selectable') && document.querySelector('span.seat.selectable').click();`)
	time.Sleep(1500 * time.Millisecond)
	vraw3, _ := evalJSON(s, maoyanSeatVerifyScript)
	v3, _ := vraw3.(map[string]interface{})
	if v3 != nil {
		if sn := int(toF(v3["selectedNow"])); sn > 0 {
			ob3, _ := v3["orderBtn"].(map[string]interface{})
			return map[string]interface{}{
				"ok":            true,
				"selectedNow":   sn,
				"price":         v3["price"],
				"orderBtn":      v3["orderBtn"],
				"chosenRowIdx":  row["rowIdx"],
				"fallback":      true,
				"confirmed":     autoConfirmSeat(s, ob3),
			}, nil
		}
	}
	return map[string]interface{}{"ok": false, "error": "click_no_effect", "availableCount": availCount}, nil
}

// autoConfirmSeat mirrors car mcp.js autoConfirmSeat: up to 5 attempts,
// each checks URL→/order/confirm, dismisses "我知道了" modal, then clicks
// 确认选座 if not blocked.
func autoConfirmSeat(s *cdpSession, orderBtn map[string]interface{}) map[string]interface{} {
	for attempt := 0; attempt < 5; attempt++ {
		// 1. URL already navigated?
		urlStr, _ := evalString(s, `location.href`)
		if strings.Contains(urlStr, "/order/confirm") {
			return map[string]interface{}{"ok": true, "orderUrl": urlStr}
		}
		// 2. dismiss modal
		modalStr, _ := evalString(s, maoyanDismissModalScript)
		if modalStr == "isolated-warning" {
			return map[string]interface{}{"ok": false, "error": "isolated_seat"}
		}
		if modalStr != "no-modal" && modalStr != "no-btn" {
			var coord map[string]interface{}
			if err := json.Unmarshal([]byte(modalStr), &coord); err == nil {
				// DOM click by text first, coord fallback
				if !domClickByText(s, []string{"我知道了"}, 400) {
					x := toF(coord["x"])
					y := toF(coord["y"])
					s.dispatchClick(x, y)
				}
				time.Sleep(800 * time.Millisecond)
				continue
			}
		}
		// 3. check confirm btn blocked
		craw, _ := evalJSON(s, maoyanCheckConfirmBtnScript)
		check, _ := craw.(map[string]interface{})
		blocked, _ := check["blocked"].(bool)
		btnX := toF(check["x"])
		btnY := toF(check["y"])
		if btnX == 0 && orderBtn != nil {
			btnX = toF(orderBtn["x"])
			btnY = toF(orderBtn["y"])
		}
		if !blocked {
			// DOM click by text "确认选座" first, coord fallback
			if !domClickByText(s, []string{"确认选座"}, 500) {
				if btnX > 0 && btnY > 0 {
					s.dispatchClick(btnX, btnY)
				}
			}
			time.Sleep(2500 * time.Millisecond)
		}
	}
	finalUrl, _ := evalString(s, `location.href`)
	if strings.Contains(finalUrl, "/order/confirm") {
		return map[string]interface{}{"ok": true, "orderUrl": finalUrl}
	}
	return map[string]interface{}{"ok": false, "orderUrl": finalUrl}
}

// ── dom_click ─────────────────────────────────────────────────────────────
// Exposed as a tool: click element by exact visible text.

func toolDomClick(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	text, ok := getStr(args, "text")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing text"}
	}
	maxW := 500
	if v, ok := getNum(args, "maxWidth"); ok {
		maxW = int(v)
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	tj, _ := json.Marshal([]string{text})
	script := substitute(maoyanDomClickScriptTemplate, map[string]string{
		"TEXTS_JSON": string(tj),
		"MAX_WIDTH":  fmt.Sprintf("%d", maxW),
	})
	raw, err := evalJSON(s, script)
	if err != nil {
		return nil, err
	}
	return raw, nil
}

// ── small numeric helpers ─────────────────────────────────────────────────

func toF(v interface{}) float64 {
	switch n := v.(type) {
	case float64:
		return n
	case int:
		return float64(n)
	case int64:
		return float64(n)
	}
	return 0
}

func absFloat(x float64) float64 {
	if x < 0 {
		return -x
	}
	return x
}

func iface(m map[string]interface{}, key string) interface{} {
	if m == nil {
		return nil
	}
	return m[key]
}

// wouldIsolate mirrors car selectSeat's wouldIsolate: after placing a pick at
// seatX, does any contiguous free segment in the full row have length exactly 1?
func wouldIsolate(allSeats []interface{}, seatX float64) bool {
	occupied := make([]bool, len(allSeats))
	for i, s := range allSeats {
		sm, _ := s.(map[string]interface{})
		avail := false
		if sm != nil {
			avail, _ = sm["avail"].(bool)
		}
		sx := toF(sm["x"])
		occupied[i] = !avail || sx == seatX
	}
	i := 0
	for i < len(occupied) {
		if !occupied[i] {
			j := i
			for j < len(occupied) && !occupied[j] {
				j++
			}
			if j-i == 1 {
				return true
			}
			i = j
		} else {
			i++
		}
	}
	return false
}
