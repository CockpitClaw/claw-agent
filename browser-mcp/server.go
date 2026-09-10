package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

type toolError struct {
	Code    int
	Message string
}

func (e *toolError) Error() string { return e.Message }

const (
	mcpPort = 19002
)

var mcpAuthToken = os.Getenv("MCP_AUTH_TOKEN")

var authHeaderValue = "Bearer " + mcpAuthToken

type jsonrpcReq struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params"`
}

type toolDef struct {
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	InputSchema map[string]interface{} `json:"inputSchema"`
}

func strProp(desc string) map[string]interface{} {
	return map[string]interface{}{"type": "string", "description": desc}
}
func numProp(desc string) map[string]interface{} {
	return map[string]interface{}{"type": "number", "description": desc}
}
func boolProp(desc string) map[string]interface{} {
	return map[string]interface{}{"type": "boolean", "description": desc}
}

func requiredArr(items ...string) []string { return items }

func getToolDefs() []toolDef {
	return []toolDef{
		{
			Name:        "list_pages",
			Description: "List all open Chrome tabs. Each page has an id, url, title. Call this first to get a pageId before calling other tools.",
			InputSchema: map[string]interface{}{"type": "object"},
		},
		{
			Name:        "new_page",
			Description: "Open a new Chrome tab and navigate it to the given URL. Returns the new pageId. Use this when list_pages returns no pages (all tabs closed) or when you need a fresh tab instead of reusing an existing one.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"url": strProp("URL to open in the new tab"),
				},
				"required": requiredArr("url"),
				"type":     "object",
			},
		},
		{
			Name:        "navigate_page",
			Description: "Navigate a page to a new URL.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId": numProp("Page ID from list_pages"),
					"url":    strProp("URL to navigate to"),
				},
				"required": requiredArr("pageId", "url"),
				"type":     "object",
			},
		},
		{
			Name: "take_snapshot",
			Description: "Capture a semantic snapshot of the page (works on all sites including div-soup). " +
				"Returns a pruned tree where every visible node is classified as: " +
				"[action] (large clickable), [option] (small clickable), [content] (prominent text), " +
				"or [img-group:gallery]. All classified nodes carry a [ref=eN] token usable by click/fill/hover.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId": numProp("Page ID"),
					"target": strProp("Optional: element ref (e.g. 'e5') or CSS selector to scope the snapshot. Omit for full page."),
					"depth":  numProp("Optional: max depth. Default 12."),
					"boxes":  boolProp("Optional: include [box=x,y,w,h]. Default false."),
				},
				"required": requiredArr("pageId"),
				"type":     "object",
			},
		},
		{
			Name:        "take_screenshot",
			Description: "Take a PNG screenshot and return base64 + metadata. Returns viewportWidth, viewportHeight (CSS px), devicePixelRatio, screenshotWidth, screenshotHeight (actual px). To convert screenshot pixel coords to click coords: css_x = screenshot_x / devicePixelRatio, css_y = screenshot_y / devicePixelRatio.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name: "click",
			Description: "Click an element or coordinate. " +
				"Option A — pass `target`: a [ref=eN] from the last snapshot or a CSS selector. " +
				"Option B — pass `x`/`y` (viewport px). Native CDP mousePressed/Released, isTrusted=true.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId":      numProp("Page ID"),
					"target":      strProp("Element ref (e.g. 'e7') or CSS selector. Mutually exclusive with x/y."),
					"x":           numProp("Viewport X coordinate."),
					"y":           numProp("Viewport Y coordinate."),
					"doubleClick": boolProp("Optional: double-click. Default false."),
				},
				"required": requiredArr("pageId"),
				"type":     "object",
			},
		},
		{
			Name:        "type",
			Description: "Type text into the focused or targeted editable element by dispatching keyboard events.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId": numProp("Page ID"),
					"target": strProp("Element ref (e.g. 'e3') or CSS selector"),
					"text":   strProp("Text to type"),
					"submit": boolProp("Optional: press Enter after. Default false."),
				},
				"required": requiredArr("pageId", "target", "text"),
				"type":     "object",
			},
		},
		{
			Name:        "fill",
			Description: "Directly set the value of an input/textarea and dispatch input event. Faster than type.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId": numProp("Page ID"),
					"target": strProp("Element ref or CSS selector"),
					"value":  strProp("Value to fill in"),
				},
				"required": requiredArr("pageId", "target", "value"),
				"type":     "object",
			},
		},
		{
			Name:        "evaluate_script",
			Description: "Execute arbitrary JavaScript in the page main frame and return the result.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId": numProp("Page ID"),
					"script": strProp("JavaScript expression or IIFE"),
				},
				"required": requiredArr("pageId", "script"),
				"type":     "object",
			},
		},
		{
			Name: "evaluate_script_in_frame",
			Description: "Execute JavaScript inside a cross-origin iframe by targeting its CDP execution context. " +
				"Useful for reading or interacting with iframes that block normal evaluate_script access " +
				"(e.g. JD payment popup at https://pc-settlement-lite-pro.pf.jd.com). " +
				"Waits up to 8s for the frame context to appear after the iframe loads.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId":   numProp("Page ID"),
					"frameUrl": strProp("Origin of the target iframe, e.g. \"https://pc-settlement-lite-pro.pf.jd.com\""),
					"script":  strProp("JavaScript expression or IIFE to evaluate inside the iframe"),
				},
				"required": requiredArr("pageId", "frameUrl", "script"),
				"type":     "object",
			},
		},
		{
			Name:        "scroll_down",
			Description: "Scroll the page down by ~40% of the viewport height.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		// ── maoyan high-level helpers (ported from car mcp.js) ──
		{
			Name:        "maoyan_get_cinemas",
			Description: "Maoyan: scroll cinema list page and return cinemas with cinemaId/name/address/price/distance/x/y. Call after navigate to /cinemas?movieId=. Returns {cinemas:[...]}.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "maoyan_get_shows",
			Description: "Maoyan: on a /cinema/<id>?movieId= page, extract today's/tomorrow's showtimes filtered by movieId+date. Returns {count, shows:[{time,timeMin,passed,href,x,y}], targetDate, movieId}. date: \"today\"|\"tomorrow\"|YYYYMMDD.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId":  numProp("Page ID"),
					"date":    strProp("\"today\" (default) | \"tomorrow\" | YYYYMMDD"),
					"movieId": strProp("Optional: filmId. If omitted, extracted from current URL."),
				},
				"required": requiredArr("pageId"),
				"type":     "object",
			},
		},
		{
			Name:        "maoyan_click_show",
			Description: "Maoyan: navigate to the xseats seat-map page for a given showtime via window.location.href (preserves session). Waits 4s for /xseats/ to load. Returns {ok:true,xseatsUrl} or {ok:false,error}. time must come from maoyan_get_shows.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId":  numProp("Page ID"),
					"time":    strProp("Showtime like \"19:00\", must match maoyan_get_shows output"),
					"movieId": strProp("Optional filmId; extracted from URL if missing"),
					"date":    strProp("\"today\"|\"tomorrow\"|YYYYMMDD; default today"),
				},
				"required": requiredArr("pageId", "time"),
				"type":     "object",
			},
		},
		{
			Name:        "maoyan_query_seats",
			Description: "Maoyan seat-map: return row layout, availableCount, rowCount, orderBtn coords, centerX. Call after maoyan_click_show lands on /xseats/.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "maoyan_select_seat",
			Description: "Maoyan: one-shot seat selection + confirm. Picks a seat by rowHint(front/middle/back) + colHint(left/center/right), clicks it, dismisses the \"我知道了\" modal, clicks 确认选座, waits for /order/confirm. Returns {ok, selectedNow, price, orderBtn, chosenRowIdx, confirmed:{ok,orderUrl}}.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId":  numProp("Page ID"),
					"rowHint": strProp("front | middle | back (default middle)"),
					"colHint": strProp("left | center | right (default center)"),
				},
				"required": requiredArr("pageId"),
				"type":     "object",
			},
		},
		{
			Name:        "maoyan_dismiss_modal",
			Description: "Maoyan: close the \"我知道了\" entry-notice modal that appears on xseats pages. Returns {ok, modal:\"none\"|\"closed\"} or {ok:false,error:\"isolated_seat\"}.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "dom_click",
			Description: "Click a visible element by exact innerText match. More reliable than coordinate click on SPAs where text elements have real onclick handlers. Returns {clicked, text, x, y} or {clicked:false}.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId":    numProp("Page ID"),
					"text":      strProp("Exact visible innerText of the element to click"),
					"maxWidth":  numProp("Optional: max element width to match, filters out huge containers. Default 500."),
				},
				"required": requiredArr("pageId", "text"),
				"type":     "object",
			},
		},
		// ── JD size helpers (detail page) ──
		{
			Name:        "jd_get_sizes",
			Description: "JD: on a product detail page (item.jd.com / npcitem.jd.hk), poll up to 3s and return the selectable size list with coords + selected/outOfStock flags. Returns {found, sizes:[{text,x,y,selected,outOfStock}]}. Call BEFORE clicking 立即购买 — sizes live on the detail page, not on trade.jd.hk.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "jd_select_size",
			Description: "JD: click the size element matching `size` text on a product detail page. DOM .click() first, falls back to coordinate dispatchClick. Waits 800ms and verifies selection (checks --sel/active). Returns {ok:true,selected} or {ok:false,error}.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId": numProp("Page ID"),
					"size":   strProp("Size token to select, e.g. \"43\" or \"XL\". Must match a text from jd_get_sizes."),
				},
				"required": requiredArr("pageId", "size"),
				"type":     "object",
			},
		},
		{
			Name:        "jd_find_pay_button",
			Description: "JD: on order-confirmation page (URL contains trade.jd), find the pay/submit button with built-in render-wait + retry. Auto-detects form: single-page (立即支付 directly), two-phase (提交订单 then later 立即支付), or iframe popup (pc-settlement-lite-pro). Polls up to 8s for button to render. Returns {form: 'single_page'|'two_phase_submit'|'iframe', btn:{txt,x,y}} or {error:'not_found'}. Call this instead of writing your own evaluate_script to find the pay button.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "scroll_up",
			Description: "Scroll the page up by ~40% of the viewport height.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		// ── generic helper tools (prevent agent from writing raw JS) ──
		{
			Name:        "search_and_extract_links",
			Description: "Extract clickable links from the current page. Returns [{text, href, x, y}]. Optionally filter by CSS selector (e.g. 'a[href*=\"/track/\"]' for Spotify tracks). Without selector, extracts all visible <a> tags. Use this instead of writing raw querySelectorAll JS.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId":   numProp("Page ID"),
					"selector": strProp("Optional: CSS selector to filter links. E.g. 'a[href*=\"/track/\"]', 'ytd-video-renderer a'. Omit for all links."),
				},
				"required": requiredArr("pageId"),
				"type":     "object",
			},
		},
		{
			Name:        "media_status",
			Description: "Check the current media playback state. Detects <video> or <audio> elements and returns {found, type, paused, playing, currentTime, duration, muted, volume, src}. Use this instead of writing raw querySelector('video') JS.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "media_play",
			Description: "Attempt to play the current media. Tries: 1) .play() API, 2) click play button (common selectors), 3) click player container. Returns {ok, method}. Verify with media_status after calling.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "media_pause",
			Description: "Pause the current media. Tries .pause() API first, then clicks a pause button. Returns {ok, method}.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "wait_for_element",
			Description: "Wait for a CSS selector to appear in the DOM. Polls every 300ms up to timeout. Returns {found, waited, visible, x, y} or {found:false, error:'timeout'}. Use this for SPA pages where elements render asynchronously.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId":   numProp("Page ID"),
					"selector": strProp("CSS selector to wait for, e.g. 'video', 'button[aria-label*=Play]', '.search-result'"),
					"timeout":  numProp("Optional: max wait in ms. Default 5000."),
				},
				"required": requiredArr("pageId", "selector"),
				"type":     "object",
			},
		},
		{
			Name:        "dismiss_overlays",
			Description: "Close cookie consent banners, modal dialogs, and popup overlays. Tries 20+ common selectors (Accept/Agree/Close buttons, cookie banners, modal close buttons). Returns {dismissed, details}. Call this after navigate to international sites (YouTube, Spotify, etc.) that show cookie consent.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "get_page_text",
			Description: "Extract visible text and actionable elements from the page. Returns {title, url, headings, paragraphs, main_text, text_length, visible_buttons, canvas_count, large_image_count, render_hint}. main_text is body.innerText (all rendered text, 4000 chars cap) — use it for SPA pages (Baidu/JD/React apps) where headings+paragraphs are empty. visible_buttons lists clickable button texts (确认选座/去付款/立即支付 etc.) so you can pick the next click without snapshot. render_hint tells you what to do when main_text is empty (canvas/image-rendered page → use take_snapshot or platform tools; do NOT loop get_page_text).",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "detect_login_wall",
			Description: "Detect login walls and preview mode. Returns {has_login_wall, preview_mode, signals, url}. has_login_wall=true means real login wall (URL pattern, password field, login modal, or text like '请登录/需要登录/Sign in to') → HARD STOP. preview_mode=true means only 30s preview available (e.g. '试听') but playback CAN still work — do NOT stop, try click_play_button.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "click_play_button",
			Description: "Find and click a play button on the page. Handles custom players (Apple Music, Spotify) that don't expose <audio>/<video> elements. Tries common play-button selectors: [class*=play-button], [data-testid*=play-button], button[aria-label*=Play]. Returns {ok, method}. Verify with media_status after.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{"pageId": numProp("Page ID")},
				"required":   requiredArr("pageId"),
				"type":        "object",
			},
		},
		{
			Name:        "hover",
			Description: "Hover the mouse over an element or coordinate. Triggers CSS :hover states, hover-reveal menus, and tooltips. Option A — pass `target`: a [ref=eN] from snapshot or CSS selector. Option B — pass `x`/`y` (viewport CSS px).",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId": numProp("Page ID"),
					"target": strProp("Element ref (e.g. 'e7') or CSS selector. Mutually exclusive with x/y."),
					"x":      numProp("Viewport X coordinate."),
					"y":      numProp("Viewport Y coordinate."),
				},
				"required": requiredArr("pageId"),
				"type":     "object",
			},
		},
		{
			Name:        "drag",
			Description: "Drag from one coordinate to another (e.g. slider, progress bar). Uses mousePressed → mouseMoved → mouseReleased with smooth interpolation.",
			InputSchema: map[string]interface{}{
				"properties": map[string]interface{}{
					"pageId": numProp("Page ID"),
					"fromX":  numProp("Start X coordinate (viewport CSS px)"),
					"fromY":  numProp("Start Y coordinate (viewport CSS px)"),
					"toX":    numProp("End X coordinate (viewport CSS px)"),
					"toY":    numProp("End Y coordinate (viewport CSS px)"),
					"steps":  numProp("Optional: number of interpolation steps. Default 10."),
				},
				"required": requiredArr("pageId", "fromX", "fromY", "toX", "toY"),
				"type":     "object",
			},
		},
	}
}

