package com.clawagent.cdp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * CDP 纯工具函数集合：
 *   - JS 代码片段生成（DOM 操作、节点查询等）
 *   - JS 执行结果 → CDP RemoteObject 解析
 *   - JSON 字符串清理
 *   - CallArgument 列表 → JS 数组
 *
 * 无状态、无 Android 依赖，便于单元测试。
 */
object CdpJsHelpers {

    private val gson = Gson()
    private val jsonParser = JsonParser()

    // ── JS 结果解析 ────────────────────────────────────────────────

    /**
     * 将 Android `evaluateJavascript` 返回的 JSON-encoded 字符串
     * 转换为 CDP RemoteObject（`{type, value, ...}`）。
     */
    fun parseJsResult(jsResult: String): JsonObject {
        if (jsResult == "null" || jsResult.isBlank()) {
            return JsonObject().apply { addProperty("type", "undefined") }
        }
        return try {
            val parsed = jsonParser.parse(jsResult)
            when {
                parsed.isJsonNull -> JsonObject().apply { addProperty("type", "null") }
                parsed.isJsonPrimitive -> {
                    val prim = parsed.asJsonPrimitive
                    when {
                        prim.isBoolean -> JsonObject().apply {
                            addProperty("type", "boolean")
                            addProperty("value", prim.asBoolean)
                        }
                        prim.isNumber -> JsonObject().apply {
                            addProperty("type", "number")
                            addProperty("value", prim.asNumber)
                        }
                        else -> JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("value", prim.asString)
                        }
                    }
                }
                parsed.isJsonObject -> JsonObject().apply {
                    addProperty("type", "object")
                    add("value", parsed.asJsonObject)
                }
                parsed.isJsonArray -> JsonObject().apply {
                    addProperty("type", "object")
                    addProperty("subtype", "array")
                    add("value", parsed.asJsonArray)
                }
                else -> JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("value", jsResult)
                }
            }
        } catch (_: Exception) {
            JsonObject().apply {
                addProperty("type", "string")
                addProperty("value", jsResult)
            }
        }
    }

    /**
     * 清理 `evaluateJavascript` 返回的带外层双引号的 JSON 字符串转义，
     * 用于 DOM.getDocument / DOM.getOuterHTML 等返回 `JSON.stringify(...)` 的场景。
     */
    fun cleanJsonString(jsResult: String): String =
        if (jsResult.startsWith("\"") && jsResult.endsWith("\"")) {
            jsResult.substring(1, jsResult.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else jsResult

    /**
     * 将 CDP `CallArgument[]` 转为可直接注入 JS 的数组字符串。
     * 例：`[{"value":1},{"objectId":"obj-2"}]` → `[1,(window.__cdpObjectRegistry&&...)||null]`
     */
    fun buildCallArgumentsJs(arguments: JsonArray): String {
        if (arguments.size() == 0) return "[]"
        val parts = mutableListOf<String>()
        for (arg in arguments) {
            val obj = arg.asJsonObject
            when {
                obj.has("value") -> parts.add(gson.toJson(obj["value"]))
                obj.has("unserializableValue") -> parts.add(obj["unserializableValue"].asString)
                obj.has("objectId") -> {
                    val oid = obj["objectId"].asString
                    parts.add("(window.__cdpObjectRegistry&&window.__cdpObjectRegistry['$oid'])||null")
                }
                else -> parts.add("undefined")
            }
        }
        return "[${parts.joinToString(",")}]"
    }

    // ── DOM JS 片段生成 ────────────────────────────────────────────

    fun domGetDocumentJs(depth: Int, nextNodeId: Int): String = """
        (function() {
            var nextId = $nextNodeId;
            window.__cdpNodeMap = window.__cdpNodeMap || {};
            function walk(el, currentDepth) {
                if (currentDepth < 0) return null;
                var nodeId = nextId++;
                var backendNodeId = nodeId;
                var tag = el.tagName ? el.tagName.toLowerCase() : '';
                var children = [];
                var attrs = [];
                if (el.attributes) {
                    for (var i = 0; i < Math.min(el.attributes.length, 100); i++) {
                        var a = el.attributes[i]; attrs.push(a.name, a.value);
                    }
                }
                if (currentDepth > 0 && el.children) {
                    for (var i = 0; i < Math.min(el.children.length, 200); i++) {
                        var child = walk(el.children[i], currentDepth - 1);
                        if (child) children.push(child);
                    }
                }
                var node = {
                    nodeId: nodeId, backendNodeId: backendNodeId,
                    nodeType: el.nodeType || 1,
                    nodeName: tag || el.nodeName || '',
                    localName: tag,
                    nodeValue: el.nodeType === 3 ? (el.nodeValue || '') : '',
                    childNodeCount: children.length,
                    attributes: attrs,
                    children: children.length > 0 ? children : undefined,
                    baseURL: document.baseURI || '',
                    documentURL: document.URL || ''
                };
                if (el.nodeType === 1) node.frameId = window.__cdpFrameId || '';
                window.__cdpNodeMap[nodeId] = node;
                return node;
            }
            var root = walk(document.documentElement || document.body, $depth);
            window.__cdpNextNodeId = nextId;
            return JSON.stringify(root);
        })();
    """.trimIndent()

    fun domDescribeNodeJs(nodeId: Int, backendNodeId: Int): String = """
        (function() {
            var map = window.__cdpNodeMap || {};
            var n = ($nodeId > 0 && map[$nodeId]) ? map[$nodeId]
                  : ($backendNodeId > 0 && map[$backendNodeId]) ? map[$backendNodeId]
                  : null;
            if (!n) return JSON.stringify({nodeId:$nodeId,backendNodeId:$backendNodeId,nodeType:1,nodeName:'BODY',localName:'body',nodeValue:''});
            return JSON.stringify({
                nodeId:n.nodeId, backendNodeId:n.backendNodeId,
                nodeType:n.nodeType, nodeName:n.nodeName, localName:n.localName,
                nodeValue:n.nodeValue, attributes:(n.attributes||[]), frameId:n.frameId
            });
        })();
    """.trimIndent()

    fun domQuerySelectorAllJs(nodeId: Int, selector: String, nextNodeId: Int): String = """
        (function() {
            var nextId = window.__cdpNextNodeId || $nextNodeId;
            window.__cdpNodeMap = window.__cdpNodeMap || {};
            var safeSelector = ${gson.toJson(selector)};
            var results = [];
            try {
                var root = ($nodeId > 0 && window.__cdpNodeMap[$nodeId])
                    ? (document.querySelector('*[__cdpNid="'+window.__cdpNodeMap[$nodeId].nodeId+'"]') || document)
                    : document;
                var els = root.querySelectorAll(safeSelector);
                for (var i = 0; i < Math.min(els.length, 500); i++) {
                    var id = nextId++; var el = els[i];
                    var tag = el.tagName ? el.tagName.toLowerCase() : '';
                    var attrs = [];
                    if (el.attributes) {
                        for (var j = 0; j < Math.min(el.attributes.length, 50); j++) {
                            var a = el.attributes[j]; attrs.push(a.name, a.value);
                        }
                    }
                    window.__cdpNodeMap[id] = {
                        nodeId:id, backendNodeId:id, nodeType:1,
                        nodeName:tag, localName:tag, nodeValue:'',
                        attributes:attrs, childNodeCount: el.children ? el.children.length : 0,
                        frameId: window.__cdpFrameId || ''
                    };
                    results.push(id);
                }
            } catch(e) {}
            window.__cdpNextNodeId = nextId;
            return JSON.stringify(results);
        })();
    """.trimIndent()

    fun domPushBackendIdsJs(backendNodeIds: List<Int>, nextNodeId: Int): String {
        val idsStr = backendNodeIds.joinToString(",")
        return """
            (function() {
                var map = window.__cdpNodeMap || {};
                var ids = [$idsStr];
                var results = [];
                var nextId = window.__cdpNextNodeId || $nextNodeId;
                for (var i = 0; i < ids.length; i++) {
                    var n = map[ids[i]];
                    if (!n) {
                        try {
                            var el = document.querySelector('*[__cdpBeId="'+ids[i]+'"]');
                            if (el && el.parentNode) {
                                var id = nextId++;
                                var tag = el.tagName ? el.tagName.toLowerCase() : '';
                                window.__cdpNodeMap[id] = {
                                    nodeId:id, backendNodeId:ids[i],
                                    nodeType:1, nodeName:tag, localName:tag,
                                    nodeValue:'', attributes:[],
                                    childNodeCount: el.children ? el.children.length : 0
                                };
                                results.push(id); continue;
                            }
                        } catch(e) {}
                        results.push(0);
                    } else {
                        results.push(n.nodeId);
                    }
                }
                window.__cdpNextNodeId = nextId;
                return JSON.stringify(results);
            })();
        """.trimIndent()
    }

    fun domResolveNodeJs(nodeId: Int, backendNodeId: Int): String = """
        (function() {
            var map = window.__cdpNodeMap || {};
            var n = ($nodeId > 0 && map[$nodeId]) ? map[$nodeId]
                  : ($backendNodeId > 0 && map[$backendNodeId]) ? map[$backendNodeId]
                  : null;
            if (!n) return JSON.stringify(null);
            window.__cdpObjectRegistry = window.__cdpObjectRegistry || {};
            var objId = 'obj-' + n.nodeId;
            try {
                var el = document.querySelector('*[__cdpBeId="'+n.backendNodeId+'"]');
                window.__cdpObjectRegistry[objId] = el || window;
            } catch(e) { window.__cdpObjectRegistry[objId] = window; }
            return JSON.stringify(objId);
        })();
    """.trimIndent()

    fun domSetAttributeJs(nodeId: Int, attrName: String, attrValue: String): String = """
        (function() {
            var map = window.__cdpNodeMap || {};
            var n = map[$nodeId];
            if (!n) return;
            try {
                var el = document.querySelector('*[__cdpBeId="'+n.backendNodeId+'"]');
                if (!el) {
                    var all = document.querySelectorAll(n.nodeName || '*');
                    for (var i = 0; i < all.length; i++) {
                        if (n.attributes && n.attributes.length > 0) {
                            var match = true;
                            for (var j = 0; j < Math.min(n.attributes.length, 6); j+=2) {
                                if (all[i].getAttribute(n.attributes[j]) !== n.attributes[j+1]) { match=false; break; }
                            }
                            if (match) { el = all[i]; break; }
                        }
                    }
                    el = el || all[0] || document.body;
                }
                el.setAttribute(${gson.toJson(attrName)}, ${gson.toJson(attrValue)});
            } catch(e) {}
        })();
    """.trimIndent()

    fun domGetContentQuadsJs(nodeId: Int, backendNodeId: Int): String = """
        (function() {
            var map = window.__cdpNodeMap || {};
            var n = ($nodeId > 0 && map[$nodeId]) ? map[$nodeId]
                  : ($backendNodeId > 0 && map[$backendNodeId]) ? map[$backendNodeId]
                  : null;
            if (!n) return JSON.stringify([]);
            try {
                var el = document.querySelector('*[__cdpBeId="'+n.backendNodeId+'"]');
                if (el) {
                    var r = el.getBoundingClientRect();
                    return JSON.stringify([[r.left,r.top,r.right,r.top,r.right,r.bottom,r.left,r.bottom]]);
                }
            } catch(e) {}
            return JSON.stringify([[0,0,window.innerWidth,0,window.innerWidth,window.innerHeight,0,window.innerHeight]]);
        })();
    """.trimIndent()

    fun domGetOuterHTMLJs(nodeId: Int, backendNodeId: Int): String = """
        (function() {
            var map = window.__cdpNodeMap || {};
            var n = ($nodeId > 0 && map[$nodeId]) ? map[$nodeId]
                  : ($backendNodeId > 0 && map[$backendNodeId]) ? map[$backendNodeId]
                  : null;
            if (!n) return document.documentElement.outerHTML || '';
            try {
                var el = document.querySelector('*[__cdpBeId="'+n.backendNodeId+'"]');
                if (el) return el.outerHTML || '';
                if (n.nodeName) {
                    var els = document.getElementsByTagName(n.nodeName);
                    if (els && els.length > 0) return els[0].outerHTML || '';
                }
            } catch(e) {}
            return document.documentElement.outerHTML || '';
        })();
    """.trimIndent()

    // ── Input JS 片段生成 ────────────────────────────────────────────

    fun inputMouseEventJs(
        eventType: String,
        x: Double,
        y: Double,
        button: String,
        clickCount: Int,
        deltaX: Double = 0.0,
        deltaY: Double = 0.0
    ): String {
        val buttonCode = when (button) {
            "left" -> 0; "middle" -> 1; "right" -> 2; "back" -> 3; "forward" -> 4; else -> 0
        }
        val buttonsDown = when (eventType) { "mousePressed" -> 1 shl buttonCode; else -> 0 }
        return """
            (function() {
                var el = document.elementFromPoint($x, $y) || document.body;
                var init = {
                    bubbles: true, cancelable: true, view: window,
                    clientX: $x, clientY: $y, button: $buttonCode,
                    buttons: $buttonsDown, detail: $clickCount
                };
                if ('$eventType' === 'mouseMoved') {
                    el.dispatchEvent(new MouseEvent('mousemove', init));
                    var hoverEl = document.elementFromPoint($x, $y);
                    if (hoverEl) { hoverEl.dispatchEvent(new MouseEvent('mouseover', init)); hoverEl.dispatchEvent(new MouseEvent('mouseenter', init)); }
                } else if ('$eventType' === 'mousePressed') {
                    el.dispatchEvent(new MouseEvent('mousedown', init)); el.focus();
                } else if ('$eventType' === 'mouseReleased') {
                    el.dispatchEvent(new MouseEvent('mouseup', init)); el.dispatchEvent(new MouseEvent('click', init));
                    if ($clickCount > 1) el.dispatchEvent(new MouseEvent('dblclick', init));
                } else if ('$eventType' === 'mouseWheel') {
                    el.dispatchEvent(new WheelEvent('wheel', { bubbles:true, cancelable:true, clientX:$x, clientY:$y, deltaX:$deltaX, deltaY:$deltaY, deltaMode:0 }));
                }
                return 'ok';
            })();
        """.trimIndent()
    }

    fun inputKeyEventJs(
        key: String,
        text: String,
        code: String,
        windowsVirtualKeyCode: Int,
        eventType: String
    ): String {
        val keyJson = gson.toJson(key)
        val textJson = gson.toJson(text)
        val codeJson = gson.toJson(code)
        val typeJson = gson.toJson(eventType)
        return """
            (function() {
                var el = document.activeElement || document.body;
                var key = $keyJson, text = $textJson, code = $codeJson;
                var eventType = $typeJson, keyCode = $windowsVirtualKeyCode;
                var shiftKey = key && key.startsWith && key.startsWith('Shift');
                var keyParams = { key:key, code:code, keyCode:keyCode, bubbles:true, cancelable:true, shiftKey:shiftKey };
                if (eventType === 'keyDown') {
                    el.dispatchEvent(new KeyboardEvent('keydown', keyParams));
                } else if (eventType === 'keyUp') {
                    el.dispatchEvent(new KeyboardEvent('keyup', keyParams));
                } else if (eventType === 'char' || eventType === 'keyDown') {
                    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                        if (el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) {
                            el.checked = !el.checked; el.dispatchEvent(new Event('change', {bubbles:true}));
                        } else {
                            var val = el.value || el.textContent || '';
                            var sel = el.selectionStart !== undefined
                                ? {start: el.selectionStart || val.length, end: el.selectionEnd || val.length}
                                : {start: val.length, end: val.length};
                            if (key === 'Backspace') {
                                if (sel.start > 0 && sel.start === sel.end) {
                                    el.value = val.substring(0, sel.start - 1) + val.substring(sel.end);
                                    el.selectionStart = el.selectionEnd = sel.start - 1;
                                } else { el.value = val.substring(0, sel.start) + val.substring(sel.end); el.selectionStart = el.selectionEnd = sel.start; }
                            } else if (key === 'Delete') {
                                if (sel.end < val.length && sel.start === sel.end) {
                                    el.value = val.substring(0, sel.start) + val.substring(sel.start + 1);
                                    el.selectionStart = el.selectionEnd = sel.start;
                                } else { el.value = val.substring(0, sel.start) + val.substring(sel.end); el.selectionStart = el.selectionEnd = sel.start; }
                            } else if (key === 'Enter') {
                                if (el.tagName === 'TEXTAREA' || el.isContentEditable) {
                                    el.value = val.substring(0, sel.start) + '
' + val.substring(sel.end);
                                    el.selectionStart = el.selectionEnd = sel.start + 1;
                                }
                                var form = el.closest('form');
                                if (form) { el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true})); form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})); }
                            } else if (key === 'Escape') {
                                el.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true})); el.blur && el.blur();
                            } else if (key === 'Tab') {
                                el.dispatchEvent(new KeyboardEvent('keydown',{key:'Tab',bubbles:true,cancelable:true}));
                            } else if (text && text.length > 0) {
                                el.value = val.substring(0, sel.start) + text + val.substring(sel.end);
                                el.selectionStart = el.selectionEnd = sel.start + text.length;
                            }
                        }
                        el.dispatchEvent(new InputEvent('input', {bubbles:true, cancelable:true}));
                        return 'typed';
                    }
                    if (el) el.dispatchEvent(new KeyboardEvent('keydown', keyParams));
                    return 'global';
                }
                return 'ok';
            })();
        """.trimIndent()
    }

    fun inputInsertTextJs(text: String): String {
        val textJson = gson.toJson(text)
        val textLen = text.length
        return """
            (function() {
                var el = document.activeElement;
                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                    var val = el.value || '';
                    var sel = el.selectionStart !== undefined
                        ? {start: el.selectionStart || val.length, end: el.selectionEnd || val.length}
                        : {start: val.length, end: val.length};
                    el.value = val.substring(0, sel.start) + $textJson + val.substring(sel.end);
                    el.selectionStart = el.selectionEnd = sel.start + $textLen;
                    el.dispatchEvent(new InputEvent('input', {bubbles:true, cancelable:true}));
                    return 'ok';
                }
                return 'nosel';
            })();
        """.trimIndent()
    }
}
