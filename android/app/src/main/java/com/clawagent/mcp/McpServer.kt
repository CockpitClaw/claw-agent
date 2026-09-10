package com.clawagent.mcp

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.clawagent.cdp.WebViewBridge
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP Server - 支持 SSE 和 Streamable HTTP 双传输模式
 *
 * === Streamable HTTP（推荐，ClawAgent 默认） ===
 * 客户端 POST /mcp 发送 JSON-RPC 请求，服务端直接返回 JSON-RPC 响应。
 * 请求/响应一对一，无需 SSE 长连接。
 *
 * ClawAgent 配置：
 * {
 *   "mcp": {
 *     "servers": {
 *       "android-webview": {
 *         "transport": "streamable-http",
 *         "url": "http://192.168.x.x:19003/mcp"
 *       }
 *     }
 *   }
 * }
 *
 * === SSE 传输（兼容模式） ===
 * 1. 客户端 GET /sse → 建立 SSE 长连接
 * 2. 服务端发送 event: endpoint，告知 POST /messages
 * 3. 客户端 POST /messages 发送 JSON-RPC 请求
 * 4. 服务端通过 SSE 推送响应
 */
class McpServer(
    private val config: McpConfig,
    private val bridge: WebViewBridge,
    private val apkPath: String = ""
) : NanoHTTPD(config.port) {

    companion object {
        private const val TAG = "McpServer"
    }

    private val gson = Gson()
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // SSE 连接管理（兼容模式）
    private val sseClients = mutableMapOf<String, SseClient>()
    private var clientIdCounter = 0

    // WebView/CDP 工具（操控 WebView 内 Web 内容）
    private val tools = McpTools(bridge, apkPath)
    // 原生 Accessibility 工具（操控任意 Android 原生 App UI）
    private val nativeTools = NativeAccessibilityMcpTools()

    private data class SseClient(
        val id: String,
        val queue: LinkedBlockingQueue<String>,
        val createdAt: Long = System.currentTimeMillis()
    )

    // ── 生命周期 ────────────────────────────────────────────────────

    override fun start() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "MCP Server already running")
            return
        }
        try {
            super.start()
            Log.i(TAG, "MCP Server started on port ${config.port}")
            bridge.appendLog("MCP Server 启动: :${config.port}")
            // 提前初始化 CDP 通道，让 Emulation.setDeviceMetricsOverride 在第一次页面导航前就生效，
            // 否则 ICB 高度 fix 只在首个 MCP 工具调用后才触发，用户手动浏览的页面修复不到。
            scope.launch { tools.initCdp() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MCP Server: ${e.message}", e)
            bridge.appendLog("MCP Server 启动失败: ${e.message}")
            running.set(false)
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping MCP Server...")
        tools.cleanup()
        nativeTools.cleanup()
        sseClients.values.forEach { it.queue.offer("") }
        sseClients.clear()
        super.stop()
        scope.cancel()
        Log.i(TAG, "MCP Server stopped")
        bridge.appendLog("MCP Server 已停止")
    }

    fun getActiveSessionCount(): Int = sseClients.size

    // ── HTTP 路由 ────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val headers = session.headers

        Log.d(TAG, "HTTP $method $uri")

        // 认证
        if (needsAuth(uri) && !checkAuth(headers)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                """{"error":"Unauthorized"}"""
            )
        }

        return try {
            when {
                // === Streamable HTTP（主模式） ===
                uri == "/mcp" && method == Method.POST -> handleStreamableHttp(session)
                uri == "/mcp" && method == Method.GET -> handleSse(session) // SSE fallback
                uri == "/mcp" && method == Method.DELETE -> newFixedLengthResponse(
                    Response.Status.OK, "application/json", """{"ok":true}"""
                )

                // === SSE 兼容模式 ===
                uri == "/sse" && method == Method.GET -> handleSse(session)
                uri == "/messages" && method == Method.POST -> handleMessages(session)

                // 健康检查
                uri == "/" && method == Method.GET -> jsonResponse(
                    Response.Status.OK,
                    JsonObject().apply {
                        addProperty("name", McpConfig.SERVER_NAME)
                        addProperty("version", McpConfig.SERVER_VERSION)
                        addProperty("protocol", "streamable-http")
                        addProperty("protocolVersion", McpConfig.PROTOCOL_VERSION)
                        addProperty("capabilities", "tools")
                        addProperty("sseClients", sseClients.size)
                    }
                )

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "application/json",
                    """{"error":"Not Found","path":"$uri"}"""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP error: $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"error":"${e.message?.replace("\"", "\\\"")}"}"""
            )
        }
    }

    private fun needsAuth(uri: String): Boolean {
        return config.authToken.isNotEmpty() &&
            (uri == "/mcp" || uri == "/sse" || uri == "/messages")
    }

    // ── Streamable HTTP（主模式） ────────────────────────────────────

    /**
     * 处理 streamable-http POST 请求。
     * 直接解析 JSON-RPC，同步处理并返回响应。
     */
    private fun handleStreamableHttp(session: IHTTPSession): Response {
        val bodyStr = readBody(session)
        if (bodyStr.isEmpty()) {
            return errorResponse(-32700, "Parse error: empty body")
        }

        Log.d(TAG, "Streamable HTTP request: ${bodyStr.take(200)}")

        val request = try {
            JsonParser.parseString(bodyStr).asJsonObject
        } catch (e: Exception) {
            return errorResponse(-32700, "Parse error: ${e.message}")
        }

        // 处理请求
        val response = runBlocking { processRequest(request) }
        return jsonResponse(Response.Status.OK, response)
    }

    // ── SSE 兼容模式 ─────────────────────────────────────────────────

    private fun handleSse(session: IHTTPSession): Response {
        val clientId = "client-${++clientIdCounter}"
        val queue = LinkedBlockingQueue<String>()
        sseClients[clientId] = SseClient(clientId, queue)

        Log.i(TAG, "SSE client connected: $clientId")
        bridge.appendLog("MCP SSE 连接: $clientId")

        val pipedIn = java.io.PipedInputStream(8192)
        val pipedOut = java.io.PipedOutputStream(pipedIn)

        // 启动 SSE 写入线程
        Thread({
            try {
                val writer = pipedOut.bufferedWriter()

                // 发送 endpoint 事件
                writer.write("event: endpoint\n")
                writer.write("data: /messages\n\n")
                writer.write(": connected\n\n")
                writer.flush()

                Log.d(TAG, "SSE endpoint sent to $clientId")

                // 持续推送
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val msg = queue.poll(30, TimeUnit.SECONDS)
                    if (msg == null) {
                        writer.write(": heartbeat\n\n")
                        writer.flush()
                        continue
                    }
                    if (msg.isEmpty()) break
                    writer.write("event: message\n")
                    writer.write("data: $msg\n\n")
                    writer.flush()
                }
                writer.close()
            } catch (e: Exception) {
                Log.d(TAG, "SSE write error for $clientId: ${e.message}")
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
                sseClients.remove(clientId)
                Log.i(TAG, "SSE client disconnected: $clientId")
                bridge.appendLog("MCP SSE 断开: $clientId")
            }
        }, "mcp-sse-$clientId").apply {
            isDaemon = true
            start()
        }

        return newChunkedResponse(
            Response.Status.OK, "text/event-stream", pipedIn
        ).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
            addHeader("X-Accel-Buffering", "no")
        }
    }

    private fun handleMessages(session: IHTTPSession): Response {
        val bodyStr = readBody(session)
        if (bodyStr.isEmpty()) {
            return errorResponse(-32700, "Parse error: empty body")
        }

        scope.launch {
            try {
                val request = JsonParser.parseString(bodyStr).asJsonObject
                val response = processRequest(request)
                pushToSseClients(gson.toJson(response))
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SSE message: ${e.message}", e)
                val err = createErrorResponse(null, -32700, "Parse error: ${e.message}")
                pushToSseClients(gson.toJson(err))
            }
        }

        return newFixedLengthResponse(
            Response.Status.ACCEPTED, "application/json",
            """{"accepted":true}"""
        )
    }

    // ── JSON-RPC 处理 ────────────────────────────────────────────────

    private suspend fun processRequest(request: JsonObject): JsonObject {
        val id = request.get("id")
        val method = request.get("method")?.asString
        // Gson 的 JsonObject.getAsJsonObject(key) 在成员是 JsonPrimitive 时会抛 IllegalStateException
        // 而非返回 null，Elvis 兜不住 —— 用安全转型，畸形请求不再冒 -32000。
        val params = (request.get("params") as? JsonObject) ?: JsonObject()

        Log.d(TAG, "Processing: method=$method, id=$id")

        return when (method) {
            "initialize" -> createSuccessResponse(id, JsonObject().apply {
                addProperty("protocolVersion", McpConfig.PROTOCOL_VERSION)
                add("capabilities", JsonObject().apply {
                    add("tools", JsonObject())
                })
                add("serverInfo", JsonObject().apply {
                    addProperty("name", McpConfig.SERVER_NAME)
                    addProperty("version", McpConfig.SERVER_VERSION)
                })
            })

            "notifications/initialized" -> {
                Log.d(TAG, "Client initialized")
                createSuccessResponse(id, JsonObject())
            }

            "ping" -> createSuccessResponse(id, JsonObject())

            "tools/list" -> createSuccessResponse(id, JsonObject().apply {
                // 合并 WebView 工具 + 原生 Accessibility 工具
                val allTools = JsonArray()
                tools.getToolDefinitions().forEach { allTools.add(it) }
                nativeTools.getToolDefinitions().forEach { allTools.add(it) }
                add("tools", allTools)
            })

            "tools/call" -> {
                val name = params.get("name")?.asString
                val arguments = (params.get("arguments") as? JsonObject) ?: JsonObject()
                if (name == null) {
                    createErrorResponse(id, -32602, "Missing tool name")
                } else {
                    try {
                        // "native_" 前缀 → 原生 Accessibility 工具；其余 → WebView/CDP 工具
                        val result = if (name.startsWith("native_")) {
                            nativeTools.callTool(name, arguments)
                        } else {
                            tools.callTool(name, arguments)
                        }
                        // MCP 规范：tools/call 返回值必须包装为 content 数组
                        createSuccessResponse(id, JsonObject().apply {
                            add("content", JsonArray().apply {
                                if (result.get("type")?.asString == "image") {
                                    add(JsonObject().apply {
                                        addProperty("type", "image")
                                        addProperty("data", result.get("data").asString)
                                        addProperty("mimeType", result.get("mimeType").asString)
                                    })
                                } else {
                                    add(JsonObject().apply {
                                        addProperty("type", "text")
                                        addProperty("text", gson.toJson(result))
                                    })
                                }
                            })
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool call error: ${e.message}", e)
                        createErrorResponse(id, -32000, "Tool error: ${e.message}")
                    }
                }
            }

            "shutdown" -> createSuccessResponse(id, JsonObject())

            else -> {
                Log.w(TAG, "Unknown method: $method")
                createErrorResponse(id, -32601, "Method not found: $method")
            }
        }
    }

    private fun pushToSseClients(message: String): Boolean {
        if (sseClients.isEmpty()) return false
        var pushed = false
        for (client in sseClients.values) {
            if (client.queue.offer(message)) pushed = true
        }
        return pushed
    }

    // ── 工具方法 ────────────────────────────────────────────────────

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull()
            ?: session.headers["Content-Length"]?.toIntOrNull()
            ?: return ""
        val buf = ByteArray(contentLength)
        val inputStream = session.inputStream
        var totalRead = 0
        while (totalRead < contentLength) {
            val n = inputStream.read(buf, totalRead, contentLength - totalRead)
            if (n == -1) break
            totalRead += n
        }
        return String(buf, 0, totalRead, Charsets.UTF_8).trim()
    }

    private fun checkAuth(headers: Map<String, String>): Boolean {
        if (config.authToken.isEmpty()) return true
        val authHeader = headers["authorization"] ?: headers["Authorization"] ?: ""
        if (authHeader.startsWith("Bearer ") && authHeader.substring(7) == config.authToken) return true
        if (authHeader.startsWith("Basic ")) {
            try {
                val decoded = String(Base64.decode(authHeader.substring(6), Base64.DEFAULT))
                if (decoded.substringAfter(":", "") == config.authToken) return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun createSuccessResponse(id: com.google.gson.JsonElement?, result: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("result", result)
    }

    private fun createErrorResponse(id: com.google.gson.JsonElement?, code: Int, message: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }

    private fun jsonResponse(status: Response.Status, obj: Any): Response {
        return newFixedLengthResponse(status, "application/json", gson.toJson(obj))
    }

    private fun errorResponse(code: Int, message: String): Response {
        return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "application/json",
            gson.toJson(createErrorResponse(null, code, message))
        )
    }
}