func rpcOk(id json.RawMessage, result interface{}) map[string]interface{} {
	return map[string]interface{}{"jsonrpc": "2.0", "id": id, "result": result}
}

func errResp(id json.RawMessage, code int, message string) map[string]interface{} {
	return map[string]interface{}{"jsonrpc": "2.0", "id": id, "error": map[string]interface{}{"code": code, "message": message}}
}

func processRequest(req jsonrpcReq) map[string]interface{} {
	id := req.ID
	switch req.Method {
	case "initialize":
		return rpcOk(id, map[string]interface{}{
			"protocolVersion": "2024-11-05",
			"capabilities":    map[string]interface{}{"tools": map[string]interface{}{}},
			"serverInfo":      map[string]interface{}{"name": "browser-mcp", "version": "0.1.0"},
		})
	case "notifications/initialized":
		return rpcOk(id, map[string]interface{}{})
	case "ping":
		return rpcOk(id, map[string]interface{}{})
	case "tools/list":
		return rpcOk(id, map[string]interface{}{"tools": getToolDefs()})
	case "tools/call":
		var p struct {
			Name      string                 `json:"name"`
			Arguments map[string]interface{} `json:"arguments"`
		}
		if err := json.Unmarshal(req.Params, &p); err != nil {
			return errResp(id, -32700, "parse error: "+err.Error())
		}
		if p.Name == "" {
			return errResp(id, -32602, "Missing tool name")
		}
		if p.Arguments == nil {
			p.Arguments = map[string]interface{}{}
		}
		result, err := callTool(p.Name, p.Arguments)
		if err != nil {
			if te, ok := err.(*toolError); ok {
				return errResp(id, te.Code, te.Message)
			}
			return errResp(id, -32000, "Tool error: "+err.Error())
		}
		// screenshot returns image content with coordinate metadata
		if img, ok := result.(map[string]interface{}); ok && img["type"] == "image" {
			item := map[string]interface{}{
				"type":     "image",
				"data":     img["data"],
				"mimeType": img["mimeType"],
			}
			// Carry through coordinate metadata for screenshot→click conversion
			for _, k := range []string{"viewportWidth", "viewportHeight", "devicePixelRatio", "screenshotWidth", "screenshotHeight"} {
				if v, exists := img[k]; exists {
					item[k] = v
				}
			}
			return rpcOk(id, map[string]interface{}{
				"content": []map[string]interface{}{item},
			})
		}
		// wrap text result
		jsonBytes, _ := json.Marshal(result)
		return rpcOk(id, map[string]interface{}{
			"content": []map[string]interface{}{{
				"type": "text",
				"text": string(jsonBytes),
			}},
		})
	case "shutdown":
		return rpcOk(id, map[string]interface{}{})
	default:
		return errResp(id, -32601, "Method not found: "+req.Method)
	}
}

