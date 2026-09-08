package com.clawagent.mcp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程内直连本 WebView 真实 CDP socket 的通道。
 *
 * 架构：LocalSocket @webview_devtools_remote_<pid> → LocalSocketWrapper → OkHttp WebSocket
 * 全程不经过 TCP/IP 栈，不受 iptables 限制。
 */
class InternalCdpChannel(private val pid: Int) {

    companion object {
        private const val TAG = "InternalCdpChannel"
        private const val CONNECT_TIMEOUT_S = 10L
        private const val CMD_TIMEOUT_MS = 8_000L
    }

    val isReady: Boolean get() = ready.get()

    private val ready = AtomicBoolean(false)
    private val cmdId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, kotlinx.coroutines.CompletableDeferred<JsonObject>>()

    /** origin (trimmed trailing /) → CDP executionContextId，由 Runtime.executionContextCreated 事件维护 */
    val frameContexts = ConcurrentHashMap<String, Int>()

    private val socketName = "webview_devtools_remote_$pid"
    private var ws: WebSocket? = null

    // ── Init / teardown ───────────────────────────────────────────────────

    /**
     * 建立 WebSocket 连接到 browser target。必须在 IO 线程调用。
     */
    fun init(): Boolean {
        if (!testLocalSocket()) {
            Log.w(TAG, "LocalSocket @$socketName not reachable")
            return false
        }

        val wsPath = fetchWsPathDirect() ?: run {
            Log.w(TAG, "No page target found in /json/list")
            return false
        }

        if (!connectWs(wsPath)) {
            Log.w(TAG, "WebSocket connect failed")
            return false
        }

        ready.set(true)
        Log.i(TAG, "CDP channel ready, target $wsPath")
        return true
    }

    fun close() {
        ready.set(false)
        ws?.close(1000, "close")
        ws = null
        failAllPending("channel closed")
    }

    // ── CDP commands ──────────────────────────────────────────────────────

    suspend fun getTargets(): List<CdpTarget> {
        val resp = sendCmd("Target.getTargets", JsonObject())
        val arr = resp.getAsJsonObject("result")
            ?.getAsJsonArray("targetInfos") ?: return emptyList()
        return arr.map { it.asJsonObject }.map { t ->
            CdpTarget(
                targetId = t.get("targetId")?.asString ?: "",
                type     = t.get("type")?.asString ?: "",
                url      = t.get("url")?.asString ?: "",
                title    = t.get("title")?.asString ?: ""
            )
        }
    }

    /**
     * 获取页面 frame 树，返回所有 frame（含跨域 iframe）的 frameId 和 url。
     * Android WebView CDP 不把 iframe 暴露为独立 target，但可以通过 frameId 访问执行上下文。
     */
    suspend fun getIframeFrames(): List<CdpFrame> {
        val resp = sendCmd("Page.getFrameTree", JsonObject())
        val root = resp.getAsJsonObject("result")?.getAsJsonObject("frameTree") ?: return emptyList()
        val result = mutableListOf<CdpFrame>()
        collectFrames(root, result, isRoot = true)
        return result
    }

    private fun collectFrames(node: com.google.gson.JsonObject, out: MutableList<CdpFrame>, isRoot: Boolean) {
        val frame = node.getAsJsonObject("frame")
        if (frame != null) {
            val frameId = frame.get("id")?.asString ?: ""
            val url = frame.get("url")?.asString ?: ""
            if (!isRoot && frameId.isNotEmpty()) {
                out.add(CdpFrame(frameId, url))
            }
        }
        val children = node.getAsJsonArray("childFrames")
        if (children != null) {
            for (child in children) {
                collectFrames(child.asJsonObject, out, isRoot = false)
            }
        }
    }

