package com.clawagent.cdp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单条 CDP WebSocket 连接的消息处理器。
 *
 * 职责：解析 CDP JSON-RPC，路由到对应方法处理器，并通过 [WebViewBridge] 驱动 WebView。
 *
 * 扩展点（为后续 MCP 准备）：
 *   - 新增 method 只需在 [handleCdpMessage] 的 `when` 块中追加分支。
 *   - 若要接入 MCP，可在 Activity 级别将 MCP 工具注册为额外的 [WebViewBridge] 操作，
 *     或在 [handleCdpMessage] 之后增加 MCP 分发入口。
 */
class CdpSession(
    handshake: NanoHTTPD.IHTTPSession,
    private val config: CdpConfig,
    private val bridge: WebViewBridge,
    private val server: CdpServer,
    private val isPageSession: Boolean,
    private val onClose: () -> Unit,
) : NanoWSD.WebSocket(handshake) {

    private val TAG = "WebViewCDP"
    private val gson = Gson()
    private val jsonParser = JsonParser()

    private val pageSessionId = config.pageSessionId

    /** 防止多次 setAutoAttach 产生重复 attachedToTarget 事件 */
    private var hasSentAttached = false

    /** 协程 scope：处理 CDP 命令，不阻塞 NanoWSD IO 线程 */
    private val socketScope = CoroutineScope(CDP_DISPATCHER + SupervisorJob())

    // CDP state（节点注册表等）
    private val cdpNextNodeId = AtomicInteger(1000)
    private val cdpExecutionContextId = 1

    companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        val CDP_DISPATCHER: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)
    }

    // ── WebSocket 生命周期 ──────────────────────────────────────────

    override fun onOpen() {
        Log.i(TAG, "CDP WebSocket onOpen, isPage=$isPageSession")
    }

    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
        Log.i(TAG, "CDP WebSocket onClose: $reason")
        socketScope.cancel()
        bridge.appendLog("CDP WS 断开: $reason")
        onClose()
    }

    override fun onMessage(frame: NanoWSD.WebSocketFrame) {
        val text = frame.textPayload
        if (text.isNullOrBlank()) return

        Log.i(TAG, ">>> WS received (${text.length} chars)")
        try {
            val msg = jsonParser.parse(text).asJsonObject
            val id = msg.get("id")?.asInt ?: -1
            val method = msg.get("method")?.asString ?: ""
            val params = msg.getAsJsonObject("params") ?: JsonObject()
            val sessionId = msg.get("sessionId")?.asString ?: ""

            Log.i(TAG, "CDP req #$id: $method session=${sessionId.take(12)}")

            // 每条命令独立协程，互不阻塞
            socketScope.launch {
                val start = System.currentTimeMillis()
                try {
                    handleCdpMessage(id, method, params, sessionId)
                    Log.i(TAG, "Handler done #$id: $method in ${System.currentTimeMillis() - start}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "Handler failed #$id: $method after ${System.currentTimeMillis() - start}ms: ${e.message}", e)
                    sendResult(id, sessionId) { JsonObject() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CDP parse error: ${e.message}")
            bridge.appendLog("CDP 解析错误: ${e.message}")
            sendError(0, "Parse error: ${e.message}")
        }
    }

    override fun onPong(frame: NanoWSD.WebSocketFrame) {}

    override fun onException(exception: IOException) {
        val msg = exception.message
        if (msg != null && (msg.contains("EOF") || msg.contains("end of stream"))) {
            Log.i(TAG, "CDP WebSocket closed")
        } else {
            Log.e(TAG, "CDP WebSocket exception: ${msg ?: exception::class.simpleName}")
        }
    }

    // ── CDP 消息路由 ────────────────────────────────────────────────

    private suspend fun handleCdpMessage(id: Int, method: String, params: JsonObject, sessionId: String) {
        val start = System.currentTimeMillis()
        var success = true
        var errorMessage: String? = null
        Log.i(TAG, ">>> CDP request #$id: $method (sessionId: ${if (sessionId.isNotEmpty()) "${sessionId.take(8)}..." else "none"})")
        try {
            when (method) {

                // ── Browser ──────────────────────────────────────
                "Browser.getVersion" ->
                    sendResult(id, sessionId) { buildBrowserVersion() }

                "Browser.getWindowForTarget" ->
                    sendResult(id, sessionId) {
                        JsonObject().apply {
                            addProperty("windowId", 1)
                            add("bounds", JsonObject().apply {
                                addProperty("left", 0); addProperty("top", 0)
                                addProperty("width", bridge.viewportWidth)
                                addProperty("height", bridge.viewportHeight)
                                addProperty("windowState", "normal")
                            })
                        }
                    }

                "Browser.setDownloadBehavior" -> sendResult(id, sessionId) { JsonObject() }

                "Browser.getBrowserCommandLine" ->
                    sendResult(id, sessionId) { JsonObject().apply { add("arguments", JsonArray()) } }

                "Browser.grantPermissions" -> sendResult(id, sessionId) { JsonObject() }

                "Browser.resetPermissions" -> sendResult(id, sessionId) { JsonObject() }

                "Browser.close" -> {
                    Log.i(TAG, "Browser.close - clearing WebView content")
                    bridge.clearContent()
                    sendResult(id, sessionId) { JsonObject() }
                }

                // ── Target ──────────────────────────────────────
                "Target.attachToBrowserTarget" ->
                    sendResult(id, sessionId) { JsonObject().apply { addProperty("sessionId", "browser-session") } }

                "Target.setDiscoverTargets" ->
                    sendResult(id, sessionId) { JsonObject() }
                // 已存在的 target 由 setAutoAttach 上报，setDiscoverTargets 不重复推送

                "Target.setAutoAttach" -> {
                    val autoAttach = params.get("autoAttach")?.asBoolean ?: false
                    val waitForDebuggerOnStart = params.get("waitForDebuggerOnStart")?.asBoolean ?: false
                    val flatten = params.get("flatten")?.asBoolean ?: false
                    Log.i(TAG, "Target.setAutoAttach: autoAttach=$autoAttach waitForDebugger=$waitForDebuggerOnStart flatten=$flatten")
                    sendResult(id, sessionId) { JsonObject() }
                    if (autoAttach && !hasSentAttached) {
                        hasSentAttached = true
                        sendTargetAttachedEvent(config.pageTargetId, waitForDebuggerOnStart)
                    }
                }

                "Target.getTargets" ->
                    sendResult(id, sessionId) { buildGetTargetsResult() }

                "Target.createTarget" -> {
                    val url = params.get("url")?.asString ?: "about:blank"
                    if (url != "about:blank" && url.isNotBlank()) bridge.navigate(url)
                    sendResult(id, sessionId) { JsonObject().apply { addProperty("targetId", config.pageTargetId) } }
                    hasSentAttached = false
                }

                "Target.attachToTarget" -> {
                    val flatten = params.get("flatten")?.asBoolean ?: false
                    Log.i(TAG, "Target.attachToTarget flatten=$flatten sessionId=$sessionId")
                    sendResult(id, sessionId) { JsonObject().apply { addProperty("sessionId", pageSessionId) } }
                }

                "Target.detachFromTarget" -> sendResult(id, sessionId) { JsonObject() }

                "Target.closeTarget" ->
                    sendResult(id, sessionId) { JsonObject().apply { addProperty("success", true) } }

                "Target.activateTarget" -> sendResult(id, sessionId) { JsonObject() }

                "Target.getTargetInfo" -> {
                    val targetId = params.get("targetId")?.asString ?: config.pageTargetId
                    sendResult(id, sessionId) {
                        JsonObject().apply {
                            add("targetInfo", JsonObject().apply {
                                addProperty("targetId", targetId)
                                addProperty("type", "page")
                                addProperty("title", bridge.pageTitle.ifEmpty { "WebView Page" })
                                addProperty("url", bridge.currentUrl.ifEmpty { "about:blank" })
                                addProperty("attached", true)
                                addProperty("canAccessOpener", false)
                                addProperty("browserContextId", config.browserContextId)
                                addProperty("waitingForDebugger", false)
                            })
                        }
                    }
                }

                // ── Page ────────────────────────────────────────
                "Page.enable" -> sendResult(id, sessionId) { JsonObject() }
                "Page.disable" -> sendResult(id, sessionId) { JsonObject() }

                "Page.getNavigationHistory" ->
                    sendResult(id, sessionId) { buildNavigationHistory() }

                "Page.getFrameTree" ->
                    sendResult(id, sessionId) { buildFrameTree() }

                "Page.navigate" -> {
                    val url = params.get("url")?.asString ?: ""
                    Log.i(TAG, "Page.navigate to: $url")
                    val navResult = bridge.navigateAsync(url)
                    sendResult(id, sessionId) {
                        JsonObject().apply {
                            addProperty("frameId", config.pageTargetId)
                            addProperty("loaderId", navResult.loaderId)
                            if (!navResult.success) addProperty("errorText", navResult.errorText ?: "Navigation failed")
                        }
                    }
                    if (!navResult.success) success = false
                }

                "Page.bringToFront" -> sendResult(id, sessionId) { JsonObject() }

                "Page.getLayoutMetrics" ->
                    sendResult(id, sessionId) { buildLayoutMetrics() }

                "Page.captureScreenshot" -> {
                    val screenshotBytes = bridge.captureScreenshot()
                    if (screenshotBytes != null) {
                        val base64 = android.util.Base64.encodeToString(screenshotBytes, android.util.Base64.NO_WRAP)
                        sendResult(id, sessionId) { JsonObject().apply { addProperty("data", base64) } }
                        bridge.appendLog("CDP screenshot: ${screenshotBytes.size} bytes")
                    } else {
                        sendError(id, "Screenshot failed", sessionId)
                    }
                }

                "Page.startScreencast" -> sendResult(id, sessionId) { JsonObject() }
                "Page.stopScreencast" -> sendResult(id, sessionId) { JsonObject() }
                "Page.setAdBlockingEnabled" -> sendResult(id, sessionId) { JsonObject() }

                "Page.printToPDF" -> sendResult(id, sessionId) {
                    JsonObject().apply { addProperty("data", ""); addProperty("stream", "") }
                }

                "Page.getManifestIcons" -> sendResult(id, sessionId) { JsonObject() }

                "Page.getAppManifest" -> sendResult(id, sessionId) {
                    JsonObject().apply {
                        addProperty("url", bridge.currentUrl.ifEmpty { "about:blank" })
                        addProperty("errors", JsonArray().toString())
                        addProperty("data", "")
                    }
                }

                "Page.reload" -> {
                    val ignoreCache = params.get("ignoreCache")?.asBoolean ?: false
                    bridge.evaluateJs(if (ignoreCache) "location.reload(true)" else "location.reload()")
                    sendResult(id, sessionId) { JsonObject() }
                }

                "Page.createIsolatedWorld" ->
                    sendResult(id, sessionId) { JsonObject().apply { addProperty("executionContextId", cdpExecutionContextId) } }

                "Page.addScriptToEvaluateOnNewDocument" -> {
                    val identifier = params.get("identifier")?.asString ?: UUID.randomUUID().toString()
                    sendResult(id, sessionId) { JsonObject().apply { addProperty("identifier", identifier) } }
                }

                "Page.handleJavaScriptDialog" -> sendResult(id, sessionId) { JsonObject() }

                "Page.setBypassCSP" -> sendResult(id, sessionId) { JsonObject() }

                "Page.navigateToHistoryEntry" -> {
                    val entryId = params.get("entryId")?.asInt ?: -1
                    bridge.evaluateJs("history.go($entryId)")
                    sendResult(id, sessionId) { JsonObject() }
                }

                "Page.captureSnapshot" ->
                    sendResult(id, sessionId) { JsonObject().apply { addProperty("data", "") } }

                "Page.setLifecycleEventsEnabled" -> {
                    sendResult(id, sessionId) { JsonObject() }
                    val enabled = params.get("enabled")?.asBoolean ?: false
                    if (enabled) {
                        val ts = System.currentTimeMillis() / 1000.0
                        listOf("commit", "DOMContentLoaded", "load", "networkAlmostIdle", "networkIdle").forEach { name ->
                            sendEventWithSession("Page.lifecycleEvent", JsonObject().apply {
                                addProperty("frameId", config.pageTargetId)
                                addProperty("loaderId", "loader-${config.pageTargetId}")
                                addProperty("name", name)
                                addProperty("timestamp", ts)
                            }, sessionId)
                        }
                        Log.i(TAG, "Page.setLifecycleEventsEnabled: sent initial lifecycle events sessionId=$sessionId")
                    }
                }

                // ── Runtime ─────────────────────────────────────
                "Runtime.enable" -> {
                    val execCtx = buildExecutionContext()
                    sendResult(id, sessionId) {
                        JsonObject().apply { add("executionContexts", JsonArray().apply { add(execCtx) }) }
                    }
                    val ctxEvent = JsonObject().apply { add("context", execCtx) }
                    if (sessionId.isNotEmpty()) sendEventWithSession("Runtime.executionContextCreated", ctxEvent, sessionId)
                    else sendEvent("Runtime.executionContextCreated", ctxEvent)
                }

                "Runtime.runIfWaitingForDebugger" -> sendResult(id, sessionId) { JsonObject() }

                "Runtime.evaluate" -> {
                    val expression = params.get("expression")?.asString ?: ""
                    Log.i(TAG, "Runtime.evaluate: ${expression.length} chars")
                    val jsResult = bridge.evaluateJs(expression)
                    Log.i(TAG, "Runtime.evaluate: result ${jsResult.length} chars")
                    sendResult(id, sessionId) {
                        JsonObject().apply { add("result", CdpJsHelpers.parseJsResult(jsResult)) }
                    }
                }

                "Runtime.callFunctionOn" -> {
                    val functionDeclaration = params.get("functionDeclaration")?.asString ?: ""
                    val objectId = params.get("objectId")?.asString ?: ""
                    val returnByValue = params.get("returnByValue")?.asBoolean ?: true
                    val arguments = params.getAsJsonArray("arguments") ?: JsonArray()
                    val awaitPromise = params.get("awaitPromise")?.asBoolean ?: false
                    val argsJs = CdpJsHelpers.buildCallArgumentsJs(arguments)
                    val jsExpr = """
                        (function() {
                            var fn = ($functionDeclaration);
                            var args = $argsJs;
                            var obj = window.__cdpObjectRegistry && window.__cdpObjectRegistry['$objectId']
                                ? window.__cdpObjectRegistry['$objectId'] : window;
                            ${if (awaitPromise) "return fn.apply(obj, args);" else "return JSON.stringify(fn.apply(obj, args));"}
                        })();
                    """.trimIndent()
                    val jsResult = bridge.evaluateJs(jsExpr)
                    sendResult(id, sessionId) {
                        JsonObject().apply { add("result", CdpJsHelpers.parseJsResult(jsResult)) }
                    }
                }

                "Runtime.terminateExecution" -> sendResult(id, sessionId) { JsonObject() }

                // ── Accessibility ────────────────────────────────
                "Accessibility.enable" -> sendResult(id, sessionId) { JsonObject() }
                "Accessibility.disable" -> sendResult(id, sessionId) { JsonObject() }

                "Accessibility.getFullAXTree" -> {
                    Log.i(TAG, "Accessibility.getFullAXTree called")
                    val axTreeResult = bridge.evaluateJs(AX_TREE_JS)
                    Log.i(TAG, "Accessibility.getFullAXTree: JS result ${axTreeResult.length} chars")
                    val clean = CdpJsHelpers.cleanJsonString(axTreeResult)
                    try {
                        val nodesArray = jsonParser.parse(clean).asJsonArray
                        Log.i(TAG, "Accessibility.getFullAXTree: ${nodesArray.size()} nodes")
                        sendResult(id, sessionId) { JsonObject().apply { add("nodes", nodesArray) } }
                    } catch (e: Exception) {
                        Log.w(TAG, "Accessibility.getFullAXTree parse failed: ${e.message}")
                        sendResult(id, sessionId) {
                            JsonObject().apply {
                                add("nodes", JsonArray().apply {
                                    add(JsonObject().apply {
                                        addProperty("nodeId", "root-0")
                                        addProperty("ignored", false)
                                        add("role", JsonObject().apply { addProperty("value", "RootWebArea") })
                                        add("name", JsonObject().apply { addProperty("value", bridge.pageTitle.ifEmpty { "WebView" }) })
                                        add("childIds", JsonArray())
                                    })
                                })
                            }
                        }
                    }
                }

                "Accessibility.getPartialAXTree" ->
                    sendResult(id, sessionId) {
                        JsonObject().apply {
                            add("nodes", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("nodeId", "root-0")
                                    addProperty("ignored", false)
                                    add("role", JsonObject().apply { addProperty("value", "RootWebArea") })
                                    add("name", JsonObject().apply { addProperty("value", bridge.pageTitle.ifEmpty { "WebView" }) })
                                })
                            })
                        }
                    }

                // ── DOM ─────────────────────────────────────────
                "DOM.enable" -> sendResult(id, sessionId) { JsonObject() }
                "DOM.disable" -> sendResult(id, sessionId) { JsonObject() }

                "DOM.getDocument" -> {
                    val depth = params.get("depth")?.asInt ?: 2
                    val jsResult = bridge.evaluateJs(CdpJsHelpers.domGetDocumentJs(depth, cdpNextNodeId.get()))
                    val clean = CdpJsHelpers.cleanJsonString(jsResult)
                    try {
                        val rootNode = jsonParser.parse(clean).asJsonObject
                        sendResult(id, sessionId) { JsonObject().apply { add("root", rootNode) } }
                    } catch (_: Exception) { sendEmptyResult(id, sessionId) }
                }

                "DOM.getOuterHTML" -> {
                    val nodeId = params.get("nodeId")?.asInt ?: 0
                    val backendNodeId = params.get("backendNodeId")?.asInt ?: 0
                    val jsResult = bridge.evaluateJs(CdpJsHelpers.domGetOuterHTMLJs(nodeId, backendNodeId))
                    val clean = CdpJsHelpers.cleanJsonString(jsResult)
                    sendResult(id, sessionId) { JsonObject().apply { addProperty("outerHTML", clean) } }
                }

                "DOM.describeNode" -> {
                    val nodeId = params.get("nodeId")?.asInt ?: 0
                    val backendNodeId = params.get("backendNodeId")?.asInt ?: 0
                    val jsResult = bridge.evaluateJs(CdpJsHelpers.domDescribeNodeJs(nodeId, backendNodeId))
                    val clean = CdpJsHelpers.cleanJsonString(jsResult)
                    try {
                        val node = jsonParser.parse(clean).asJsonObject
                        sendResult(id, sessionId) { JsonObject().apply { add("node", node) } }
                    } catch (_: Exception) { sendError(id, "Node not found", sessionId) }
                }

                "DOM.querySelectorAll" -> {
                    val nodeId = params.get("nodeId")?.asInt ?: 0
                    val selector = params.get("selector")?.asString ?: "*"
                    val jsResult = bridge.evaluateJs(CdpJsHelpers.domQuerySelectorAllJs(nodeId, selector, cdpNextNodeId.get()))
                    val clean = CdpJsHelpers.cleanJsonString(jsResult)
                    try {
                        val arr = jsonParser.parse(clean).asJsonArray
                        sendResult(id, sessionId) { JsonObject().apply { add("nodeIds", arr) } }
                    } catch (_: Exception) {
                        sendResult(id, sessionId) { JsonObject().apply { add("nodeIds", JsonArray()) } }
                    }
                }

                "DOM.pushNodesByBackendIdsToFrontend" -> {
                    val ids = mutableListOf<Int>()
                    params.getAsJsonArray("backendNodeIds")?.forEach { ids.add(it.asInt) }
                    val jsResult = bridge.evaluateJs(CdpJsHelpers.domPushBackendIdsJs(ids, cdpNextNodeId.get()))
                    val clean = CdpJsHelpers.cleanJsonString(jsResult)
                    try {
                        val arr = jsonParser.parse(clean).asJsonArray
                        sendResult(id, sessionId) { JsonObject().apply { add("nodeIds", arr) } }
                    } catch (_: Exception) {
                        sendResult(id, sessionId) { JsonObject().apply { add("nodeIds", JsonArray()) } }
                    }
                }

                "DOM.resolveNode" -> {
                    val nodeId = params.get("nodeId")?.asInt ?: 0
                    val backendNodeId = params.get("backendNodeId")?.asInt ?: 0
                    val jsResult = bridge.evaluateJs(CdpJsHelpers.domResolveNodeJs(nodeId, backendNodeId))
                    val clean = CdpJsHelpers.cleanJsonString(jsResult)
                    val objId = if (clean != "null" && clean.isNotBlank()) clean else "obj-0"
                    sendResult(id, sessionId) {
                        JsonObject().apply {
                            add("object", JsonObject().apply {
                                addProperty("objectId", objId)
                                addProperty("type", "object")
                                addProperty("subtype", "node")
                            })
                        }
                    }
                }

                "DOM.setAttributeValue" -> {
                    val nodeId = params.get("nodeId")?.asInt ?: 0
                    val name = params.get("name")?.asString ?: ""
                    val value = params.get("value")?.asString ?: ""
                    bridge.evaluateJs(CdpJsHelpers.domSetAttributeJs(nodeId, name, value))
                    sendResult(id, sessionId) { JsonObject() }
                }

                "DOM.getContentQuads" -> {
                    val nodeId = params.get("nodeId")?.asInt ?: 0
                    val backendNodeId = params.get("backendNodeId")?.asInt ?: 0
                    val jsResult = bridge.evaluateJs(CdpJsHelpers.domGetContentQuadsJs(nodeId, backendNodeId))
                    val clean = CdpJsHelpers.cleanJsonString(jsResult)
                    try {
                        val quadArr = jsonParser.parse(clean).asJsonArray
                        sendResult(id, sessionId) { JsonObject().apply { add("quads", quadArr) } }
                    } catch (_: Exception) {
                        sendResult(id, sessionId) {
                            JsonObject().apply {
                                add("quads", JsonArray().apply {
                                    add(JsonArray().apply {
                                        add(0); add(0); add(bridge.viewportWidth); add(0)
                                        add(bridge.viewportWidth); add(bridge.viewportHeight); add(0); add(bridge.viewportHeight)
                                    })
                                })
                            }
                        }
                    }
                }

                // ── Input ────────────────────────────────────────
                "Input.dispatchMouseEvent" -> {
                    val eventType = params.get("type")?.asString ?: ""
                    val x = params.get("x")?.asDouble ?: 0.0
                    val y = params.get("y")?.asDouble ?: 0.0
                    val button = params.get("button")?.asString ?: "left"
                    val clickCount = params.get("clickCount")?.asInt ?: 1
                    val deltaX = params.get("deltaX")?.asDouble ?: 0.0
                    val deltaY = params.get("deltaY")?.asDouble ?: 0.0
                    val mouseJs = CdpJsHelpers.inputMouseEventJs(eventType, x, y, button, clickCount, deltaX, deltaY)
                    bridge.evaluateJs(mouseJs)
                    sendResult(id, sessionId) { JsonObject() }
                }

                "Input.dispatchKeyEvent" -> {
                    val key = params.get("key")?.asString ?: ""
                    val text = params.get("text")?.asString ?: key
                    val code = params.get("code")?.asString ?: ""
                    val windowsVirtualKeyCode = params.get("windowsVirtualKeyCode")?.asInt ?: 0
                    val type = params.get("type")?.asString ?: ""
                    bridge.evaluateJs(CdpJsHelpers.inputKeyEventJs(key, text, code, windowsVirtualKeyCode, type))
                    sendResult(id, sessionId) { JsonObject() }
                }

                "Input.insertText" -> {
                    val text = params.get("text")?.asString ?: ""
                    bridge.evaluateJs(CdpJsHelpers.inputInsertTextJs(text))
                    sendResult(id, sessionId) { JsonObject() }
                }

                "Input.dispatchTouchEvent" -> sendResult(id, sessionId) { JsonObject() }

                // ── Emulation ────────────────────────────────────
                "Emulation.setDeviceMetricsOverride" ->
                    sendError(id, "Device metrics override not supported on Android WebView. " +
                        "Viewport is controlled by device screen size. Use Page.getLayoutMetrics for actual dimensions.", sessionId)
                "Emulation.clearDeviceMetricsOverride" ->
                    sendError(id, "Device metrics override not supported on Android WebView.", sessionId)
                "Emulation.setUserAgentOverride" ->
                    sendError(id, "User agent override not supported on Android WebView.", sessionId)
                "Emulation.setTouchEmulationEnabled" ->
                    sendError(id, "Touch emulation not supported. Android has native touch support.", sessionId)
                "Emulation.setEmulatedMedia" ->
                    sendError(id, "Emulated media not supported on Android WebView.", sessionId)
                "Emulation.setFocusEmulationEnabled" ->
                    sendError(id, "Focus emulation not supported on Android WebView.", sessionId)
                "Emulation.setLocaleOverride" ->
                    sendError(id, "Locale override not supported. Controlled by device settings.", sessionId)
                "Emulation.setTimezoneOverride" ->
                    sendError(id, "Timezone override not supported. Controlled by device settings.", sessionId)
                "Emulation.setPageScaleFactor" ->
                    sendError(id, "Page scale factor not supported. Use viewport meta tag or CSS zoom.", sessionId)
                "Emulation.canEmulate" ->
                    sendResult(id, sessionId) { JsonObject().apply { addProperty("result", false) } }

                // ── Network / Performance / Log / Inspector / Overlay / Fetch / DeviceAccess ──
                "Network.enable",
                "Network.setCacheDisabled",
                "Performance.enable",
                "Log.enable",
                "Inspector.enable",
                "Overlay.enable",
                "Overlay.setShowPaintRects",
                "Fetch.enable",
                "DeviceAccess.enable" -> sendResult(id, sessionId) { JsonObject() }

                // ── 未知命令 ─────────────────────────────────────
                else -> {
                    Log.w(TAG, "Unknown CDP method: $method")
                    bridge.appendLog("CDP: unknown method '$method'")
                    sendEmptyResult(id, sessionId)
                }
            }
        } catch (e: Exception) {
            success = false
            errorMessage = e.message
            Log.e(TAG, "CDP handler error for $method: ${e.message}", e)
            bridge.appendLog("CDP 错误: $method - ${e.message}")
            sendError(id, "${e.message}", sessionId)
        } finally {
            val elapsed = System.currentTimeMillis() - start
            Log.i(TAG, "<<< CDP response #$id: $method [${if (success) "OK" else "FAIL"}] ${elapsed}ms${if (errorMessage != null) " error=$errorMessage" else ""}")
        }
    }

    // ── 响应发送 ────────────────────────────────────────────────────

    fun sendResult(id: Int, sessionId: String, builder: () -> JsonObject) {
        val resultObj = builder()
        val msg = JsonObject().apply {
            addProperty("id", id)
            add("result", resultObj)
            if (sessionId.isNotEmpty()) addProperty("sessionId", sessionId)
        }
        val json = gson.toJson(msg)
        Log.i(TAG, "Sending result #$id: ${json.take(200)}${if (json.length > 200) "..." else ""}")
        try { send(json) } catch (e: Exception) { Log.e(TAG, "Failed to send result #$id: ${e.message}", e) }
    }

    fun sendError(id: Int, errorMessage: String, sessionId: String = "") {
        val msg = JsonObject().apply {
            addProperty("id", id)
            add("error", JsonObject().apply { addProperty("message", errorMessage) })
            if (sessionId.isNotEmpty()) addProperty("sessionId", sessionId)
        }
        try { send(gson.toJson(msg)) } catch (_: Exception) {}
    }

    private fun sendEmptyResult(id: Int, sessionId: String) = sendResult(id, sessionId) { JsonObject() }

    fun sendEvent(method: String, params: JsonObject) {
        val msg = JsonObject().apply { addProperty("method", method); add("params", params) }
        try { send(gson.toJson(msg)) } catch (_: Exception) {}
    }

    fun sendEventWithSession(method: String, params: JsonObject, sessionId: String) {
        val msg = JsonObject().apply {
            addProperty("method", method); add("params", params); addProperty("sessionId", sessionId)
        }
        try { send(gson.toJson(msg)) } catch (_: Exception) {}
    }

    // ── CDP 结果构建 ────────────────────────────────────────────────

    private fun buildBrowserVersion() = JsonObject().apply {
        addProperty("protocolVersion", CdpConfig.PROTOCOL_VERSION)
        addProperty("product", CdpConfig.BROWSER_NAME)
        addProperty("revision", "@000000")
        addProperty("userAgent", CdpConfig.USER_AGENT)
        addProperty("jsVersion", CdpConfig.V8_VERSION)
    }

    private fun buildGetTargetsResult() = JsonObject().apply {
        add("targetInfos", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("targetId", config.browserTargetId)
                addProperty("type", "browser")
                addProperty("title", "WebView Browser")
                addProperty("url", "")
                addProperty("attached", true)
                addProperty("canAccessOpener", false)
                addProperty("browserContextId", config.browserContextId)
            })
            add(JsonObject().apply {
                addProperty("targetId", config.pageTargetId)
                addProperty("type", "page")
                addProperty("title", bridge.pageTitle.ifEmpty { "WebView Page" })
                addProperty("url", bridge.currentUrl.ifEmpty { "about:blank" })
                addProperty("attached", false)
                addProperty("canAccessOpener", false)
                addProperty("browserContextId", config.browserContextId)
            })
        })
    }

    private fun buildNavigationHistory() = JsonObject().apply {
        addProperty("currentIndex", 0)
        add("entries", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("id", 0)
                addProperty("url", bridge.currentUrl.ifEmpty { "about:blank" })
                addProperty("title", bridge.pageTitle.ifEmpty { "WebView" })
                addProperty("userTypedURL", bridge.currentUrl.ifEmpty { "about:blank" })
            })
        })
    }

    private fun buildFrameTree() = JsonObject().apply {
        add("frameTree", JsonObject().apply {
            add("frame", JsonObject().apply {
                addProperty("id", config.pageTargetId)
                addProperty("loaderId", UUID.randomUUID().toString())
                addProperty("url", bridge.currentUrl.ifEmpty { "about:blank" })
                addProperty("domainAndRegistry", "")
                addProperty("securityOrigin", "")
                addProperty("mimeType", "text/html")
                addProperty("unreachableUrl", "" as String?)
            })
        })
    }

    private fun buildLayoutMetrics() = JsonObject().apply {
        val w = bridge.viewportWidth; val h = bridge.viewportHeight
        add("layoutViewport", JsonObject().apply {
            addProperty("pageX", 0); addProperty("pageY", 0)
            addProperty("clientWidth", w); addProperty("clientHeight", h)
        })
        add("visualViewport", JsonObject().apply {
            addProperty("offsetX", 0); addProperty("offsetY", 0)
            addProperty("pageX", 0); addProperty("pageY", 0)
            addProperty("clientWidth", w); addProperty("clientHeight", h)
            addProperty("scale", 1.0); addProperty("zoom", 1.0)
        })
        add("contentSize", JsonObject().apply {
            addProperty("width", w); addProperty("height", h)
        })
    }

    private fun buildExecutionContext() = JsonObject().apply {
        addProperty("id", cdpExecutionContextId)
        addProperty("origin", bridge.currentUrl.ifEmpty { "http://localhost" })
        addProperty("name", "webview-main")
        addProperty("uniqueId", "ctx-$cdpExecutionContextId")
        add("auxData", JsonObject().apply {
            addProperty("isDefault", true)
            addProperty("type", "default")
            addProperty("frameId", config.pageTargetId)
        })
    }

    private fun sendTargetAttachedEvent(targetId: String, waitForDebugger: Boolean = false) {
        val event = JsonObject().apply {
            addProperty("method", "Target.attachedToTarget")
            add("params", JsonObject().apply {
                addProperty("sessionId", pageSessionId)
                add("targetInfo", JsonObject().apply {
                    addProperty("targetId", targetId)
                    addProperty("type", "page")
                    addProperty("title", bridge.pageTitle.ifEmpty { "WebView Page" })
                    addProperty("url", bridge.currentUrl.ifEmpty { "about:blank" })
                    addProperty("attached", true)
                    addProperty("canAccessOpener", false)
                    addProperty("browserContextId", config.browserContextId)
                })
                addProperty("waitingForDebugger", waitForDebugger)
            })
        }
        try { send(gson.toJson(event)) } catch (_: Exception) {}
        if (waitForDebugger) {
            sendEvent("Target.waitingForDebugger", JsonObject().apply {
                addProperty("sessionId", pageSessionId)
                addProperty("targetId", targetId)
                addProperty("reason", "attached")
            })
        }
    }

    // ── Input JS 片段构建 ───────────────────────────────────────────

    private fun buildMouseEventJs(
        eventType: String, x: Double, y: Double,
        buttonCode: Int, clickCount: Int, params: JsonObject
    ): String {
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
                    var deltaX = ${params.get("deltaX")?.asDouble ?: 0.0};
                    var deltaY = ${params.get("deltaY")?.asDouble ?: -100.0};
                    el.dispatchEvent(new WheelEvent('wheel', { bubbles:true, cancelable:true, clientX:$x, clientY:$y, deltaX:deltaX, deltaY:deltaY, deltaMode:0 }));
                }
                return 'ok';
            })();
        """.trimIndent()
    }

    private fun buildKeyEventJs(key: String, text: String, code: String, keyCode: Int, type: String): String {
        val keyJson = gson.toJson(key)
        val textJson = gson.toJson(text)
        val codeJson = gson.toJson(code)
        val typeJson = gson.toJson(type)
        return """
            (function() {
                var el = document.activeElement || document.body;
                var key = $keyJson, text = $textJson, code = $codeJson;
                var eventType = $typeJson, keyCode = $keyCode;
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
                                    el.value = val.substring(0, sel.start) + '\n' + val.substring(sel.end);
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

    private fun buildInsertTextJs(text: String): String {
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

    // ── 常量 JS ────────────────────────────────────────────────────

    private val AX_TREE_JS = """
        (function() {
            var allNodes = []; var count = 0; var maxNodes = 300;
            function walk(el, depth) {
                if (depth > 8 || count >= maxNodes) return null; count++;
                var nodeId = 'ax-' + count;
                var role = (el.getAttribute && el.getAttribute('role')) || (el.tagName ? el.tagName.toLowerCase() : 'unknown');
                var name = (el.getAttribute && el.getAttribute('aria-label')) || el.title || '';
                if (!name && el.tagName === 'INPUT') name = el.placeholder || el.name || '';
                if (!name && (el.tagName === 'A' || el.tagName === 'BUTTON')) name = (el.textContent || '').trim().substring(0, 80);
                var node = { nodeId:nodeId, ignored:false, role:{value:role}, name:{value:name}, childIds:[] };
                allNodes.push(node);
                var kids = el.children;
                if (kids && kids.length > 0 && depth < 8) {
                    var limit = depth === 0 ? 30 : 15;
                    for (var i = 0; i < Math.min(kids.length, limit) && count < maxNodes; i++) {
                        var childId = walk(kids[i], depth + 1);
                        if (childId) node.childIds.push(childId);
                    }
                }
                return nodeId;
            }
            walk(document.documentElement || document.body, 0);
            return JSON.stringify(allNodes);
        })();
    """.trimIndent()
}