// ── Tool dispatch ─────────────────────────────────────────────────────

func callTool(name string, args map[string]interface{}) (interface{}, error) {
	switch name {
	case "list_pages":
		return toolListPages()
	case "new_page":
		return toolNewPage(args)
	case "navigate_page":
		return toolNavigate(args)
	case "take_snapshot":
		return toolSnapshot(args)
	case "take_screenshot":
		return toolScreenshot(args)
	case "click":
		return toolClick(args)
	case "type":
		return toolType(args)
	case "fill":
		return toolFill(args)
	case "evaluate_script":
		return toolEval(args)
	case "evaluate_script_in_frame":
		return toolEvalInFrame(args)
	case "scroll_down":
		return toolScroll(args, "down")
	case "scroll_up":
		return toolScroll(args, "up")
	case "search_and_extract_links":
		return toolSearchExtractLinks(args)
	case "media_status":
		return toolMediaStatus(args)
	case "media_play":
		return toolMediaPlay(args)
	case "media_pause":
		return toolMediaPause(args)
	case "wait_for_element":
		return toolWaitForElement(args)
	case "dismiss_overlays":
		return toolDismissOverlays(args)
	case "get_page_text":
		return toolGetPageText(args)
	case "detect_login_wall":
		return toolDetectLoginWall(args)
	case "click_play_button":
		return toolClickPlayButton(args)
	case "hover":
		return toolHover(args)
	case "drag":
		return toolDrag(args)
	case "maoyan_get_cinemas":
		return toolGetCinemas(args)
	case "maoyan_get_shows":
		return toolGetShows(args)
	case "maoyan_click_show":
		return toolClickShow(args)
	case "maoyan_query_seats":
		return toolQuerySeats(args)
	case "maoyan_select_seat":
		return toolSelectSeat(args)
	case "maoyan_dismiss_modal":
		return toolDismissModal(args)
	case "dom_click":
		return toolDomClick(args)
	case "jd_get_sizes":
		return toolJDGetSizes(args)
	case "jd_select_size":
		return toolJDSelectSize(args)
	case "jd_find_pay_button":
		return toolJDFindPayButton(args)
	default:
		return nil, &toolError{Code: -32601, Message: "Unknown tool: " + name}
	}
}

