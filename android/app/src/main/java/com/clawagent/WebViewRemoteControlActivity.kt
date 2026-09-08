package com.clawagent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.clawagent.cdp.CdpConfig
import com.clawagent.cdp.CdpServer
import com.clawagent.cdp.NavigationResult
import com.clawagent.cdp.WebViewBridge
import com.clawagent.mcp.McpConfig
import com.clawagent.mcp.McpServerService
import com.clawagent.mcp.WebViewBridgeProxy
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * WebView 远程控制 Activity —— 实现 Chrome DevTools Protocol (CDP) 子集。
 *
 * 本 Activity 只负责：
 *   1. Compose UI 渲染（状态栏、WebView 嵌入、日志面板）
 *   2. 实现 [WebViewBridge]（导航/JS 执行/截图等 WebView 操作）
 *   3. CDP Server 生命周期管理（创建、启动、停止）
 *
 * CDP 协议实现细节均在 cdp/ 子包中，与 Activity 解耦，方便后续扩展 MCP 能力。
 *
 * 架构：
 * ┌────────────────────┐      CDP (HTTP + WS)     ┌───────────────────┐
 * │ ClawAgent Gateway   │ ─────────────────────→  │ CdpServer (:19001)│
 * │ (browser plugin)   │                         │   CdpSession      │
 * └────────────────────┘                         │                   │
 * ┌────────────────────┐      MCP (TCP)          │   WebViewBridge   │
 * │ ClawAgent MCP       │ ─────────────────────→  │   (Activity)      │
 * │ client             │    :19003               │                   │
 * └────────────────────┘                         │   WebView         │
 *                                                └───────────────────┘
 *
 * ClawAgent 配置示例 (clawagent.json):
 *   CDP:
 *     "browser": {
 *       "profiles": {
 *         "android-webview": {
 *           "cdpUrl": "http://token@127.0.0.1:19001",
 *           "attachOnly": true
 *         }
 *       }
 *     }
 *   MCP:
 *     "mcp": {
 *       "servers": {
 *         "android-webview": {
 *           "transport": "tcp",
 *           "host": "127.0.0.1",
 *           "port": 19003
 *         }
 *       }
 *     }
 */
class WebViewRemoteControlActivity : ComponentActivity(), WebViewBridge {

    /** 服务运行模式：CDP 和 MCP 互斥，同时只启用一个 */
    enum class ServerMode { CDP, MCP }

    private lateinit var webView: WebView
    private var cdpServer: CdpServer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── UI 状态（Compose 可观察） ────────────────────────────────────
    private var _currentUrl by mutableStateOf("")
    private var _pageTitle by mutableStateOf("")
    private var serverMode by mutableStateOf(ServerMode.CDP)
    private var serverStatus by mutableStateOf("未启动")
    private var serverPort by mutableIntStateOf(CdpConfig.DEFAULT_CDP_PORT)
    private var logMessages by mutableStateOf("")

    // ── 地理位置策略 ────────────────────────────────────────────────
    @Volatile private var geoPolicyMode   = "device" // "deny" | "device" | "mock"
    @Volatile private var geoMockLat      = 0.0
    @Volatile private var geoMockLon      = 0.0
    @Volatile private var geoMockAccuracy = 50.0

    // ── WebViewBridge 实现 ─────────────────────────────────────────

    override val currentUrl: String get() = _currentUrl
    override val pageTitle: String get() = _pageTitle
    override val viewportWidth: Int get() = if (::webView.isInitialized) webView.width else 0
    override val viewportHeight: Int get() = if (::webView.isInitialized) webView.height else 0
    override val viewportZoom: Float = 1.5f