    /**
     * 在指定 frameId 的执行上下文中执行 JS。
     * 先用 Runtime.executionContexts 找到对应 frameId 的 contextId，再执行。
     */
    suspend fun evaluateInFrame(frameId: String, expression: String, timeoutMs: Long = CMD_TIMEOUT_MS): String {
        val ctxResp = sendCmd("Runtime.getExecutionContexts", JsonObject())
        Log.i(TAG, "evaluateInFrame frameId=$frameId rawResp=${ctxResp.toString().take(300)}")
        val ctxArr = ctxResp.getAsJsonObject("result")?.getAsJsonArray("contexts")
        if (ctxArr == null) {
            return evaluateInFrameViaIsolatedWorld(frameId, expression, timeoutMs)
        }
        val contextId = ctxArr.map { it.asJsonObject }.firstOrNull { ctx ->
            val auxData = ctx.getAsJsonObject("auxData")
            auxData?.get("frameId")?.asString == frameId
        }?.get("id")?.asInt ?: return evaluateInFrameViaIsolatedWorld(frameId, expression, timeoutMs)

        val params = JsonObject().apply {
            addProperty("expression", expression)
            addProperty("returnByValue", true)
            addProperty("contextId", contextId)
        }
        val resp = sendCmd("Runtime.evaluate", params, timeoutMs = timeoutMs)
        val result = resp.getAsJsonObject("result")?.getAsJsonObject("result") ?: return "null"
        val ex = resp.getAsJsonObject("result")?.getAsJsonObject("exceptionDetails")
        if (ex != null) return "null"
        val type  = result.get("type")?.asString
        val value = result.get("value")
        return when {
            value == null || value.isJsonNull -> "null"
            type == "string" -> "\"${value.asString.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            type == "boolean" -> value.asBoolean.toString()
            type == "number" -> value.asNumber.toString()
            else -> if (value.isJsonObject || value.isJsonArray) value.toString() else value.toString()
        }
    }

    /**
     * Fallback: Page.createIsolatedWorld 为 frameId 创建一个独立 JS world，
     * 返回 executionContextId，再用 Runtime.evaluate + contextId 执行。
     * 注意：isolated world 访问不到页面真实 DOM，只能用来验证连通性。
     * 更好的做法：用 Runtime.enable 订阅 executionContextCreated 事件获取真实 contextId。
     */
    private suspend fun evaluateInFrameViaIsolatedWorld(frameId: String, expression: String, timeoutMs: Long): String {
        return try {
            val params = JsonObject().apply {
                addProperty("frameId", frameId)
                addProperty("worldName", "mcp-eval")
                addProperty("grantUniversalAccess", true)
            }
            val resp = sendCmd("Page.createIsolatedWorld", params, timeoutMs = 5_000L)
            val contextId = resp.getAsJsonObject("result")?.get("executionContextId")?.asInt
            Log.i(TAG, "createIsolatedWorld frameId=$frameId contextId=$contextId")
            if (contextId == null) return "null"

            val evalParams = JsonObject().apply {
                addProperty("expression", expression)
                addProperty("returnByValue", true)
                addProperty("contextId", contextId)
            }
            val evalResp = sendCmd("Runtime.evaluate", evalParams, timeoutMs = timeoutMs)
            val result = evalResp.getAsJsonObject("result")?.getAsJsonObject("result") ?: return "null"
            val ex = evalResp.getAsJsonObject("result")?.getAsJsonObject("exceptionDetails")
            if (ex != null) {
                Log.w(TAG, "JS exception in isolated world: ${ex.toString().take(200)}")
                return "null"
            }
            val type  = result.get("type")?.asString
            val value = result.get("value")
            when {
                value == null || value.isJsonNull -> "null"
                type == "string" -> "\"${value.asString.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                type == "boolean" -> value.asBoolean.toString()
                type == "number" -> value.asNumber.toString()
                else -> if (value.isJsonObject || value.isJsonArray) value.toString() else value.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "evaluateInFrameViaIsolatedWorld failed: ${e.message}")
            "null"
        }
    }

