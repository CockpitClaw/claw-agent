package main

import (
	"encoding/json"
	"time"
)

// jdGetSizes polls the detail-page DOM for the selectable size list. Sizes
// on JD detail pages (item.jd.com / npcitem.jd.hk) render asynchronously,
// so we poll up to 3s. Runs on the main frame — JD sizes live in the main
// page DOM, not an iframe (trade.jd.hk order page has NO size elements).
func toolJDGetSizes(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	var raw interface{}
	deadline := time.Now().Add(3 * time.Second)
	for {
		raw, err = evalJSON(s, jdGetSizesScript)
		if err == nil {
			if m, ok := raw.(map[string]interface{}); ok {
				if sizes, _ := m["sizes"].([]interface{}); len(sizes) > 0 {
					return m, nil
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
	if raw == nil {
		return map[string]interface{}{"found": false, "sizes": []interface{}{}}, nil
	}
	return raw, nil
}

// toolJDSelectSize clicks the size element matching the target text, waits
// for render, then re-queries to confirm selection (checks --sel / active).
func toolJDSelectSize(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	target, ok := getStr(args, "size")
	if !ok {
		return nil, &toolError{Code: -32602, Message: "Missing size"}
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}
	script := substitute(jdSelectSizeScriptTemplate, map[string]string{
		"SELECTED_SIZE": jsonQuote(target),
	})
	raw, err := evalJSON(s, script)
	if err != nil {
		return nil, err
	}
	clicked, _ := raw.(map[string]interface{})
	if c, _ := clicked["clicked"].(bool); !c {
		return map[string]interface{}{"ok": false, "error": "size_not_in_list"}, nil
	}
	// Wait for selection to register, then verify
	time.Sleep(800 * time.Millisecond)
	verifyRaw, _ := evalJSON(s, jdGetSizesScript)
	if m, ok := verifyRaw.(map[string]interface{}); ok {
		if sizes, _ := m["sizes"].([]interface{}); ok {
			for _, sz := range sizes {
				if sm, ok := sz.(map[string]interface{}); ok {
					if t, _ := sm["text"].(string); t == target {
						if sel, _ := sm["selected"].(bool); sel {
							return map[string]interface{}{"ok": true, "selected": target}, nil
						}
					}
				}
			}
		}
	}
	// Selection didn't register via DOM click — fall back to coordinate click
	// using the coords returned by the select script.
	if xVal, ok := clicked["x"].(float64); ok {
		if yVal, ok := clicked["y"].(float64); ok {
			if err := s.dispatchClick(xVal, yVal); err != nil {
				return nil, err
			}
			time.Sleep(800 * time.Millisecond)
			// verify again
			verifyRaw2, _ := evalJSON(s, jdGetSizesScript)
			if m, ok := verifyRaw2.(map[string]interface{}); ok {
				if sizes, _ := m["sizes"].([]interface{}); ok {
					for _, sz := range sizes {
						if sm, ok := sz.(map[string]interface{}); ok {
							if t, _ := sm["text"].(string); t == target {
								if sel, _ := sm["selected"].(bool); sel {
									return map[string]interface{}{"ok": true, "selected": target, "fallback": "coord"}, nil
								}
							}
						}
					}
				}
			}
		}
	}
	return map[string]interface{}{"ok": false, "error": "click_no_effect"}, nil
}

// toolJDFindPayButton finds the pay/submit button on a JD order-confirmation
// page (URL contains trade.jd.*). It handles three forms the order page can
// take, auto-detecting which one by probing the DOM with render-wait retries:
//
//  1. single_page: 立即支付 button directly on trade.jd.hk (JD international)
//  2. two_phase_submit: 提交订单 button → click → jumps to payment page → 立即支付
//  3. iframe: pc-settlement-lite-pro.pf.jd.com iframe popup on item.jd.com
//
// Why this exists: agents kept misjudging the form on first probe (SPA not
// rendered yet) and wandering into scroll_down / snapshot / login-wall
// detours. Polling + form-detection belongs in Go, not in the LLM's head.
func toolJDFindPayButton(args map[string]interface{}) (interface{}, error) {
	pageID, err := requirePageID(args)
	if err != nil {
		return nil, err
	}
	s, err := sessions.get(pageID)
	if err != nil {
		return nil, err
	}

	// findButtonScript probes for a button matching btnTexts in the given
	// JS execution context (main frame by default). Returns {txt,x,y} or {error:"not_found"}.
	findButtonScript := func(btnTexts []string) string {
		// Build JS array literal of button texts.
		arr := "["
		for i, t := range btnTexts {
			if i > 0 {
				arr += ","
			}
			arr += "'" + t + "'"
		}
		arr += "]"
		return `(function(){var btnTexts=` + arr + `;var all=document.querySelectorAll("a,button,div,span");for(var i=0;i<all.length;i++){var el=all[i];var t=(el.innerText||"").trim();if(btnTexts.indexOf(t)===-1)continue;var r=el.getBoundingClientRect();if(r.width>60&&r.width<400&&r.height>20){return JSON.stringify({txt:t,x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)})}}return JSON.stringify({error:"not_found"})})()`
	}

	payTexts := []string{"立即支付", "去付款", "确认支付", "立即付款", "去支付"}
	submitTexts := []string{"提交订单", "确认订单", "提交", "确认提交", "去结算", "结算"}
	const iframeURL = "https://pc-settlement-lite-pro.pf.jd.com"

	deadline := time.Now().Add(8 * time.Second)
	for {
		// 1. Try main frame: 立即支付 (single-page form)
		raw, err := evalJSON(s, findButtonScript(payTexts))
		if err == nil {
			if m, ok := raw.(map[string]interface{}); ok {
				if txt, _ := m["txt"].(string); txt != "" {
					return map[string]interface{}{
						"form": "single_page",
						"btn":  m,
					}, nil
				}
			}
		}
		// 2. Try main frame: 提交订单 (two-phase form, first step)
		raw, err = evalJSON(s, findButtonScript(submitTexts))
		if err == nil {
			if m, ok := raw.(map[string]interface{}); ok {
				if txt, _ := m["txt"].(string); txt != "" {
					return map[string]interface{}{
						"form": "two_phase_submit",
						"btn":  m,
					}, nil
				}
			}
		}
		// 3. Try iframe (popup form on item.jd.com)
		iframeRaw, iframeErr := s.evaluateInFrame(iframeURL, findButtonScript(payTexts), 2*time.Second)
		if iframeErr == nil {
			if str, ok := iframeRaw.(string); ok {
				var m map[string]interface{}
				if json.Unmarshal([]byte(str), &m) == nil {
					if txt, _ := m["txt"].(string); txt != "" {
						return map[string]interface{}{
							"form":   "iframe",
							"iframe": iframeURL,
							"btn":    m,
						}, nil
					}
				}
			}
		}
		if !time.Now().Before(deadline) {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	return map[string]interface{}{"error": "not_found", "reason": "8s 内未找到支付/提交按钮，页面可能未加载或需要登录"}, nil
}