    override suspend fun navigateAsync(url: String, timeoutMs: Long): NavigationResult {
        val loaderId = UUID.randomUUID().toString()
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.post {
                        if (!cont.isActive) return@post
                        val originalClient = webView.webViewClient
                        var done = false

                        val tempClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                _currentUrl = url ?: ""
                                appendLog("页面开始加载: $url")
                            }

                            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                super.onPageFinished(view, finishedUrl)
                                _currentUrl = finishedUrl ?: ""
                                view?.title?.let { _pageTitle = it }
                                appendLog("页面加载完成: $finishedUrl")
                                cdpServer?.notifyPageLoadComplete(finishedUrl ?: "", _pageTitle)
                                injectCSS(view)
                                enableScale(view)
                                adaptPageIfNeed(view, finishedUrl ?: "")
                                view?.requestFocus()
                                if (!done) {
                                    done = true
                                    webView.webViewClient = originalClient
                                    if (cont.isActive) cont.resume(NavigationResult(true, loaderId, null), null)
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                appendLog("加载错误: ${error?.description}")
                                if (!done && request?.isForMainFrame == true) {
                                    done = true
                                    val msg = error?.description?.toString() ?: "Navigation error"
                                    webView.webViewClient = originalClient
                                    if (cont.isActive) cont.resume(NavigationResult(false, loaderId, msg), null)
                                }
                            }
                        }