func toolListPages() (interface{}, error) {
	pages, err := registry.refresh()
	if err != nil {
		return nil, err
	}
	type pageItem struct {
		ID    int    `json:"id"`
		URL   string `json:"url"`
		Title string `json:"title"`
	}
	out := make([]pageItem, len(pages))
	for i, p := range pages {
		out[i] = pageItem{ID: p.PageID, URL: p.URL, Title: p.Title}
	}
	return map[string]interface{}{"pages": out}, nil
}

func toolNewPage(args map[string]interface{}) (interface{}, error) {
	url, ok := getStr(args, "url")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing url"}
	}
	// Guard: refuse to open a new tab when page tabs already exist.
	// Agents kept calling new_page instead of reusing the existing tab,
	// leaving duplicate tabs (old JD payment + new YouTube) that confused
	// pageId tracking on the next case. Force reuse via navigate_page.
	// listPages() already filters Type=="page", so any entry in `existing`
	// is a real browser tab — no need to check p.Type here.
	existing, _ := registry.refresh()
	if len(existing) > 0 {
		firstPageID := existing[0].PageID
		return map[string]interface{}{
			"pageId":  firstPageID,
			"reused":  true,
			"warning": "已有 " + strconv.Itoa(len(existing)) + " 个标签页，未新开。请在已有 pageId=" + strconv.Itoa(firstPageID) + " 上用 navigate_page 导航，禁止 new_page。",
			"hint":    "复用现有标签页：navigate_page(pageId=" + strconv.Itoa(firstPageID) + ", url=\"" + url + "\")",
		}, nil
	}
	if _, err := createPage(url); err != nil {
		return nil, &toolError{Code: -32000, Message: "create page: " + err.Error()}
	}
	// Refresh registry so the new tab gets a pageId assigned.
	pages, err := registry.refresh()
	if err != nil {
		return nil, err
	}
	// The newly created tab is the one whose URL matches (or the last page
	// in the list, since /json/new appends). Match by URL for accuracy.
	for _, p := range pages {
		if p.URL == url || strings.HasPrefix(p.URL, strings.TrimRight(url, "/")) {
			return map[string]interface{}{
				"pageId": p.PageID,
				"url":    p.URL,
				"title":  p.Title,
			}, nil
		}
	}
	// Fallback: return the last page (most likely the new one).
	if len(pages) > 0 {
		last := pages[len(pages)-1]
		return map[string]interface{}{
			"pageId": last.PageID,
			"url":    last.URL,
			"title":  last.Title,
		}, nil
	}
	return nil, &toolError{Code: -32000, Message: "new page created but not found in list"}
}