    /**
     * 通过 origin URL 定位 iframe 的 executionContext 并执行 JS。
     * 优先查 frameContexts（由 Runtime.executionContextCreated 事件维护），
     * 找不到时重发 Runtime.enable 刷新，最终 fallback 到 getIframeFrames-based 路径。
     */
    suspend fun evaluateInFrameByOrigin(
        frameUrl: String,
        expression: String,
        timeoutMs: Long = CMD_TIMEOUT_MS
    ): String {
        val origin = frameUrl.trimEnd('/')

        fun lookup(): Int? = frameContexts[origin]
            ?: frameContexts.entries.firstOrNull { (k, _) ->
                origin.startsWith(k) || k.startsWith(origin)
            }?.value

        // Phase 1: fast poll up to 1.5 s (frame context usually arrives within 100-200 ms)
        var contextId: Int? = null
        val deadline = System.currentTimeMillis() + 1_500L
        while (System.currentTimeMillis() < deadline) {
            contextId = lookup()
            if (contextId != null) break
            kotlinx.coroutines.delay(100)
        }

        // Phase 2: re-send Runtime.enable to force re-delivery of all live contexts
        if (contextId == null) {
            Log.d(TAG, "evaluateInFrameByOrigin: no ctx for '$frameUrl' after 1.5s, refreshing")
            ws?.send("""{"id":0,"method":"Runtime.enable","params":{}}""")
            kotlinx.coroutines.delay(500)
            contextId = lookup()
        }

        // Phase 3: fallback to frameId-based path
        if (contextId == null) {
            Log.d(TAG, "evaluateInFrameByOrigin: fallback to frameId for '$frameUrl'")
            val frames = getIframeFrames()
            val match = frames.firstOrNull { f ->
                val u = f.url.trimEnd('/')
                u == origin || u.startsWith(origin) || origin.startsWith(u)
            }
            return if (match != null) evaluateInFrame(match.frameId, expression, timeoutMs)
            else throw IllegalStateException(
                "Frame context not found for '$frameUrl'. Available origins: ${frameContexts.keys}"
            )
        }

        val params = JsonObject().apply {
            addProperty("expression", expression)
            addProperty("contextId", contextId)
            addProperty("returnByValue", true)
        }
        val resp = sendCmd("Runtime.evaluate", params, timeoutMs = timeoutMs)
        val result = resp.getAsJsonObject("result")?.getAsJsonObject("result") ?: return "null"
        val ex = resp.getAsJsonObject("result")?.getAsJsonObject("exceptionDetails")
        if (ex != null) return "null"
        val type  = result.get("type")?.asString
        val value = result.get("value")
        return when {
            value == null || value.isJsonNull -> "null"
            type == "string" -> "\"${value.asString.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            type == "boolean" -> value.asBoolean.toString()
            type == "number"  -> value.asNumber.toString()
            else -> if (value.isJsonObject || value.isJsonArray) value.toString() else value.toString()
        }
    }

    suspend fun attachToTarget(targetId: String): String {
        val params = JsonObject().apply {
            addProperty("targetId", targetId)
            addProperty("flatten", true)
        }
        val resp = sendCmd("Target.attachToTarget", params)
        return resp.getAsJsonObject("result")?.get("sessionId")?.asString
            ?: throw IllegalStateException("attachToTarget returned no sessionId")
    }

    suspend fun evaluate(expression: String, sessionId: String? = null, timeoutMs: Long = CMD_TIMEOUT_MS): String {
        val params = JsonObject().apply {
            addProperty("expression", expression)
            addProperty("returnByValue", true)
        }
        val resp = sendCmd("Runtime.evaluate", params, sessionId, timeoutMs)
        val result = resp.getAsJsonObject("result")?.getAsJsonObject("result") ?: return "null"
        val ex = resp.getAsJsonObject("result")?.getAsJsonObject("exceptionDetails")
        if (ex != null) {
            val msg = ex.getAsJsonObject("exception")?.get("description")?.asString ?: "JS exception"
            throw RuntimeException(msg)
        }
        val type  = result.get("type")?.asString
        val value = result.get("value")
        return when {
            value == null || value.isJsonNull -> "null"
            type == "string" -> value.asString
            type == "boolean" -> value.asBoolean.toString()
            type == "number" -> value.asNumber.toString()
            else -> if (value.isJsonObject || value.isJsonArray) value.toString() else value.asString
        }
    }

    suspend fun evaluateJs(expression: String, sessionId: String? = null, timeoutMs: Long = CMD_TIMEOUT_MS): String {
        val params = JsonObject().apply {
            addProperty("expression", expression)
            addProperty("returnByValue", true)
        }
        val resp = sendCmd("Runtime.evaluate", params, sessionId, timeoutMs)
        val result = resp.getAsJsonObject("result")?.getAsJsonObject("result") ?: return "null"
        val ex = resp.getAsJsonObject("result")?.getAsJsonObject("exceptionDetails")
        if (ex != null) return "null"
        val type  = result.get("type")?.asString
        val value = result.get("value")
        return when {
            value == null || value.isJsonNull -> "null"
            type == "string" -> "\"${value.asString.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            type == "boolean" -> value.asBoolean.toString()
            type == "number" -> value.asNumber.toString()
            else -> if (value.isJsonObject || value.isJsonArray) value.toString() else value.toString()
        }
    }

    suspend fun detachFromTarget(sessionId: String) {
        try {
            val params = JsonObject().apply { addProperty("sessionId", sessionId) }
            sendCmd("Target.detachFromTarget", params, timeoutMs = 3_000L)
        } catch (_: Exception) {}
    }

