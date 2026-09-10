package com.clawagent.cdp

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CDP HTTP + WebSocket Server（基于 NanoWSD）。
 *
 * 职责：
 *   1. HTTP 端点（/json/version、/json/list 等）
 *   2. WebSocket 升级 → 交给 [CdpSession] 处理
 *   3. 向所有已连接的 socket 广播 CDP 事件
 *
 * 不持有任何 WebView 引用；通过 [WebViewBridge] 操控 WebView。
 */
class CdpServer(
    private val config: CdpConfig,
    private val bridge: WebViewBridge,
) : NanoWSD(config.port) {

    private val TAG = "WebViewCDP"
    private val gson = Gson()

    /** socketId → 活跃连接（线程安全） */
    private val activeSockets = ConcurrentHashMap<String, CdpSession>()

    // ── 事件广播 ────────────────────────────────────────────────────

    /**
     * 通知所有客户端页面导航完成。
     * 由 Activity 的 `WebViewClient.onPageFinished` 调用。
     */
    fun notifyPageLoadComplete(url: String, title: String) {
        val loaderId = UUID.randomUUID().toString()
        val frameId = config.pageTargetId
        val effectiveUrl = url.ifEmpty { "about:blank" }
        val ts = System.currentTimeMillis() / 1000.0
        val sid = config.pageSessionId

        val frameObj = JsonObject().apply {
            addProperty("id", frameId)
            addProperty("loaderId", loaderId)
            addProperty("url", effectiveUrl)
            addProperty("domainAndRegistry", "")
            addProperty("securityOrigin", "")
            addProperty("mimeType", "text/html")
        }

        // ① Browser socket：带 sessionId，供 Playwright flattened 协议路由
        sendEventAll("Page.frameNavigated", JsonObject().apply {
            add("frame", frameObj); addProperty("type", "Navigation")
        }, sid)
        sendEventAll("Page.loadEventFired", JsonObject().apply {
            addProperty("timestamp", ts)
        }, sid)
        sendEventAll("Page.frameStoppedLoading", JsonObject().apply {
            addProperty("frameId", frameId)
        }, sid)
        listOf("commit", "DOMContentLoaded", "load", "networkAlmostIdle", "networkIdle").forEach { name ->
            sendEventAll("Page.lifecycleEvent", JsonObject().apply {
                addProperty("frameId", frameId)
                addProperty("loaderId", loaderId)
                addProperty("name", name)
                addProperty("timestamp", ts)
            }, sid)
        }
        sendEventAll("DOM.documentUpdated", JsonObject(), sid)

        // Target.targetInfoChanged 是 browser-level 事件，不带 sessionId
        sendEventAll("Target.targetInfoChanged", JsonObject().apply {
            add("targetInfo", JsonObject().apply {
                addProperty("targetId", config.pageTargetId)
                addProperty("type", "page")
                addProperty("title", title.ifEmpty { "WebView Page" })
                addProperty("url", effectiveUrl)
                addProperty("attached", true)
                addProperty("canAccessOpener", false)
                addProperty("browserContextId", config.browserContextId)
            })
        })

        // ② Page socket：直连模式（不带 sessionId）
        val pageSocket = activeSockets["page-${config.pageTargetId}"]
        if (pageSocket != null) {
            listOf(
                "Page.frameNavigated" to JsonObject().apply {
                    add("frame", frameObj); addProperty("type", "Navigation")
                },
                "Page.loadEventFired" to JsonObject().apply { addProperty("timestamp", ts) },
                "Page.frameStoppedLoading" to JsonObject().apply { addProperty("frameId", frameId) }
            ).forEach { (method, params) ->
                try {
                    pageSocket.send(gson.toJson(JsonObject().apply {
                        addProperty("method", method); add("params", params)
                    }))
                } catch (_: Exception) { /* best effort */ }
            }
        }
    }

    /**
     * 向 browser socket 广播事件（带可选 sessionId）。
     *
     * 只发给 browser socket：Playwright 通过 sessionId 路由机制分发事件；
     * page socket 收到带 sessionId 的消息会断言失败。
     */
    fun sendEventAll(method: String, params: JsonObject, sessionId: String? = null) {
        val event = gson.toJson(JsonObject().apply {
            addProperty("method", method)
            add("params", params)
            if (sessionId != null) addProperty("sessionId", sessionId)
        })
        val browserSocket = activeSockets["browser-${config.browserTargetId}"]
        if (browserSocket != null) {
            try { browserSocket.send(event) } catch (_: Exception) { /* best effort */ }
        }
    }

    // ── 认证 ────────────────────────────────────────────────────────

    /**
     * 验证 HTTP/WebSocket 请求认证信息。
     * 支持：Bearer token、HTTP Basic Auth（URL embedding 风格）、?token= 查询参数。
     */
    fun checkAuth(headers: Map<String, String>, queryParams: Map<String, String>): Boolean {
        if (config.authToken.isEmpty()) return true

        val authHeader = headers["authorization"] ?: headers["Authorization"] ?: ""
        if (authHeader.startsWith("Bearer ") && authHeader.substring(7) == config.authToken) return true

        if (authHeader.startsWith("Basic ")) {
            try {
                val decoded = String(Base64.decode(authHeader.substring(6), Base64.DEFAULT))
                if (decoded.substringAfter(":", "") == config.authToken) return true
            } catch (_: Exception) { /* fall through */ }
        }

        val queryToken = queryParams["token"] ?: ""
        if (queryToken.isNotEmpty() && queryToken == config.authToken) return true

        return false
    }

    // ── HTTP 端点 ───────────────────────────────────────────────────

    override fun serveHttp(session: IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        val method = session.method
        val headers = session.headers

        val isPublicEndpoint = (uri == "/json/version" && method == Method.GET) ||
            (uri == "/" && method == Method.HEAD)

        if (!isPublicEndpoint && !checkAuth(headers, session.parms ?: emptyMap())) {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json",
                """{"error":"Unauthorized","message":"Invalid or missing auth token"}"""
            )
        }

        Log.i(TAG, "HTTP $method $uri")
        bridge.appendLog("CDP HTTP $method $uri")

        return try {
            when {
                uri == "/json/version" && method == Method.GET -> jsonResponse(
                    NanoHTTPD.Response.Status.OK,
                    JsonObject().apply {
                        addProperty("Browser", CdpConfig.BROWSER_NAME)
                        addProperty("Protocol-Version", CdpConfig.PROTOCOL_VERSION)
                        addProperty("User-Agent", CdpConfig.USER_AGENT)
                        addProperty("V8-Version", CdpConfig.V8_VERSION)
                        addProperty("WebKit-Version", CdpConfig.WEBKIT_VERSION)
                        addProperty("webSocketDebuggerUrl",
                            "ws://127.0.0.1:${config.port}/devtools/browser/${config.browserTargetId}")
                    }
                )

                (uri == "/json/list" || uri == "/json") && method == Method.GET ->
                    jsonResponse(NanoHTTPD.Response.Status.OK, buildTargetList())

                (uri == "/json/new" || uri.startsWith("/json/new?")) && method == Method.GET -> {
                    val url = session.parms?.get("url") ?: "about:blank"
                    if (url != "about:blank" && url.isNotBlank()) bridge.navigate(url)
                    jsonResponse(NanoHTTPD.Response.Status.OK, JsonObject().apply {
                        addProperty("id", config.pageTargetId)
                        addProperty("type", "page")
                        addProperty("title", bridge.pageTitle.ifEmpty { "WebView Page" })
                        addProperty("url", url)
                        addProperty("webSocketDebuggerUrl",
                            "ws://127.0.0.1:${config.port}/devtools/page/${config.pageTargetId}")
                    })
                }

                uri.startsWith("/json/activate/") && method == Method.GET ->
                    jsonResponse(NanoHTTPD.Response.Status.OK, JsonObject().apply { addProperty("ok", true) })

                uri.startsWith("/json/close/") && method == Method.GET ->
                    jsonResponse(NanoHTTPD.Response.Status.OK, JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("message", "Cannot close sole page in single-page mode")
                    })

                uri == "/" && method == Method.HEAD ->
                    newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "")

                uri == "/" && method == Method.GET -> jsonResponse(
                    NanoHTTPD.Response.Status.OK,
                    JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("driver", "cdp")
                        addProperty("browserTargetId", config.browserTargetId)
                        addProperty("pageTargetId", config.pageTargetId)
                        addProperty("currentUrl", bridge.currentUrl)
                        addProperty("pageTitle", bridge.pageTitle)
                        addProperty("activeConnections", activeSockets.size)
                        addProperty("webSocketDebuggerUrl",
                            "ws://127.0.0.1:${config.port}/devtools/browser/${config.browserTargetId}")
                    }
                )

                else -> newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, "application/json",
                    """{"error":"Not Found","path":"$uri"}"""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP error: $uri", e)
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json",
                """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
            )
        }
    }

    // ── WebSocket 升级 ──────────────────────────────────────────────

    override fun openWebSocket(handshake: IHTTPSession): NanoWSD.WebSocket? {
        val headers = handshake.headers
        val parms = handshake.parms ?: emptyMap()

        if (!checkAuth(headers, parms)) {
            Log.w(TAG, "WebSocket auth failed: ${handshake.uri}")
            return null
        }

        val path = handshake.uri
        val isPage = path.contains("/devtools/page/")
        val socketId = if (isPage) "page-${config.pageTargetId}" else "browser-${config.browserTargetId}"

        val old = activeSockets.remove(socketId)
        if (old != null) Log.i(TAG, "WebSocket replacing old $socketId connection")

        Log.i(TAG, "WebSocket connected: $path (id=$socketId)")
        bridge.appendLog("CDP WS 连接: $socketId")

        val session = CdpSession(
            handshake = handshake,
            config = config,
            bridge = bridge,
            server = this,
            isPageSession = isPage,
            onClose = { activeSockets.remove(socketId) },
        )
        activeSockets[socketId] = session
        return session
    }

    // ── 内部构建工具 ────────────────────────────────────────────────

    private fun buildTargetList(): JsonArray = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("id", config.pageTargetId)
            addProperty("type", "page")
            addProperty("title", bridge.pageTitle.ifEmpty { "WebView Page" })
            addProperty("url", bridge.currentUrl.ifEmpty { "about:blank" })
            addProperty("webSocketDebuggerUrl",
                "ws://127.0.0.1:${config.port}/devtools/page/${config.pageTargetId}")
            addProperty("devtoolsFrontendUrl",
                "https://chrome-devtools-frontend.appspot.com/serve_file/@@HostBinding" +
                    "(ws://127.0.0.1:${config.port})/inspector.html?" +
                    "ws=127.0.0.1:${config.port}/devtools/page/${config.pageTargetId}")
            addProperty("browserContextId", config.browserContextId)
        })
    }

    private fun jsonResponse(status: NanoHTTPD.Response.Status, obj: Any): NanoHTTPD.Response =
        newFixedLengthResponse(status, "application/json", gson.toJson(obj))
}