func getNum(args map[string]interface{}, key string) (float64, bool) {
	v, ok := args[key]
	if !ok {
		return 0, false
	}
	switch n := v.(type) {
	case float64:
		return n, true
	case int:
		return float64(n), true
	case json.Number:
		f, err := n.Float64()
		return f, err == nil
	}
	return 0, false
}

func getStr(args map[string]interface{}, key string) (string, bool) {
	v, ok := args[key]
	if !ok {
		return "", false
	}
	s, ok := v.(string)
	return s, ok
}

func getBool(args map[string]interface{}, key string) bool {
	v, ok := args[key]
	if !ok {
		return false
	}
	b, _ := v.(bool)
	return b
}

func requirePageID(args map[string]interface{}) (int, error) {
	v, ok := getNum(args, "pageId")
	if !ok {
		return 0, &toolError{Code: -32602, Message: "Missing pageId"}
	}
	return int(v), nil
}

func toolNavigate(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	url, ok := getStr(args, "url")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing url"}
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	success, errText, err := s.navigate(url)
	if err != nil {
		return nil, err
	}
	r := map[string]interface{}{"success": success}
	if errText != "" {
		r["error"] = errText
	}
	return r, nil
}

func toolSnapshot(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	depth := 12
	if v, ok := getNum(args, "depth"); ok {
		depth = int(v)
	}
	target, _ := getStr(args, "target")
	boxes := getBool(args, "boxes")
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	js := snapshotJS(depth, boxes, target)
	v, err := s.evaluateJS(js, 15*time.Second)
	if err != nil {
		return nil, err
	}
	snap, _ := v.(string)
	return map[string]interface{}{"snapshot": snap}, nil
}