    /** 通过 CDP Emulation 域覆盖 WebView 的地理位置，效果全局持久直到调用 clearGeolocationOverride。 */
    suspend fun setGeolocationOverride(lat: Double, lon: Double, accuracy: Double) {
        val params = JsonObject().apply {
            addProperty("latitude",  lat)
            addProperty("longitude", lon)
            addProperty("accuracy",  accuracy)
        }
        sendCmd("Emulation.setGeolocationOverride", params)
    }

    /** 清除之前通过 setGeolocationOverride 设置的位置覆盖，恢复使用真实/被拒绝的位置。 */
    suspend fun clearGeolocationOverride() {
        sendCmd("Emulation.clearGeolocationOverride", JsonObject())
    }

    /**
     * 覆盖设备视口尺寸和 devicePixelRatio，直接控制 JS 看到的 innerWidth/innerHeight/DPR。
     * 这是 Chrome DevTools 设备仿真模式的底层命令，可靠地绕过硬件 DPI 限制。
     *
     * @param width  CSS 像素宽度（= 物理宽 / dpr）
     * @param height CSS 像素高度（= 物理高 / dpr）
     * @param deviceScaleFactor DPR，如 2.0 = 4 物理像素对应 1 CSS 像素
     * @param mobile 是否模拟移动设备（影响滚动行为等）
     */
    suspend fun setDeviceMetricsOverride(
        width: Int, height: Int, deviceScaleFactor: Double, mobile: Boolean = false
    ) {
        val params = JsonObject().apply {
            addProperty("width", width)
            addProperty("height", height)
            addProperty("deviceScaleFactor", deviceScaleFactor)
            addProperty("mobile", mobile)
        }
        sendCmd("Emulation.setDeviceMetricsOverride", params)
    }

    /** 清除 setDeviceMetricsOverride，恢复硬件真实尺寸。 */
    suspend fun clearDeviceMetricsOverride() {
        sendCmd("Emulation.clearDeviceMetricsOverride", JsonObject())
    }

    /**
     * 覆盖 WebView 的 User-Agent、platform 和 userAgentData。
     * 同时覆盖 HTTP 请求头和 JS 的 navigator.userAgent / navigator.platform / navigator.userAgentData。
     */
    suspend fun setUserAgentOverride(ua: String, platform: String, mobile: Boolean) {
        val metadata = JsonObject().apply {
            add("brands", com.google.gson.JsonArray().apply {
                add(JsonObject().apply { addProperty("brand", "Google Chrome"); addProperty("version", "126") })
                add(JsonObject().apply { addProperty("brand", "Chromium"); addProperty("version", "126") })
                add(JsonObject().apply { addProperty("brand", "Not-A.Brand"); addProperty("version", "99") })
            })
            addProperty("fullVersion", "126.0.0.0")
            addProperty("platform", if (mobile) "Android" else "macOS")
            addProperty("platformVersion", if (mobile) "11.0" else "14.5")
            addProperty("architecture", if (mobile) "arm" else "arm")
            addProperty("model", if (mobile) "Pixel 7" else "")
            addProperty("mobile", mobile)
        }
        val params = JsonObject().apply {
            addProperty("userAgent", ua)
            addProperty("platform", platform)
            add("userAgentMetadata", metadata)
        }
        sendCmd("Network.setUserAgentOverride", params)
    }

    /** 注入一段 JS，在每次文档创建时执行（持久到 removeScriptToEvaluateOnNewDocument）。返回 scriptId。 */
    suspend fun addScriptToEvaluateOnNewDocument(source: String): String {
        val params = JsonObject().apply { addProperty("source", source) }
        val resp = sendCmd("Page.addScriptToEvaluateOnNewDocument", params)
        return resp.getAsJsonObject("result")?.get("identifier")?.asString ?: ""
    }

    /** 移除之前注入的文档级脚本。 */
    suspend fun removeScriptToEvaluateOnNewDocument(identifier: String) {
        if (identifier.isBlank()) return
        val params = JsonObject().apply { addProperty("identifier", identifier) }
        try { sendCmd("Page.removeScriptToEvaluateOnNewDocument", params) } catch (_: Exception) {}
    }

