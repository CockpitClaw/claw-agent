package com.clawagent.mcp

import com.clawagent.cdp.NavigationResult
import com.clawagent.cdp.WebViewBridge

/**
 * [WebViewBridge] 的可插拔代理。
 *
 * 背景：MCP Server 现在托管在长生命周期的 [McpServerService] 里，不再随
 * WebViewRemoteControlActivity 的销毁而销毁（见该 Service 类注释）。但 WebView
 * 相关工具终究需要一个活着的 Activity 实例才能操作真实的 WebView。
 *
 * 本代理持有一个可随时替换/置空的目标引用：
 *   - Activity onCreate 时把自己注册进来（`target = this`）
 *   - Activity onDestroy 时清空注册（仅清自己那份，避免新旧实例互相覆盖）
 *
 * target 为空时，WebView 相关工具调用会抛出 [IllegalStateException]，
 * 由 [McpServer] 既有的 try/catch 转换成正常的 tool error 返回给客户端，
 * 不影响 `native_*` 工具（它们不依赖这个代理）继续正常工作。
 */
object WebViewBridgeProxy : WebViewBridge {

    @Volatile
    var target: WebViewBridge? = null

    private fun require(): WebViewBridge = target
        ?: throw IllegalStateException(
            "WebView 不可用：clawagent 界面当前未打开，请先打开 App 界面后重试（不影响 native_* 工具）"
        )

    // 简单属性 getter 在没有 target 时返回安全默认值而不是抛异常：
    // McpTools 的 init 块会在 McpServer 构造时（即 McpServerService.onCreate()）
    // 立即读取 currentUrl/pageTitle 来初始化首页记录，此时 Activity 可能还没启动过，
    // 若这里抛异常会导致整个 Service（进而整个进程）崩溃。
    override val currentUrl: String get() = target?.currentUrl ?: ""
    override val pageTitle: String get() = target?.pageTitle ?: ""
    override val viewportWidth: Int get() = target?.viewportWidth ?: 0
    override val viewportHeight: Int get() = target?.viewportHeight ?: 0
    override val viewportZoom: Float get() = target?.viewportZoom ?: 1.5f

    override suspend fun navigateAsync(url: String, timeoutMs: Long): NavigationResult =
        require().navigateAsync(url, timeoutMs)

    override fun navigate(url: String) = require().navigate(url)

    override suspend fun evaluateJs(expression: String, timeoutMs: Long): String =
        require().evaluateJs(expression, timeoutMs)

    override suspend fun captureScreenshot(): ByteArray? = require().captureScreenshot()

    override suspend fun dispatchTouchAt(x: Float, y: Float): Boolean = require().dispatchTouchAt(x, y)

    override suspend fun dispatchSwipe(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        durationMs: Long
    ): Boolean = require().dispatchSwipe(fromX, fromY, toX, toY, durationMs)

    override fun clearContent() = require().clearContent()

    override fun pressSystemBack() = require().pressSystemBack()

    override fun appendLog(msg: String) {
        // 没有目标时日志直接丢弃，不抛异常（日志不是关键路径）
        target?.appendLog(msg)
    }

    override fun setGeolocationPolicy(mode: String, lat: Double, lon: Double, accuracy: Double) {
        target?.setGeolocationPolicy(mode, lat, lon, accuracy)
    }

    override fun getLastKnownLocation(): Pair<Double, Double>? = target?.getLastKnownLocation()
}