func toolScreenshot(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	bytes, err := s.screenshot()
	if err != nil {
		return nil, err
	}
	// Decode PNG header to get pixel dimensions (width/height in actual pixels).
	// PNG header: byte 16-19 = width (BE uint32), 20-23 = height (BE uint32).
	pngW, pngH := 0, 0
	if len(bytes) > 23 {
		pngW = int(bytes[16])<<24 | int(bytes[17])<<16 | int(bytes[18])<<8 | int(bytes[19])
		pngH = int(bytes[20])<<24 | int(bytes[21])<<16 | int(bytes[22])<<8 | int(bytes[23])
	}
	return map[string]interface{}{
		"type":             "image",
		"data":             base64Encode(bytes),
		"mimeType":         "image/png",
		"viewportWidth":    s.vpWidth,
		"viewportHeight":   s.vpHeight,
		"devicePixelRatio": s.dpr,
		"screenshotWidth":  pngW,
		"screenshotHeight": pngH,
	}, nil
}

func toolClick(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	doubleClick := getBool(args, "doubleClick")

	if target, ok := getStr(args, "target"); ok && target != "" {
		prep := fmt.Sprintf(`
(function() {
  var ref = %s;
  var el = (window.__mcpRefMap && window.__mcpRefMap[ref])
         || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
  if (!el) return JSON.stringify({ found: false });
  el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  var rect = el.getBoundingClientRect();
  var cx = rect.left + rect.width / 2;
  var cy = rect.top + rect.height / 2;
  return JSON.stringify({ found: true, cx: cx, cy: cy });
})()
`, strconv.Quote(target))
		v, err := s.evaluateJS(prep, 5*time.Second)
		if err != nil {
			return nil, err
		}
		str, _ := v.(string)
		var p struct {
			Found bool    `json:"found"`
			CX    float64 `json:"cx"`
			CY    float64 `json:"cy"`
		}
		if err := json.Unmarshal([]byte(str), &p); err != nil {
			return nil, &toolError{Code: -32000, Message: "click: bad prep result: " + str}
		}
		if !p.Found {
			return map[string]interface{}{"success": false, "error": "Element not found: " + target}, nil
		}
		if err := s.dispatchClick(p.CX, p.CY); err != nil {
			return nil, err
		}
		if doubleClick {
			s.dispatchClick(p.CX, p.CY)
		}
		return map[string]interface{}{"success": true, "mode": "native", "cx": p.CX, "cy": p.CY}, nil
	}

	// coordinate mode
	xv, xok := getNum(args, "x")
	yv, yok := getNum(args, "y")
	if !xok || !yok {
		return nil, &toolError{Code: -32602, Message: "click: requires either target or x/y"}
	}
	if err := s.dispatchClick(xv, yv); err != nil {
		return nil, err
	}
	if doubleClick {
		s.dispatchClick(xv, yv)
	}
	return map[string]interface{}{"success": true, "mode": "coordinate"}, nil
}

func toolType(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	target, ok := getStr(args, "target")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing target"}
	}
	text, ok := getStr(args, "text")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing text"}
	}
	submit := getBool(args, "submit")
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	js := fmt.Sprintf(`
(function() {
  var ref = %s;
  var el  = (window.__mcpRefMap && window.__mcpRefMap[ref])
          || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
  if (!el) return JSON.stringify({ success: false, error: 'Element not found: ' + ref });
  el.focus();
  var chars = %s;
  for (var i = 0; i < chars.length; i++) {
    var c = chars[i];
    el.dispatchEvent(new KeyboardEvent('keydown',  { key:c, bubbles:true, cancelable:true }));
    el.dispatchEvent(new KeyboardEvent('keypress', { key:c, bubbles:true, cancelable:true }));
    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable) {
      if (el.isContentEditable) {
        el.textContent += c;
      } else {
        var v = el.value || '';
        var s = el.selectionStart !== undefined ? el.selectionStart : v.length;
        var e2 = el.selectionEnd   !== undefined ? el.selectionEnd   : v.length;
        el.value = v.substring(0, s) + c + v.substring(e2);
        el.selectionStart = el.selectionEnd = s + 1;
      }
      el.dispatchEvent(new InputEvent('input', { bubbles:true, cancelable:true }));
    }
    el.dispatchEvent(new KeyboardEvent('keyup',   { key:c, bubbles:true, cancelable:true }));
  }
  if (%t) {
    el.dispatchEvent(new KeyboardEvent('keydown', { key:'Enter', bubbles:true, cancelable:true }));
    var form = el.closest ? el.closest('form') : null;
    if (form) form.dispatchEvent(new Event('submit', { bubbles:true, cancelable:true }));
  }
  return JSON.stringify({ success: true });
})()
`, strconv.Quote(target), strconv.Quote(text), submit)
	v, err := s.evaluateJS(js, 10*time.Second)
	if err != nil {
		return nil, err
	}
	str, _ := v.(string)
	var r map[string]interface{}
	_ = json.Unmarshal([]byte(str), &r)
	return r, nil
}

