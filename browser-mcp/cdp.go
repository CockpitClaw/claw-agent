package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

const (
	cdpHost = "127.0.0.1"
	cdpPort = 9222
)

type pageInfo struct {
	ID                   string `json:"id"`
	URL                  string `json:"url"`
	Title                string `json:"title"`
	Type                 string `json:"type"`
	WebSocketDebuggerURL string `json:"webSocketDebuggerUrl"`
}

func listPages() ([]pageInfo, error) {
	resp, err := http.Get(fmt.Sprintf("http://%s:%d/json", cdpHost, cdpPort))
	if err != nil {
		return nil, fmt.Errorf("cdp http: %w", err)
	}
	defer resp.Body.Close()
	var all []pageInfo
	if err := json.NewDecoder(resp.Body).Decode(&all); err != nil {
		return nil, fmt.Errorf("decode /json: %w", err)
	}
	var pages []pageInfo
	for _, p := range all {
		if p.Type == "page" {
			pages = append(pages, p)
		}
	}
	return pages, nil
}

// createPage opens a new browser tab via the CDP HTTP endpoint and returns it.
// Chrome's /json/new?<url> creates a new tab, navigates it to <url>, and returns
// the page descriptor (same shape as /json entries).
func createPage(url string) (pageInfo, error) {
	req, err := http.NewRequest(http.MethodPut,
		fmt.Sprintf("http://%s:%d/json/new?%s", cdpHost, cdpPort, url), nil)
	if err != nil {
		return pageInfo{}, fmt.Errorf("build create request: %w", err)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return pageInfo{}, fmt.Errorf("cdp http create: %w", err)
	}
	defer resp.Body.Close()
	var p pageInfo
	if err := json.NewDecoder(resp.Body).Decode(&p); err != nil {
		return pageInfo{}, fmt.Errorf("decode /json/new: %w", err)
	}
	if p.Type != "page" || p.ID == "" {
		return pageInfo{}, fmt.Errorf("create: unexpected response: type=%q id=%q", p.Type, p.ID)
	}
	return p, nil
}

func findPageByID(id string) (pageInfo, error) {
	pages, err := listPages()
	if err != nil {
		return pageInfo{}, err
	}
	for _, p := range pages {
		if p.ID == id {
			return p, nil
		}
	}
	return pageInfo{}, fmt.Errorf("page not found: %s", id)
}

