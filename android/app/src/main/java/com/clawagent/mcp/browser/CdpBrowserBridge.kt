package com.clawagent.mcp.browser

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.clawagent.cdp.NavigationResult
import com.clawagent.cdp.WebViewBridge
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 通过 CDP WebSocket 操控远程浏览器页面的 WebViewBridge 实现。
 * 直接通过 TcpForwarder 提供的 TCP 端口连接（无需 LocalSocketProxy）。
 */
class CdpBrowserBridge(
    private val pageInfo: DevToolsDiscovery.BrowserPageInfo
) : WebViewBridge {

    companion object {
        private const val TAG = "CdpBrowserBridge"
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()
    private val cmdId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val connected = AtomicBoolean(false)

    private var webSocket: WebSocket? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Map: normalised origin (e.g. "https://pc-settlement-lite-pro.pf.jd.com") -> CDP contextId
    private val frameContexts = ConcurrentHashMap<String, Int>()

    private var _currentUrl: String = pageInfo.url
    private var _pageTitle: String = pageInfo.title
    private var _viewportWidth: Int = 0
    private var _viewportHeight: Int = 0

    override val currentUrl: String get() = _currentUrl
    override val pageTitle: String get() = _pageTitle
    override val viewportWidth: Int get() = _viewportWidth
    override val viewportHeight: Int get() = _viewportHeight

    val isConnected: Boolean get() = connected.get()

    fun connect(): Boolean {
        if (connected.get()) return true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val wsUrl = "ws://127.0.0.1:${pageInfo.tcpPort}${pageInfo.webSocketPath}"
            Log.i(TAG, "Connecting to $wsUrl")

            val request = Request.Builder().url(wsUrl).build()
            val latch = java.util.concurrent.CountDownLatch(1)
            var success = false

            webSocket = sharedClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket connected to ${pageInfo.cdpPageId}")
                    connected.set(true)
                    success = true
                    latch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.message}")
                    connected.set(false)
                    latch.countDown()
                    failAllPending(t.message ?: "connection failed")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed: $reason")
                    connected.set(false)
                    failAllPending("connection closed")
                }
            })

            latch.await(10, TimeUnit.SECONDS)
            if (success) {
                scope.launch { initPage() }
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}", e)
            disconnect()
            return false
        }
    }

    fun disconnect() {
        connected.set(false)
        webSocket?.close(1000, "disconnect")
        webSocket = null
        failAllPending("disconnected")
        scope.cancel()
    }

    private suspend fun initPage() {
        try {
            sendCommand("Page.enable")
            sendCommand("Runtime.enable")
            val metrics = sendCommand("Page.getLayoutMetrics")
            val visualViewport = metrics.getAsJsonObject("result")?.getAsJsonObject("visualViewport")
            _viewportWidth = visualViewport?.get("clientWidth")?.asInt ?: 1920
            _viewportHeight = visualViewport?.get("clientHeight")?.asInt ?: 1080
        } catch (e: Exception) {
            Log.w(TAG, "initPage partial failure: ${e.message}")
        }
    }

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val id = json.get("id")?.asInt
            if (id != null) {
                pendingRequests.remove(id)?.complete(json)
            } else {
                val method = json.get("method")?.asString
                val params = json.getAsJsonObject("params")
                handleEvent(method, params)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse CDP message: ${e.message}")
        }
    }

    private fun handleEvent(method: String?, params: JsonObject?) {
        when (method) {
            "Runtime.executionContextCreated" -> {
                val ctx = params?.getAsJsonObject("context") ?: return
                val id = ctx.get("id")?.asInt ?: return
                val origin = ctx.get("origin")?.asString ?: ""
                if (origin.isNotEmpty() && origin != "://") {
                    val key = origin.trimEnd('/')
                    frameContexts[key] = id
                    Log.d(TAG, "Frame context registered: origin=$key id=$id")
                }
            }
            "Runtime.executionContextDestroyed" -> {
                val ctxId = params?.get("executionContextId")?.asInt
                if (ctxId != null) {
                    frameContexts.entries.removeIf { it.value == ctxId }
                }
            }
            "Page.frameNavigated" -> {
                val frame = params?.getAsJsonObject("frame")
                if (frame?.get("parentId") == null) {
                    _currentUrl = frame?.get("url")?.asString ?: _currentUrl
                    _pageTitle = frame?.get("name")?.asString ?: _pageTitle
                }
            }
            "Page.loadEventFired" -> {
                scope.launch {
                    try {
                        val result = evaluateJs("document.title", 2000)
                        val title = result.trim('"')
                        if (title.isNotEmpty() && title != "null") _pageTitle = title
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun sendCommand(
        method: String,
        params: JsonObject = JsonObject(),
        timeoutMs: Long = 10_000
    ): JsonObject {
        if (!connected.get()) throw IllegalStateException("Not connected")

        val id = cmdId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        val msg = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }

        val sent = webSocket?.send(gson.toJson(msg)) ?: false
        if (!sent) {
            pendingRequests.remove(id)
            throw IOException("Failed to send WebSocket message")
        }

        return withTimeout(timeoutMs) { deferred.await() }
    }

    private fun failAllPending(reason: String) {
        val entries = pendingRequests.entries.toList()
        pendingRequests.clear()
        for ((_, deferred) in entries) {
            deferred.completeExceptionally(IOException(reason))
        }
    }

    // ── WebViewBridge 实现 ──────────────────────────────────────────

    override suspend fun navigateAsync(url: String, timeoutMs: Long): NavigationResult {
        val loaderId = UUID.randomUUID().toString()
        return try {
            val params = JsonObject().apply { addProperty("url", url) }
            val resp = sendCommand("Page.navigate", params, timeoutMs)
            val result = resp.getAsJsonObject("result")
            val errorText = result?.get("errorText")?.asString
            if (errorText != null) {
                NavigationResult(false, loaderId, errorText)
            } else {
                _currentUrl = url
                delay(1000)
                NavigationResult(true, loaderId, null)
            }
        } catch (e: Exception) {
            NavigationResult(false, loaderId, e.message)
        }
    }

    override fun navigate(url: String) {
        scope.launch {
            try { navigateAsync(url) } catch (_: Exception) {}
        }
    }

    override suspend fun evaluateJs(expression: String, timeoutMs: Long): String {
        val params = JsonObject().apply {
            addProperty("expression", expression)
            addProperty("returnByValue", true)
        }
        val resp = sendCommand("Runtime.evaluate", params, timeoutMs)
        val result = resp.getAsJsonObject("result")?.getAsJsonObject("result") ?: return "null"

        val type = result.get("type")?.asString
        val value = result.get("value")

        return when {
            value == null || value.isJsonNull -> "null"
            type == "string" -> gson.toJson(value.asString)
            type == "boolean" -> value.asBoolean.toString()
            type == "number" -> value.asNumber.toString()
            type == "object" || type == "undefined" -> {
                if (value.isJsonObject || value.isJsonArray) gson.toJson(value)
                else value.toString()
            }
            else -> value.toString()
        }
    }

    /**
     * 在指定 iframe 的执行上下文中运行 JS。
     * frameUrl: iframe 的 origin（如 "https://pc-settlement-lite-pro.pf.jd.com"）
     * 会等待该 frame context 出现，最多 waitMs 毫秒。
     */
    suspend fun evaluateJsInFrame(frameUrl: String, expression: String, waitMs: Long = 8000): String {
        val origin = frameUrl.trimEnd('/')
        val deadline = System.currentTimeMillis() + waitMs
        var contextId: Int? = null
        while (System.currentTimeMillis() < deadline) {
            contextId = frameContexts[origin]
                ?: frameContexts.entries.firstOrNull { (k, _) ->
                    origin.startsWith(k) || k.startsWith(origin)
                }?.value
            if (contextId != null) break
            delay(200)
        }
        if (contextId == null) {
            throw IllegalStateException(
                "Frame context not found for '$frameUrl' after ${waitMs}ms. " +
                "Available origins: ${frameContexts.keys.joinToString()}"
            )
        }
        val params = JsonObject().apply {
            addProperty("expression", expression)
            addProperty("contextId", contextId)
            addProperty("returnByValue", true)
        }
        val resp = sendCommand("Runtime.evaluate", params)
        val result = resp.getAsJsonObject("result")?.getAsJsonObject("result") ?: return "null"
        val type  = result.get("type")?.asString
        val value = result.get("value")
        return when {
            value == null || value.isJsonNull -> "null"
            type == "string"  -> gson.toJson(value.asString)
            type == "boolean" -> value.asBoolean.toString()
            type == "number"  -> value.asNumber.toString()
            value.isJsonObject || value.isJsonArray -> gson.toJson(value)
            else -> value.toString()
        }
    }

    /** 返回当前已知的所有 frame origin -> contextId 映射（调试用）。 */
    fun getFrameContexts(): Map<String, Int> = frameContexts.toMap()

    override suspend fun captureScreenshot(): ByteArray? {
        return try {
            val params = JsonObject().apply { addProperty("format", "png") }
            val resp = sendCommand("Page.captureScreenshot", params, 15_000)
            val data = resp.getAsJsonObject("result")?.get("data")?.asString ?: return null
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "captureScreenshot failed: ${e.message}")
            null
        }
    }

    override fun clearContent() {
        scope.launch {
            try {
                val params = JsonObject().apply { addProperty("url", "about:blank") }
                sendCommand("Page.navigate", params)
            } catch (_: Exception) {}
        }
    }

    // CdpBrowserBridge controls a remote browser tab, not a local Activity —
    // there is no Android back key to dispatch here.
    override fun pressSystemBack() = Unit

    override fun appendLog(msg: String) {
        Log.d(TAG, "[${pageInfo.cdpPageId}] $msg")
    }
}