func toolFill(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	target, ok := getStr(args, "target")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing target"}
	}
	value, ok := getStr(args, "value")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing value"}
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	js := fmt.Sprintf(`
(function() {
  var ref = %s;
  var el  = (window.__mcpRefMap && window.__mcpRefMap[ref])
          || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
  if (!el) return JSON.stringify({ success: false, error: 'Element not found: ' + ref });
  el.focus();
  el.value = %s;
  el.dispatchEvent(new Event('input',  { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return JSON.stringify({ success: true });
})()
`, strconv.Quote(target), strconv.Quote(value))
	v, err := s.evaluateJS(js, 5*time.Second)
	if err != nil {
		return nil, err
	}
	str, _ := v.(string)
	var r map[string]interface{}
	_ = json.Unmarshal([]byte(str), &r)
	return r, nil
}

func toolEval(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	script, ok := getStr(args, "script")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing script"}
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	v, err := s.evaluateJS(script, 15*time.Second)
	if err != nil {
		return nil, err
	}
	// Truncate large string results to prevent LLM context overflow.
	if str, ok := v.(string); ok && len(str) > 10000 {
		v = str[:10000] + "\n...[truncated, total " + strconv.Itoa(len(str)) + " chars]"
	}
	return map[string]interface{}{"result": v}, nil
}

func toolEvalInFrame(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	frameURL, ok := getStr(args, "frameUrl")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing frameUrl"}
	}
	script, ok := getStr(args, "script")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing script"}
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	v, err := s.evaluateInFrame(frameURL, script, 8*time.Second)
	if err != nil {
		return nil, err
	}
	return map[string]interface{}{"result": v}, nil
}

func toolScroll(args map[string]interface{}, direction string) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	// Guard: forbid scrolling on JD order-confirmation page (trade.jd.*).
	// The pay button is in a fixed bar; scrolling pushes it out of viewport
	// and wastes ReAct turns. Agent should query the button directly instead.
	url := s.currentURL()
	if strings.Contains(url, "trade.jd.com") || strings.Contains(url, "trade.jd.hk") {
		return map[string]interface{}{
			"success": false,
			"error":   "scroll_forbidden_on_order_page",
			"reason":  "订单确认页禁止滚动。按钮在固定栏，直接用 evaluate_script 查'立即支付'/'提交订单'按钮坐标后 click(x,y) 即可。无需滚动。",
			"url":     url,
		}, nil
	}
	vh := s.vpHeight
	dist := float64(vh) * 0.6
	if dist < 200 {
		dist = 200
	}
	// Use window.scrollBy instead of dispatchSwipe. The old mousePressed→
	// mouseMoved→mouseReleased swipe simulated a drag, which on text-heavy
	// pages (cinema lists, search results) selected text instead of scrolling.
	// scrollBy changes scrollY directly, bypassing all gesture simulation.
	scrollDir := "1"
	if direction == "up" {
		scrollDir = "-1"
	}
	// Try window scroll first, then fall back to scrolling the largest
	// scrollable container (some SPA pages scroll an inner div, not window).
	script := fmt.Sprintf(`(function(){
  var dist = %s * %d;
  var before = window.scrollY;
  window.scrollBy({top: dist, behavior: 'instant'});
  var after = window.scrollY;
  if (after !== before) {
    return JSON.stringify({scrolled: 'window', before: before, after: after});
  }
  // window didn't scroll — find a scrollable container
  var els = document.querySelectorAll('*');
  var best = null;
  for (var i = 0; i < els.length; i++) {
    var el = els[i];
    var st = getComputedStyle(el);
    if (st.overflowY !== 'auto' && st.overflowY !== 'scroll') continue;
    if (el.scrollHeight - el.clientHeight < 50) continue;
    if (el.clientHeight < 100) continue;
    var rect = el.getBoundingClientRect();
    if (rect.width < 200 || rect.height < 200) continue;
    if (!best || el.scrollHeight > best.scrollHeight) best = el;
  }
  if (best) {
    var b = best.scrollTop;
    best.scrollTop += dist;
    return JSON.stringify({scrolled: 'container', cls: best.className.toString().slice(0,40), before: b, after: best.scrollTop});
  }
  return JSON.stringify({scrolled: 'none', before: before, after: after});
})()`, scrollDir, int(dist))
	v, err := s.evaluateJS(script, 4*time.Second)
	if err != nil {
		return nil, err
	}
	result := map[string]interface{}{"success": true, "direction": direction, "distance": int(dist)}
	scrolled := "none"
	if str, ok := v.(string); ok {
		var parsed map[string]interface{}
		if json.Unmarshal([]byte(str), &parsed) == nil {
			result["scroll_target"] = parsed["scrolled"]
			result["scrollY_before"] = parsed["before"]
			result["scrollY_after"] = parsed["after"]
			if s, ok := parsed["scrolled"].(string); ok {
				scrolled = s
			}
			if parsed["scrolled"] == "container" {
				result["container_class"] = parsed["cls"]
			}
		}
	}
	// If window and container both failed (virtual-scroll SPAs like maoyan),
	// fall back to PageDown/PageUp key events — the page's wheel listener
	// catches these and advances the virtual list.
	if scrolled == "none" {
		key := "PageDown"
		if direction == "up" {
			key = "PageUp"
		}
		beforeY, _ := s.evaluateJS("window.scrollY", 2*time.Second)
		if err := s.dispatchKeyPress(key); err != nil {
			return nil, err
		}
		time.Sleep(300 * time.Millisecond)
		afterY, _ := s.evaluateJS("window.scrollY", 2*time.Second)
		result["scroll_target"] = "keyboard"
		result["key"] = key
		result["scrollY_before"] = beforeY
		result["scrollY_after"] = afterY
	}
	return result, nil
}

