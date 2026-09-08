package com.clawagent.mcp.browser

import android.util.Log
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * 发现外部 app 的 WebView DevTools 页面。
 * 通过 TcpForwarder 提供的 TCP 端口访问浏览器 DevTools。
 */
class DevToolsDiscovery {

    companion object {
        private const val TAG = "DevToolsDiscovery"
        private const val SOCKET_PREFIX = "webview_devtools_remote_"
        private const val FORWARDER_BASE_PORT = 19333
        /** 用于本 app 自身 WebView 的 CDP forwarder 端口（与 car-browser 那条分开）。*/
        const val LOCAL_WEBVIEW_FORWARDER_PORT = 19334
        private const val OWN_PACKAGE = "com.clawagent"
    }

    data class BrowserPageInfo(
        val pid: Int,
        val socketName: String,
        val cdpPageId: String,
        val title: String,
        val url: String,
        val webSocketPath: String,
        val tcpPort: Int
    )

    private var forwarderProcess: Process? = null
    private var currentForwardedPid: Int = 0

    /**
     * 检测 TcpForwarder 是否在运行（由外部 adb shell 启动）。
     * 返回转发端口，-1 表示未就绪。
     */
    fun ensureForwarder(apkPath: String): Int {
        if (testPort(FORWARDER_BASE_PORT)) {
            return FORWARDER_BASE_PORT
        }

        // TcpForwarder 未运行，尝试通过 shell 启动（需要 root adb shell）
        try {
            val browserPid = findBrowserPid()
            if (browserPid <= 0) {
                Log.w(TAG, "No browser process found")
                return -1
            }

            val socketName = "$SOCKET_PREFIX$browserPid"
            Log.i(TAG, "TcpForwarder not running, attempting to start for @$socketName")

            val process = Runtime.getRuntime().exec(arrayOf(
                "sh", "-c",
                "CLASSPATH=$apkPath setsid app_process /system/bin " +
                    "com.clawagent.mcp.browser.TcpForwarder " +
                    "$socketName $FORWARDER_BASE_PORT &"
            ))
            process.waitFor()
            forwarderProcess = process

            Thread.sleep(2000)

            return if (testPort(FORWARDER_BASE_PORT)) {
                Log.i(TAG, "TcpForwarder started on port $FORWARDER_BASE_PORT")
                FORWARDER_BASE_PORT
            } else {
                Log.w(TAG, "TcpForwarder failed to start (needs root adb shell)")
                -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start TcpForwarder from app: ${e.message}")
            return -1
        }
    }

    fun stopForwarder() {
        forwarderProcess?.destroy()
        forwarderProcess = null
        currentForwardedPid = 0
    }

    /**
     * 通过 TCP 端口列出浏览器页面。
     */
    fun listPages(port: Int): List<BrowserPageInfo> {
        try {
            val conn = URL("http://127.0.0.1:$port/json").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            val jsonStr = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()

            if (jsonStr.isEmpty() || !jsonStr.startsWith("[")) return emptyList()

            val pages = mutableListOf<BrowserPageInfo>()
            val arr = JsonParser.parseString(jsonStr).asJsonArray
            for (elem in arr) {
                val obj = elem.asJsonObject
                val type = obj.get("type")?.asString ?: continue
                if (type != "page") continue
                val pageId = obj.get("id")?.asString ?: continue
                val title = obj.get("title")?.asString ?: ""
                val url = obj.get("url")?.asString ?: ""
                val wsUrl = obj.get("webSocketDebuggerUrl")?.asString ?: ""
                val wsPath = if (wsUrl.contains("/devtools/")) {
                    "/devtools/" + wsUrl.substringAfter("/devtools/")
                } else {
                    "/devtools/page/$pageId"
                }
                pages.add(BrowserPageInfo(
                    currentForwardedPid, "$SOCKET_PREFIX$currentForwardedPid",
                    pageId, title, url, wsPath, port
                ))
            }
            return pages
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list pages on port $port: ${e.message}")
            return emptyList()
        }
    }

    fun discoverAllBrowserPages(apkPath: String): List<BrowserPageInfo> {
        val port = ensureForwarder(apkPath)
        if (port < 0) return emptyList()
        return listPages(port)
    }

    // ── 本 app 自身 WebView 的 CDP 连接 ──────────────────────────────

    /**
     * 确保本 app 自身 WebView 的 TcpForwarder 在 LOCAL_WEBVIEW_FORWARDER_PORT 上运行。
     * 返回端口号，-1 表示失败。
     */
    fun ensureLocalWebViewForwarder(apkPath: String): Int {
        if (testPort(LOCAL_WEBVIEW_FORWARDER_PORT)) {
            Log.i(TAG, "Local WebView forwarder already on port $LOCAL_WEBVIEW_FORWARDER_PORT")
            return LOCAL_WEBVIEW_FORWARDER_PORT
        }
        val pid = findClawWebViewPid()
        if (pid <= 0) {
            Log.w(TAG, "Cannot find clawagent WebView PID")
            return -1
        }
        val socketName = "$SOCKET_PREFIX$pid"
        Log.i(TAG, "Starting local WebView forwarder for @$socketName on port $LOCAL_WEBVIEW_FORWARDER_PORT")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "sh", "-c",
                "CLASSPATH=$apkPath setsid app_process /system/bin " +
                    "com.clawagent.mcp.browser.TcpForwarder " +
                    "$socketName $LOCAL_WEBVIEW_FORWARDER_PORT &"
            ))
            process.waitFor()
            Thread.sleep(2000)
            if (testPort(LOCAL_WEBVIEW_FORWARDER_PORT)) {
                Log.i(TAG, "Local WebView forwarder started on port $LOCAL_WEBVIEW_FORWARDER_PORT")
                LOCAL_WEBVIEW_FORWARDER_PORT
            } else {
                Log.w(TAG, "Local WebView forwarder failed to start")
                -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start local WebView forwarder: ${e.message}")
            -1
        }
    }

    /**
     * 通过扫描 /proc/net/unix 查找属于本 app (com.clawagent) 的 WebView 进程 PID。
     * 如果 WebView 运行在独立进程，通过 cmdline 匹配包名；否则 fallback 到 Process.myPid()。
     */
    fun findClawWebViewPid(): Int {
        val ownPid = android.os.Process.myPid()
        try {
            val unixFile = java.io.File("/proc/net/unix")
            if (unixFile.canRead()) {
                val candidates = mutableListOf<Int>()
                unixFile.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val idx = line.indexOf("@$SOCKET_PREFIX")
                        if (idx == -1) continue
                        val socketName = line.substring(idx + 1).trim()
                        val pid = socketName.removePrefix(SOCKET_PREFIX).trim().toIntOrNull() ?: continue
                        candidates.add(pid)
                    }
                }
                // 尝试通过 cmdline 找到属于 clawagent 的 WebView 进程
                for (pid in candidates) {
                    try {
                        val cmdlineBytes = java.io.File("/proc/$pid/cmdline").readBytes()
                        val cmdline = cmdlineBytes.takeWhile { it != 0.toByte() }
                            .toByteArray().toString(Charsets.UTF_8)
                        if (cmdline.contains(OWN_PACKAGE)) {
                            Log.i(TAG, "Found clawagent WebView PID=$pid cmdline=$cmdline")
                            return pid
                        }
                    } catch (_: Exception) {}
                }
                // 如果只有一个 WebView socket 且不是 car browser，就用它
                val nonBrowser = candidates.filter { pid ->
                    try {
                        val cmdline = java.io.File("/proc/$pid/cmdline").readBytes()
                            .takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
                        !cmdline.contains("com.android.chrome")
                    } catch (_: Exception) { false }
                }
                if (nonBrowser.size == 1) {
                    Log.i(TAG, "Using sole non-browser WebView PID=${nonBrowser[0]}")
                    return nonBrowser[0]
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findClawWebViewPid scan failed: ${e.message}")
        }
        Log.i(TAG, "Falling back to own PID=$ownPid for clawagent WebView")
        return ownPid
    }

    private fun findBrowserPid(): Int {
        val ownPid = android.os.Process.myPid()
        try {
            // 方案1：读 /proc/net/unix（SELinux permissive 时可用）
            val file = java.io.File("/proc/net/unix")
            if (file.canRead()) {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val idx = line.indexOf("@$SOCKET_PREFIX")
                        if (idx == -1) continue
                        val socketName = line.substring(idx + 1).trim()
                        val pidStr = socketName.removePrefix(SOCKET_PREFIX)
                        val pid = pidStr.toIntOrNull() ?: continue
                        if (pid != ownPid) return pid
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "/proc/net/unix not readable: ${e.message}")
        }

        // 方案2：扫描 /proc 目录尝试常见浏览器 PID
        try {
            val procDir = java.io.File("/proc")
            val pids = procDir.list()
                ?.mapNotNull { it.toIntOrNull() }
                ?.filter { it != ownPid && it > 1000 }
                ?.sortedDescending()
                ?: return 0

            for (pid in pids) {
                // 检查 cmdline 是否是浏览器
                try {
                    val cmdline = java.io.File("/proc/$pid/cmdline").readText()
                    if (cmdline.contains("com.android.chrome")) {
                        return pid
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "PID scan failed: ${e.message}")
        }

        return 0
    }

    private fun testPort(port: Int): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 3000)
            socket.close()
            Log.i(TAG, "testPort($port): SUCCESS")
            true
        } catch (e: Exception) {
            Log.w(TAG, "testPort($port): FAILED - ${e.message}")
            false
        }
    }
}