type cdpResponse struct {
	ID     int             `json:"id"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *cdpError       `json:"error,omitempty"`
}

type cdpError struct {
	Message string `json:"message"`
}

type cdpSession struct {
	ws       *websocket.Conn
	nextID   int32
	mu       sync.Mutex
	pending  map[int]chan cdpResponse
	url      string
	title    string
	vpWidth  int
	vpHeight int

	// frame context tracking: origin → executionContextId (for cross-origin iframe JS eval)
	frameMu       sync.RWMutex
	frameContexts map[string]int

	// devicePixelRatio: Mac Retina = 2.0, standard = 1.0. Used to convert
	// screenshot pixel coords to CSS px coords (CSS px = screenshot px / dpr).
	dpr float64

	// nav epoch: bumped on every Page.frameNavigated (top-level) and
	// Page.loadEventFired. evaluateJS waits for a fresh epoch after a
	// navigation so the user script runs against a settled document and
	// doesn't hit the 10s send timeout mid-SPA-transition.
	navMu      sync.Mutex
	navEpoch   uint64
	loadCh     chan uint64 // signals each loadEventFired / frameNavigated

	// anti-poll: last time media_status was called on this page.
	// Repeated calls within 5s return a "stop polling" hint instead of
	// re-running the JS, preventing agent ReAct loops that query status
	// dozens of times waiting for "better" playback state.
	mediaMu    sync.Mutex
	mediaLast  time.Time
}

func newCDPSession(wsURL string) *cdpSession {
	return &cdpSession{
		ws:            nil,
		pending:       make(map[int]chan cdpResponse),
		vpWidth:       1280,
		vpHeight:      800,
		dpr:           1.0,
		frameContexts: make(map[string]int),
		loadCh:        make(chan uint64, 16),
	}
}

func (s *cdpSession) connect(wsURL string) error {
	dialer := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	ws, _, err := dialer.Dial(wsURL, nil)
	if err != nil {
		return fmt.Errorf("cdp dial: %w", err)
	}
	s.ws = ws
	go s.readLoop()

	if _, err := s.send("Page.enable", nil); err != nil {
		return err
	}
	if _, err := s.send("Runtime.enable", nil); err != nil {
		return err
	}
	if _, err := s.send("Network.enable", nil); err != nil {
		return err
	}

	type layoutMetrics struct {
		VisualViewport struct {
			ClientWidth  int `json:"clientWidth"`
			ClientHeight int `json:"clientHeight"`
		} `json:"visualViewport"`
	}
	if raw, err := s.send("Page.getLayoutMetrics", nil); err == nil {
		var m layoutMetrics
		if json.Unmarshal(raw, &m) == nil {
			if m.VisualViewport.ClientWidth > 0 {
				s.vpWidth = m.VisualViewport.ClientWidth
			}
			if m.VisualViewport.ClientHeight > 0 {
				s.vpHeight = m.VisualViewport.ClientHeight
			}
		}
	}

	if v, err := s.evaluateJS("location.href", 5*time.Second); err == nil {
		if str, ok := v.(string); ok {
			s.url = str
		}
	}
	// Read devicePixelRatio for screenshot→CSS coordinate conversion.
	if v, err := s.evaluateJS("window.devicePixelRatio", 3*time.Second); err == nil {
		if dpr, ok := v.(float64); ok && dpr > 0 {
			s.dpr = dpr
		}
	}
	return nil
}

func (s *cdpSession) readLoop() {
	for {
		_, data, err := s.ws.ReadMessage()
		if err != nil {
			s.failAll(fmt.Sprintf("connection closed: %v", err))
			return
		}
		var msg struct {
			ID     int             `json:"id"`
			Method string          `json:"method"`
			Params json.RawMessage `json:"params"`
			Result json.RawMessage `json:"result"`
			Error  *cdpError       `json:"error"`
		}
		if err := json.Unmarshal(data, &msg); err != nil {
			continue
		}
		if msg.ID != 0 {
			s.mu.Lock()
			ch, ok := s.pending[msg.ID]
			if ok {
				delete(s.pending, msg.ID)
			}
			s.mu.Unlock()
			if ok {
				ch <- cdpResponse{ID: msg.ID, Result: msg.Result, Error: msg.Error}
			}
			continue
		}
		// event
		switch msg.Method {
		case "Runtime.executionContextCreated":
			var p struct {
				Context struct {
					ID     int    `json:"id"`
					Origin string `json:"origin"`
				} `json:"context"`
			}
			if json.Unmarshal(msg.Params, &p) == nil && p.Context.Origin != "" && p.Context.Origin != "://" {
				key := strings.TrimRight(p.Context.Origin, "/")
				s.frameMu.Lock()
				s.frameContexts[key] = p.Context.ID
				s.frameMu.Unlock()
			}
		case "Runtime.executionContextDestroyed":
			var p struct {
				ExecutionContextID int `json:"executionContextId"`
			}
			if json.Unmarshal(msg.Params, &p) == nil && p.ExecutionContextID != 0 {
				s.frameMu.Lock()
				for k, v := range s.frameContexts {
					if v == p.ExecutionContextID {
						delete(s.frameContexts, k)
					}
				}
				s.frameMu.Unlock()
			}
		case "Runtime.executionContextsCleared":
			s.frameMu.Lock()
			s.frameContexts = make(map[string]int)
			s.frameMu.Unlock()
		case "Page.frameNavigated":
			var p struct {
				Frame struct {
					URL      string `json:"url"`
					ParentID string `json:"parentId"`
				} `json:"frame"`
			}
			if json.Unmarshal(msg.Params, &p) == nil && p.Frame.ParentID == "" {
				s.url = p.Frame.URL
				s.bumpNavEpoch()
			}
		case "Page.loadEventFired":
			s.bumpNavEpoch()
			if v, err := s.evaluateJS("document.title", 2*time.Second); err == nil {
				if t, ok := v.(string); ok && t != "" && t != "null" {
					s.title = t
				}
			}
		}
	}
}

func (s *cdpSession) failAll(reason string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, ch := range s.pending {
		ch <- cdpResponse{ID: id, Error: &cdpError{Message: reason}}
		delete(s.pending, id)
	}
}

func (s *cdpSession) send(method string, params map[string]interface{}) (json.RawMessage, error) {
	if s.ws == nil {
		return nil, fmt.Errorf("cdp: not connected")
	}
	// SPA route changes (history.pushState) tear down the current execution
	// context without firing Page.frameNavigated, so a Runtime.evaluate issued
	// mid-transition gets its response dropped — the call hangs for the full
	// 10s timeout. Retry up to 3 times with a shorter per-attempt timeout;
	// the first retry usually lands on a settled context and returns in <1s.
	const maxRetries = 3
	const perAttempt = 3 * time.Second
	var lastErr error
	for attempt := 0; attempt < maxRetries; attempt++ {
		raw, err := s.sendOnce(method, params, perAttempt)
		if err == nil {
			return raw, nil
		}
		lastErr = err
		// Only retry on timeout (response dropped mid-SPA-transition).
		// Real errors (cdp method error, write fail) should surface immediately.
		if !strings.Contains(err.Error(), "timeout") {
			return nil, err
		}
		// Backoff before retry: the SPA is still rendering. A short sleep lets
		// the new execution context settle so the next attempt's response
		// isn't dropped too.
		if attempt < maxRetries-1 {
			time.Sleep(time.Duration(500*(attempt+1)) * time.Millisecond)
		}
	}
	return nil, lastErr
}

func (s *cdpSession) sendOnce(method string, params map[string]interface{}, timeout time.Duration) (json.RawMessage, error) {
	id := int(atomic.AddInt32(&s.nextID, 1))
	ch := make(chan cdpResponse, 1)
	s.mu.Lock()
	s.pending[id] = ch
	s.mu.Unlock()

	payload := map[string]interface{}{"id": id, "method": method}
	if params != nil {
		payload["params"] = params
	}
	data, _ := json.Marshal(payload)
	if err := s.ws.WriteMessage(websocket.TextMessage, data); err != nil {
		s.mu.Lock()
		delete(s.pending, id)
		s.mu.Unlock()
		return nil, fmt.Errorf("cdp write: %w", err)
	}
	select {
	case resp := <-ch:
		if resp.Error != nil {
			return nil, fmt.Errorf("cdp %s: %s", method, resp.Error.Message)
		}
		return resp.Result, nil
	case <-time.After(timeout):
		s.mu.Lock()
		delete(s.pending, id)
		s.mu.Unlock()
		return nil, fmt.Errorf("cdp: timeout (%s) after %s", method, timeout)
	}
}

type evalResult struct {
	Result struct {
		Type  string          `json:"type"`
		Value json.RawMessage `json:"value"`
	} `json:"result"`
	ExceptionDetails struct {
		Text      string `json:"text"`
		Exception *struct {
			Type        string `json:"type"`
			Subtype     string `json:"subtype"`
			Description string `json:"description"`
		} `json:"exception"`
		StackTrace *struct {
			CallFrames []struct {
				FunctionName string `json:"functionName"`
				LineNumber   int    `json:"lineNumber"`
				ColumnNumber int    `json:"columnNumber"`
			} `json:"callFrames"`
		} `json:"stackTrace"`
	} `json:"exceptionDetails"`
}

func (s *cdpSession) bumpNavEpoch() {
	s.navMu.Lock()
	s.navEpoch++
	// Non-blocking push: loadCh has buffer 16; drop if full (reader just
	// needs the latest epoch, which it re-reads under lock anyway).
	select {
	case s.loadCh <- s.navEpoch:
	default:
	}
	s.navMu.Unlock()
}

// currentNavEpoch returns the latest navigation epoch counter.
func (s *cdpSession) currentNavEpoch() uint64 {
	s.navMu.Lock()
	defer s.navMu.Unlock()
	return s.navEpoch
}

// currentURL returns the page's current URL, refreshing from location.href if stale.
func (s *cdpSession) currentURL() string {
	s.mu.Lock()
	url := s.url
	s.mu.Unlock()
	if url != "" {
		return url
	}
	if v, err := s.evaluateJS("location.href", 3*time.Second); err == nil {
		if str, ok := v.(string); ok {
			return str
		}
	}
	return ""
}

func (s *cdpSession) evaluateJS(expression string, timeout time.Duration) (interface{}, error) {
	// send() already retries on timeout with backoff, which handles the SPA
	// context-switch case (Runtime.evaluate issued mid-transition gets its
	// response dropped). No separate readyState/navEpoch wait here — it added
	// latency on settled pages and didn't reliably predict SPA settle anyway.
	raw, err := s.send("Runtime.evaluate", map[string]interface{}{
		"expression":    expression,
		"returnByValue": true,
		"awaitPromise":  false,
	})
	if err != nil {
		return nil, err
	}
	var r evalResult
	_ = json.Unmarshal(raw, &r)
	if r.ExceptionDetails.Text != "" {
		// Surface the full exception description (contains the actual error
		// message like "TypeError: Cannot read property 'x' of undefined")
		// — the `text` field just says "Uncaught" which is useless for debugging.
		detail := r.ExceptionDetails.Text
		if r.ExceptionDetails.Exception != nil && r.ExceptionDetails.Exception.Description != "" {
			detail = r.ExceptionDetails.Exception.Description
		}
		if r.ExceptionDetails.StackTrace != nil && len(r.ExceptionDetails.StackTrace.CallFrames) > 0 {
			f := r.ExceptionDetails.StackTrace.CallFrames[0]
			if f.FunctionName != "" || f.LineNumber > 0 {
				detail += fmt.Sprintf(" (at %s line %d)", f.FunctionName, f.LineNumber)
			}
		}
		return nil, fmt.Errorf("js error: %s", detail)
	}
	if len(r.Result.Value) == 0 || string(r.Result.Value) == "null" {
		return nil, nil
	}
	var v interface{}
	if err := json.Unmarshal(r.Result.Value, &v); err != nil {
		return nil, nil
	}
	return v, nil
}

func (s *cdpSession) evaluateInFrame(frameURL string, expression string, timeout time.Duration) (interface{}, error) {
	origin := strings.TrimRight(frameURL, "/")
	deadline := time.Now().Add(timeout)
	var ctxID int
	for {
		s.frameMu.RLock()
		id, ok := s.frameContexts[origin]
		s.frameMu.RUnlock()
		if ok {
			ctxID = id
			break
		}
		// fallback: prefix match
		s.frameMu.RLock()
		for k, v := range s.frameContexts {
			if strings.HasPrefix(origin, k) || strings.HasPrefix(k, origin) {
				ctxID = v
				break
			}
		}
		s.frameMu.RUnlock()
		if ctxID != 0 {
			break
		}
		if time.Now().After(deadline) {
			s.frameMu.RLock()
			keys := make([]string, 0, len(s.frameContexts))
			for k := range s.frameContexts {
				keys = append(keys, k)
			}
			s.frameMu.RUnlock()
			return nil, fmt.Errorf("frame context not found for %q (available: %s)", frameURL, strings.Join(keys, ", "))
		}
		time.Sleep(150 * time.Millisecond)
	}

	raw, err := s.send("Runtime.evaluate", map[string]interface{}{
		"expression":    expression,
		"contextId":    ctxID,
		"returnByValue": true,
		"awaitPromise": false,
	})
	if err != nil {
		return nil, err
	}
	var r evalResult
	_ = json.Unmarshal(raw, &r)
	if r.ExceptionDetails.Text != "" {
		return nil, fmt.Errorf("js error: %s", r.ExceptionDetails.Text)
	}
	if len(r.Result.Value) == 0 || string(r.Result.Value) == "null" {
		return nil, nil
	}
	var v interface{}
	if err := json.Unmarshal(r.Result.Value, &v); err != nil {
		return nil, nil
	}
	return v, nil
}

func (s *cdpSession) navigate(url string) (bool, string, error) {
	raw, err := s.send("Page.navigate", map[string]interface{}{"url": url})
	if err != nil {
		return false, "", err
	}
	var r struct {
		ErrorText string `json:"errorText"`
	}
	_ = json.Unmarshal(raw, &r)
	time.Sleep(800 * time.Millisecond)
	return r.ErrorText == "", r.ErrorText, nil
}

func (s *cdpSession) screenshot() ([]byte, error) {
	raw, err := s.send("Page.captureScreenshot", map[string]interface{}{"format": "png"})
	if err != nil {
		return nil, err
	}
	var r struct {
		Data string `json:"data"`
	}
	if err := json.Unmarshal(raw, &r); err != nil {
		return nil, err
	}
	if r.Data == "" {
		return nil, fmt.Errorf("screenshot: no data")
	}
	return base64Decode(r.Data)
}

func (s *cdpSession) dispatchClick(x, y float64) error {
	// mouseMoved first — triggers hover state needed for Apple Music/Spotify
	// song-row play buttons that only appear on hover.
	if _, err := s.send("Input.dispatchMouseEvent", map[string]interface{}{
		"type": "mouseMoved", "x": x, "y": y, "button": "none",
	}); err != nil {
		return err
	}
	for _, t := range []string{"mousePressed", "mouseReleased"} {
		if _, err := s.send("Input.dispatchMouseEvent", map[string]interface{}{
			"type":       t,
			"x":          x,
			"y":          y,
			"button":     "left",
			"clickCount": 1,
		}); err != nil {
			return err
		}
	}
	return nil
}

func (s *cdpSession) dispatchSwipe(fromX, fromY, toX, toY float64, steps int) error {
	if _, err := s.send("Input.dispatchMouseEvent", map[string]interface{}{
		"type": "mousePressed", "x": fromX, "y": fromY, "button": "left", "clickCount": 1,
	}); err != nil {
		return err
	}
	for i := 1; i <= steps; i++ {
		t := float64(i) / float64(steps)
		if _, err := s.send("Input.dispatchMouseEvent", map[string]interface{}{
			"type":   "mouseMoved",
			"x":      fromX + (toX-fromX)*t,
			"y":      fromY + (toY-fromY)*t,
			"button": "left",
		}); err != nil {
			return err
		}
		time.Sleep(20 * time.Millisecond)
	}
	if _, err := s.send("Input.dispatchMouseEvent", map[string]interface{}{
		"type": "mouseReleased", "x": toX, "y": toY, "button": "left", "clickCount": 1,
	}); err != nil {
		return err
	}
	return nil
}

// dispatchHover moves the mouse to (x, y) and dispatches mouseMoved events.
// This triggers hover-reveal menus, tooltips, and CSS :hover states.
func (s *cdpSession) dispatchHover(x, y float64) error {
	if _, err := s.send("Input.dispatchMouseEvent", map[string]interface{}{
		"type":   "mouseMoved",
		"x":      x,
		"y":      y,
		"button": "none",
	}); err != nil {
		return err
	}
	return nil
}

// dispatchKeyPress sends a keydown+keyup for a named key (e.g. "PageDown",
// "ArrowDown", "End"). Used as a scroll fallback for pages where window.scrollBy
// has no effect (virtual-scroll lists that only respond to wheel/key events).
func (s *cdpSession) dispatchKeyPress(key string) error {
	// keyDown: type=rawKeyDown so we don't have to synthesize text
	if _, err := s.send("Input.dispatchKeyEvent", map[string]interface{}{
		"type": "keyDown",
		"key":  key,
		// code is the physical key code; both are usually accepted.
		"code": key,
	}); err != nil {
		return err
	}
	if _, err := s.send("Input.dispatchKeyEvent", map[string]interface{}{
		"type": "keyUp",
		"key":  key,
		"code": key,
	}); err != nil {
		return err
	}
	return nil
}

func (s *cdpSession) close() {
	if s.ws != nil {
		s.ws.Close()
		s.ws = nil
	}
}