    /**
     * 覆盖 CSS 媒体特性（pointer / hover 等），让页面媒体查询返回期望的值。
     * features 示例：mapOf("pointer" to "fine", "hover" to "hover")
     */
    suspend fun setEmulatedMediaFeatures(features: Map<String, String>) {
        val arr = com.google.gson.JsonArray()
        features.forEach { (name, value) ->
            arr.add(JsonObject().apply { addProperty("name", name); addProperty("value", value) })
        }
        val params = JsonObject().apply { add("features", arr) }
        sendCmd("Emulation.setEmulatedMedia", params)
    }
    // ── Internal: send/receive ────────────────────────────────────────────

    private suspend fun sendCmd(
        method: String,
        params: JsonObject,
        sessionId: String? = null,
        timeoutMs: Long = CMD_TIMEOUT_MS
    ): JsonObject {
        if (!ready.get()) throw IllegalStateException("CDP channel not ready")
        val id = cmdId.getAndIncrement()
        val deferred = kotlinx.coroutines.CompletableDeferred<JsonObject>()
        pending[id] = deferred

        val msg = JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
            if (sessionId != null) addProperty("sessionId", sessionId)
        }
        val sent = ws?.send(msg.toString()) ?: false
        if (!sent) {
            pending.remove(id)
            markDead()
            throw IllegalStateException("WebSocket send failed")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }
    }

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val id = json.get("id")?.asInt
            if (id != null && id > 0) {
                pending.remove(id)?.complete(json)
            } else if (id == null) {
                handleEvent(json.get("method")?.asString, json.getAsJsonObject("params"))
            }
            // id == 0 → response to our fire-and-forget Runtime.enable; ignore
        } catch (_: Exception) {}
    }

    private fun handleEvent(method: String?, params: JsonObject?) {
        when (method) {
            "Runtime.executionContextCreated" -> {
                val ctx = params?.getAsJsonObject("context") ?: return
                val ctxId = ctx.get("id")?.asInt ?: return
                val origin = ctx.get("origin")?.asString ?: ""
                if (origin.isNotEmpty() && origin != "://" && !origin.startsWith("chrome-extension://")) {
                    frameContexts[origin.trimEnd('/')] = ctxId
                    Log.d(TAG, "frameCtx: ${origin.trimEnd('/')} → $ctxId")
                }
            }
            "Runtime.executionContextDestroyed" -> {
                val ctxId = params?.get("executionContextId")?.asInt
                if (ctxId != null) frameContexts.entries.removeIf { it.value == ctxId }
            }
        }
    }

    private fun failAllPending(reason: String) {
        val entries = pending.entries.toList()
        pending.clear()
        for ((_, d) in entries) d.completeExceptionally(Exception(reason))
    }

    private fun markDead() {
        if (ready.compareAndSet(true, false)) {
            Log.w(TAG, "CDP channel marked dead")
            failAllPending("channel dead")
        }
    }

    // ── Direct HTTP over LocalSocket ──────────────────────────────────────

    /**
     * 从 CDP LocalSocket 读取 HTTP 响应。
     * CDP 使用 Transfer-Encoding: chunked，以 "0\r\n\r\n" 结束，不关连接。
     * 必须逐字节读头部，再按 Content-Length 或 chunked 协议读 body，不能用 readBytes()。
     */
    private fun fetchWsPathDirect(): String? {
        val ls = LocalSocket()
        return try {
            ls.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            ls.outputStream.write("GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            ls.outputStream.flush()

            val ins = ls.inputStream

            // 读头部（直到 \r\n\r\n）
            val headerBuf = StringBuilder()
            while (true) {
                val b = ins.read()
                if (b == -1) break
                headerBuf.append(b.toChar())
                val len = headerBuf.length
                if (len >= 4 &&
                    headerBuf[len-4] == '\r' && headerBuf[len-3] == '\n' &&
                    headerBuf[len-2] == '\r' && headerBuf[len-1] == '\n') break
            }
            val headers = headerBuf.toString()
            Log.d(TAG, "fetchWsPath headers: ${headers.lines().firstOrNull()}")

            // 读 body
            val body: String
            val clMatch = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(headers)
            body = if (clMatch != null) {
                val len = clMatch.groupValues[1].toInt()
                val bodyBytes = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = ins.read(bodyBytes, read, len - read)
                    if (n == -1) break
                    read += n
                }
                String(bodyBytes, 0, read, Charsets.UTF_8)
            } else {
                // chunked: 读直到终止块 "0\r\n\r\n"
                readChunkedBody(ins)
            }

            Log.d(TAG, "fetchWsPath body length: ${body.length}")
            val arr = JsonParser.parseString(body.trim()).asJsonArray
            for (elem in arr) {
                val obj = elem.asJsonObject
                if (obj.get("type")?.asString == "page") {
                    val wsUrl = obj.get("webSocketDebuggerUrl")?.asString ?: continue
                    return if (wsUrl.contains("/devtools/")) {
                        "/devtools/" + wsUrl.substringAfter("/devtools/")
                    } else {
                        "/devtools/page/${obj.get("id")?.asString}"
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "fetchWsPathDirect failed: ${e.message}")
            null
        } finally {
            try { ls.close() } catch (_: Exception) {}
        }
    }

    private fun readChunkedBody(ins: InputStream): String {
        val result = StringBuilder()
        while (true) {
            // 读 chunk-size 行
            val sizeLine = StringBuilder()
            while (true) {
                val b = ins.read()
                if (b == -1 || b.toChar() == '\n') break
                if (b.toChar() != '\r') sizeLine.append(b.toChar())
            }
            val chunkSize = sizeLine.toString().trim().toIntOrNull(16) ?: break
            if (chunkSize == 0) break
            val chunkBytes = ByteArray(chunkSize)
            var read = 0
            while (read < chunkSize) {
                val n = ins.read(chunkBytes, read, chunkSize - read)
                if (n == -1) break
                read += n
            }
            result.append(String(chunkBytes, 0, read, Charsets.UTF_8))
            // 跳过 trailing \r\n
            ins.read(); ins.read()
        }
        return result.toString()
    }

    // ── WebSocket over LocalSocket ────────────────────────────────────────

    private fun connectWs(wsPath: String): Boolean {
        val localClient = OkHttpClient.Builder()
            .socketFactory(LocalSocketFactory(socketName))
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val url = "ws://localhost$wsPath"
        val latch = CountDownLatch(1)
        var ok = false
        ws = localClient.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ok = true
                // Fire-and-forget: subscribe to execution context events for all frames
                webSocket.send("""{"id":0,"method":"Runtime.enable","params":{}}""")
                latch.countDown()
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                latch.countDown()
                markDead()
                failAllPending(t.message ?: "ws failure")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                markDead()
                failAllPending("ws closed: $reason")
            }
        })
        latch.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        return ok
    }

    private fun testLocalSocket(): Boolean {
        return try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            s.close()
            true
        } catch (_: Exception) { false }
    }

    // ── Data classes ──────────────────────────────────────────────────────

    data class CdpTarget(
        val targetId: String,
        val type: String,
        val url: String,
        val title: String
    )

    data class CdpFrame(
        val frameId: String,
        val url: String
    )
}