func toolHover(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}

	if target, ok := getStr(args, "target"); ok && target != "" {
		prep := fmt.Sprintf(`
(function() {
  var ref = %s;
  var el = (window.__mcpRefMap && window.__mcpRefMap[ref])
         || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
  if (!el) return JSON.stringify({ found: false });
  el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  var rect = el.getBoundingClientRect();
  return JSON.stringify({ found: true, cx: rect.left + rect.width / 2, cy: rect.top + rect.height / 2 });
})()
`, strconv.Quote(target))
		v, err := s.evaluateJS(prep, 5*time.Second)
		if err != nil {
			return nil, err
		}
		str, _ := v.(string)
		var p struct {
			Found bool    `json:"found"`
			CX    float64 `json:"cx"`
			CY    float64 `json:"cy"`
		}
		if err := json.Unmarshal([]byte(str), &p); err != nil {
			return nil, &toolError{Code: -32000, Message: "hover: bad prep result: " + str}
		}
		if !p.Found {
			return map[string]interface{}{"success": false, "error": "Element not found: " + target}, nil
		}
		if err := s.dispatchHover(p.CX, p.CY); err != nil {
			return nil, err
		}
		return map[string]interface{}{"success": true, "cx": p.CX, "cy": p.CY}, nil
	}

	// coordinate mode
	xv, xok := getNum(args, "x")
	yv, yok := getNum(args, "y")
	if !xok || !yok {
		return nil, &toolError{Code: -32602, Message: "hover: requires either target or x/y"}
	}
	if err := s.dispatchHover(xv, yv); err != nil {
		return nil, err
	}
	return map[string]interface{}{"success": true, "x": xv, "y": yv}, nil
}

func toolDrag(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	fromX, ok := getNum(args, "fromX")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing fromX"}
	}
	fromY, ok := getNum(args, "fromY")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing fromY"}
	}
	toX, ok := getNum(args, "toX")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing toX"}
	}
	toY, ok := getNum(args, "toY")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing toY"}
	}
	steps := 10
	if v, ok := getNum(args, "steps"); ok && v > 0 {
		steps = int(v)
	}
	if err := s.dispatchSwipe(fromX, fromY, toX, toY, steps); err != nil {
		return nil, err
	}
	return map[string]interface{}{"success": true, "fromX": fromX, "fromY": fromY, "toX": toX, "toY": toY}, nil
}

// ── HTTP server ──────────────────────────────────────────────────────

func handleMCP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	if r.Method == http.MethodDelete {
		w.Header().Set("content-type", "application/json")
		io.WriteString(w, `{"ok":true}`)
		return
	}

	if mcpAuthToken != "" {
		auth := r.Header.Get("Authorization")
		if auth != authHeaderValue {
			http.Error(w, `{"error":"Unauthorized"}`, http.StatusUnauthorized)
			return
		}
	}

	body, err := io.ReadAll(r.Body)
	if err != nil || len(body) == 0 {
		writeJSON(w, errResp(nil, -32700, "parse error: empty body"))
		return
	}

	var req jsonrpcReq
	if err := json.Unmarshal(body, &req); err != nil {
		writeJSON(w, errResp(nil, -32700, "parse error: "+err.Error()))
		return
	}

	result := processRequest(req)
	writeJSON(w, result)
}

func writeJSON(w http.ResponseWriter, v interface{}) {
	w.Header().Set("content-type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func handleRoot(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("content-type", "application/json")
	fmt.Fprintf(w, `{"name":"browser-mcp","version":"0.1.0","protocol":"streamable-http","cdpEndpoint":"http://%s:%d"}`, cdpHost, cdpPort)
}

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/mcp", handleMCP)
	mux.HandleFunc("/", handleRoot)

	addr := ":" + strconv.Itoa(mcpPort)
	log.Printf("[browser-mcp] listening on http://0.0.0.0%s/mcp", addr)
	log.Printf("[browser-mcp] CDP target: http://%s:%d", cdpHost, cdpPort)
	log.Printf("[browser-mcp] auth: %s", strings.ToLower(func() string {
		if mcpAuthToken != "" {
			return "enabled"
		}
		return "disabled"
	}()))

	srv := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("listen failed: %v", err)
	}
}