                        webView.webViewClient = tempClient
                        cont.invokeOnCancellation {
                            handler.post { if (!done) { done = true; webView.webViewClient = originalClient } }
                        }
                        try {
                            webView.loadUrl(url)
                            appendLog("开始导航: $url")
                        } catch (e: Exception) {
                            if (!done) {
                                done = true
                                webView.webViewClient = originalClient
                                if (cont.isActive) cont.resume(NavigationResult(false, loaderId, e.message ?: "failed"), null)
                            }
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Page.navigate timed out after ${timeoutMs}ms for $url")
            NavigationResult(false, loaderId, "Navigation timed out after ${timeoutMs}ms")
        }
    }

    override fun navigate(url: String) {
        runOnUiThread {
            try { webView.loadUrl(url); appendLog("CDP navigate: $url") }
            catch (e: Exception) { Log.e(TAG, "navigate failed: ${e.message}") }
        }
    }

    override suspend fun evaluateJs(expression: String, timeoutMs: Long): String =
        try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        try {
                            webView.evaluateJavascript(expression) { value -> cont.resume(value ?: "null", null) }
                        } catch (e: Exception) { cont.resume("null", null) }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "evaluateJavascript timed out after ${timeoutMs}ms")
            "null"
        } catch (e: Exception) {
            Log.e(TAG, "evaluateJavascript failed: ${e.message}", e)
            "null"
        }

    override suspend fun captureScreenshot(): ByteArray? =
        withContext(Dispatchers.Main) {
            try {
                val w = webView.width
                val h = webView.height
                if (w <= 0 || h <= 0) return@withContext null
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

                // PixelCopy captures hardware-accelerated (GPU) content; webView.draw() only
                // captures software-rendered content and returns blank on hardware-accelerated views.
                suspendCancellableCoroutine { cont ->
                    val window = this@WebViewRemoteControlActivity.window
                    val loc = IntArray(2).also { webView.getLocationInWindow(it) }
                    val srcRect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
                    PixelCopy.request(window, srcRect, bmp, { result ->
                        if (result == PixelCopy.SUCCESS) {
                            val stream = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.PNG, 80, stream)
                            cont.resume(stream.toByteArray(), null)
                        } else {
                            Log.w(TAG, "PixelCopy failed: $result, falling back to draw()")
                            val canvas = Canvas(bmp)
                            webView.draw(canvas)
                            val stream = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.PNG, 80, stream)
                            cont.resume(stream.toByteArray(), null)
                        }
                    }, Handler(Looper.getMainLooper()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed: ${e.message}")
                null
            }
        }

    override suspend fun dispatchTouchAt(x: Float, y: Float): Boolean =
        withContext(Dispatchers.Main) {
            if (!::webView.isInitialized) return@withContext false
            try {
                val downTime = SystemClock.uptimeMillis()
                val downEvent = MotionEvent.obtain(
                    downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
                )
                val upEvent = MotionEvent.obtain(
                    downTime, downTime + 60, MotionEvent.ACTION_UP, x, y, 0
                )
                val a = webView.dispatchTouchEvent(downEvent)
                val b = webView.dispatchTouchEvent(upEvent)
                downEvent.recycle()
                upEvent.recycle()
                a || b
            } catch (e: Exception) {
                Log.e(TAG, "dispatchTouchAt failed: ${e.message}", e)
                false
            }
        }

    override suspend fun dispatchSwipe(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        durationMs: Long
    ): Boolean = withContext(Dispatchers.Main) {
        if (!::webView.isInitialized) return@withContext false
        try {
            val downTime = SystemClock.uptimeMillis()
            val steps = maxOf(2, (durationMs / 16).toInt())
            val stepDelay = durationMs / steps

            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, fromY, 0)
            webView.dispatchTouchEvent(down)
            down.recycle()

            for (i in 1..steps) {
                val fraction = i.toFloat() / steps
                val mx = fromX + (toX - fromX) * fraction
                val my = fromY + (toY - fromY) * fraction
                val eventTime = downTime + stepDelay * i
                val move = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, mx, my, 0)
                webView.dispatchTouchEvent(move)
                move.recycle()
                kotlinx.coroutines.delay(stepDelay)
            }

            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, toX, toY, 0)
            webView.dispatchTouchEvent(up)
            up.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "dispatchSwipe failed: ${e.message}", e)
            false
        }
    }

    override fun clearContent() {
        runOnUiThread {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                _currentUrl = ""
                _pageTitle = ""
                Log.i(TAG, "WebView content cleared")
            } catch (e: Exception) { Log.e(TAG, "clearContent failed: ${e.message}") }
        }
    }

    override fun pressSystemBack() {
        runOnUiThread {
            // singleTask ensures only one instance exists across all tasks.
            // FLAG_ACTIVITY_REORDER_TO_FRONT alone doesn't work when the caller is in a
            // different task (e.g. Meituan launched via FLAG_ACTIVITY_NEW_TASK) —
            // with singleTask the system unconditionally brings our existing instance to front.
            val intent = android.content.Intent(this, WebViewRemoteControlActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
            appendLog("pressSystemBack: brought WebViewRemoteControlActivity to front")
        }
    }

    override fun appendLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logMessages = "$logMessages\n[$ts] $msg"
        if (logMessages.length > 2000) logMessages = logMessages.takeLast(2000)
    }

    override fun setGeolocationPolicy(mode: String, lat: Double, lon: Double, accuracy: Double) {
        geoPolicyMode = mode
        geoMockLat = lat
        geoMockLon = lon
        geoMockAccuracy = accuracy
        appendLog("地理位置策略: $mode" + if (mode == "mock") " ($lat,$lon)" else "")
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun getLastKnownLocation(): Pair<Double, Double>? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(android.location.LocationManager.FUSED_PROVIDER)
            add(android.location.LocationManager.GPS_PROVIDER)
            add(android.location.LocationManager.NETWORK_PROVIDER)
        }
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null) return Pair(loc.latitude, loc.longitude)
            } catch (_: Exception) {}
        }
        return null
    }

    // ── 生命周期 ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cdpAuthToken = intent?.getStringExtra(CdpConfig.EXTRA_AUTH_TOKEN) ?: ""
        val cdpPortVal = intent?.getIntExtra(CdpConfig.EXTRA_PORT, CdpConfig.DEFAULT_CDP_PORT) ?: CdpConfig.DEFAULT_CDP_PORT
        val cdpConfig = CdpConfig(port = cdpPortVal, authToken = cdpAuthToken)

        val mcpAuthToken = intent?.getStringExtra(McpConfig.EXTRA_AUTH_TOKEN) ?: cdpAuthToken
        val mcpPortVal = intent?.getIntExtra(McpConfig.EXTRA_PORT, McpConfig.DEFAULT_MCP_PORT) ?: McpConfig.DEFAULT_MCP_PORT
        val mcpConfig = McpConfig(port = mcpPortVal, authToken = mcpAuthToken)

        if (cdpAuthToken.isEmpty()) Log.i(TAG, "CDP Server: auth disabled")
        else Log.i(TAG, "CDP Server: auth token configured (${cdpAuthToken.length} chars)")
        Log.i(TAG, "MCP Server: port=$mcpPortVal")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setContent { MaterialTheme { CDPRemoteControlScreen(cdpConfig, mcpConfig) } }

        // 延迟 500ms 等 WebView 创建完成，默认启动 MCP
        scope.launch {
            delay(500.milliseconds)
            serverMode = ServerMode.MCP
            startMcpServer(mcpConfig)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 只清自己注册的那份代理目标，避免与新 Activity 实例互相覆盖；
        // MCP Server 本身托管在 McpServerService，不随本 Activity 销毁而停止。
        if (WebViewBridgeProxy.target === this) WebViewBridgeProxy.target = null
        stopAllServers()
        scope.cancel()
        if (::webView.isInitialized) {
            // 先从父 View 移除再 destroy，避免 EGL context 泄漏
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.clearHistory()
            webView.destroy()
        }
    }

    /** 停止所有服务（不含常驻的 McpServerService，见 [stopMcpServer]） */
    private fun stopAllServers() {
        stopCDPServer()
        stopMcpServer()
    }

    // ── 服务切换（CDP/MCP 互斥） ────────────────────────────────────

    /**
     * 切换服务模式。CDP 和 MCP 互斥，切换时先停旧服务再启新服务。
     */
    fun switchServerMode(newMode: ServerMode, cdpConfig: CdpConfig, mcpConfig: McpConfig) {
        if (newMode == serverMode) return
        Log.i(TAG, "Switching mode: $serverMode -> $newMode")
        appendLog("切换模式: ${serverMode.name} → ${newMode.name}")

        // 停止当前服务
        stopAllServers()

        serverMode = newMode
        when (newMode) {
            ServerMode.CDP -> startCDPServer(cdpConfig)
            ServerMode.MCP -> startMcpServer(mcpConfig)
        }
    }

    // ── CDP Server 生命周期 ─────────────────────────────────────────

    private fun startCDPServer(config: CdpConfig) {
        if (cdpServer != null) { stopCDPServer() }
        try {
            cdpServer = CdpServer(config, this).also { it.start(0) }
            serverPort = config.port
            serverStatus = "运行中"
            appendLog("CDP: http://127.0.0.1:${config.port}")
            Log.i(TAG, "CDP Server started on port ${config.port}")
        } catch (e: Exception) {
            serverStatus = "启动失败"
            appendLog("CDP 启动失败: ${e.message}")
            Log.e(TAG, "Failed to start CDP Server", e)
        }
    }

    private fun stopCDPServer() {
        cdpServer?.stop()
        cdpServer = null
        if (serverMode == ServerMode.CDP) serverStatus = "已停止"
    }

    // ── MCP Server 生命周期 ─────────────────────────────────────────

    /**
     * 把本 Activity 注册为 WebView 桥的代理目标，并确保常驻的 [McpServerService] 在运行。
     *
     * McpServer 实例现在托管在 McpServerService 里，与本 Activity 的生命周期解耦——
     * 这里不再自己创建/持有 McpServer，只是"注册桥 + 确保服务已启动"（两者都是幂等操作）。
     */
    private fun startMcpServer(config: McpConfig) {
        try {
            WebViewBridgeProxy.target = this
            McpServerService.ensureStarted(applicationContext)
            serverPort = config.port
            serverStatus = "运行中（常驻服务）"
            appendLog("MCP: :${config.port}（常驻服务，不受本页面生命周期影响）")
            Log.i(TAG, "MCP bridge attached, McpServerService ensured on port ${config.port}")
        } catch (e: Exception) {
            serverStatus = "启动失败"
            appendLog("MCP 启动失败: ${e.message}")
            Log.e(TAG, "Failed to attach MCP bridge / start McpServerService", e)
        }
    }

    /**
     * 仅解除本 Activity 与 WebView 桥代理的绑定，并更新 UI 展示状态。
     * McpServerService/McpServer 本身保持常驻运行，不会被这里停止——
     * clawagent 依赖 19003 端口随时可用，不应因为切换调试模式或页面销毁而下线。
     */
    private fun stopMcpServer() {
        if (WebViewBridgeProxy.target === this) WebViewBridgeProxy.target = null
        if (serverMode == ServerMode.MCP) serverStatus = "已停止（WebView 桥已解绑，常驻服务仍在运行）"
    }

    // ── Compose UI ──────────────────────────────────────────────────

    @Composable
    fun CDPRemoteControlScreen(cdpConfig: CdpConfig, mcpConfig: McpConfig) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(WindowInsets(0)),
                    factory = { ctx ->
                        // 只放一个容器，等它 layout 完成有真实尺寸后再 addView WebView，
                        // 确保 setInitialScale 在 chromium renderer 初始化之前调用
                        android.widget.FrameLayout(ctx).also { container ->
                            container.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                                val w = r - l; val h = b - t
                                if (w > 0 && h > 0 && !::webView.isInitialized) {
                                    val wv = createWebView(ctx)
                                    webView = wv
                                    Log.i(TAG, "addView WebView after container layout: ${w}x${h}")
                                    container.addView(wv, android.widget.FrameLayout.LayoutParams(
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                                    ))
                                }
                            }
                        }
                    },
                    update = {}
                )
            IconButton(
                onClick = {
                    if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(48.dp)
                    .background(Color(0x99000000), shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
        }
    }

    // ── WebView 初始化 ──────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView {
        val wv = WebView(context).apply {
            val ua = settings.userAgentString
                .replace("Linux", "Windows NT 11.0")
                .replace(Regex("Android \\d+"), "Win64")
                .replace("wv", "x64")
            settings.userAgentString = "$ua LiBrowser/wcf"

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = false
            setOverScrollMode(android.view.View.OVER_SCROLL_NEVER)
        }
        wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        wv.setInitialScale(150)
        WebView.setWebContentsDebuggingEnabled(true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme ?: return false
                // 只拦截非 http/https 的 meituan:// 深链接，唤起本地美团 App
                // http/https 的 meituan.com 地址（如支付二维码页）应在 WebView 内正常加载，
                // 否则猫眼等网站的美团支付页会被强制跳转 native App 而无法弹出二维码
                if (scheme == "meituan") {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start meituan: ${e.message}")
                    }
                    return true
                }
                return false
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                _currentUrl = url ?: ""
                appendLog("页面开始加载: $url")
                view?.setInitialScale(150)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                _currentUrl = url ?: ""
                view?.title?.let { _pageTitle = it }
                appendLog("页面加载完成: $url")
                cdpServer?.notifyPageLoadComplete(url ?: "", _pageTitle)
                injectCSS(view)
                enableScale(view)
                adaptPageIfNeed(view, url ?: "")
                view?.requestFocus()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                appendLog("加载错误: ${error?.description}")
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                _pageTitle = title ?: ""
            }
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // 尽早修正 viewport，避免页面在加载中阶段因宽 viewport（如 width=1440）导致内容偏右
                if (newProgress >= 20) enableScale(view)
                if (newProgress == 100) {
                    val url = view?.url ?: return
                    view.postDelayed({ adaptPageIfNeed(view, url) }, 1000)
                }
            }
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                when (geoPolicyMode) {
                    "device" -> callback.invoke(origin, true, false)
                    "mock"   -> callback.invoke(origin, true, false)
                    else     -> callback.invoke(origin, false, false)  // "deny" and unknown
                }
            }
        }

        wv.loadUrl(CdpConfig.DEFAULT_LOAD_URL)
        return wv
    }

    // ── WebView 页面注入（对齐 LiBrowser 处理流程）─────────────────────────────

    private fun injectCSS(view: WebView?) {
        view?.loadUrl("""javascript:(function(){
            var s=document.getElementById('wcf-injected-css');
            if(s)return;
            s=document.createElement('style');
            s.id='wcf-injected-css';
            s.textContent='*{-webkit-tap-highlight-color:transparent!important;}';
            document.head.appendChild(s);
        })()""".trimIndent())
    }

    private fun enableScale(view: WebView?) {
        view?.evaluateJavascript("""(function(){
            var metas=document.getElementsByTagName('meta');
            for(var i=0;i<metas.length;i++){
                if(metas[i].getAttribute('name')==='viewport'){
                    metas[i].setAttribute('content','width=device-width');
                }
            }
        })()""".trimIndent(), null)
    }

    private fun adaptPageIfNeed(view: WebView?, url: String) {
        if (view == null || url.isEmpty()) return
        view.evaluateJavascript("""(function(){
            var h=window.innerHeight;
            if(h>0&&document.documentElement.offsetHeight<h){
                var s=document.getElementById('wcf-icb-fix');
                if(!s){s=document.createElement('style');s.id='wcf-icb-fix';document.head.appendChild(s);}
                s.textContent='html{min-height:'+h+'px!important;position:relative!important}';
            }
        })()""".trimIndent(), null)
    }

    companion object {
        private const val TAG = "WebViewCDP"
    }
}