// ── LocalSocket-backed Socket for OkHttp ─────────────────────────────────────

/**
 * OkHttp SocketFactory，每次 createSocket 返回一个包装 LocalSocket 的 Socket。
 * OkHttp 调用 connect() 时连接到 LocalSocket abstract namespace，不走 TCP/IP，
 * 完全绕开 iptables。
 */
private class LocalSocketFactory(private val socketName: String) : javax.net.SocketFactory() {
    override fun createSocket(): Socket = LocalSocketWrapper(socketName)
    override fun createSocket(host: String, port: Int): Socket = LocalSocketWrapper(socketName)
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = LocalSocketWrapper(socketName)
    override fun createSocket(host: InetAddress, port: Int): Socket = LocalSocketWrapper(socketName)
    override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket = LocalSocketWrapper(socketName)
}

private class LocalSocketWrapper(private val socketName: String) : Socket() {
    private val ls = LocalSocket()

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        ls.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
    }

    override fun getInputStream(): InputStream = ls.inputStream
    override fun getOutputStream(): OutputStream = ls.outputStream

    override fun close() {
        try { ls.close() } catch (_: Exception) {}
    }

    override fun isClosed(): Boolean = false
    override fun isConnected(): Boolean = true
    override fun isInputShutdown(): Boolean = false
    override fun isOutputShutdown(): Boolean = false

    // OkHttp 会调用这些方法做 TCP 调优，LocalSocket 不需要，忽略即可
    override fun setSoTimeout(timeout: Int) {}
    override fun setTcpNoDelay(on: Boolean) {}
    override fun setKeepAlive(on: Boolean) {}
    override fun setSendBufferSize(size: Int) {}
    override fun setReceiveBufferSize(size: Int) {}
}
