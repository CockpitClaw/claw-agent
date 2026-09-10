package com.clawagent.cdp

/**
 * WebView 操作桥接接口 —— CDP/MCP 层通过此接口操控 WebView，与 Activity/UI 解耦。
 *
 * Activity 实现该接口；CDP Server 和将来的 MCP Server 都只依赖该接口。
 */
interface WebViewBridge {

    /** 当前页面 URL（只读快照） */
    val currentUrl: String

    /** 当前页面标题（只读快照） */
    val pageTitle: String

    /** WebView 视口宽度（px） */
    val viewportWidth: Int

    /** WebView 视口高度（px） */
    val viewportHeight: Int

    /**
     * 视口缩放倍率：物理像素 / CSS像素。
     * 例如 1.5 → CSS viewport = physWidth / 1.5，点击坐标需乘以 1.5 转换为 View 像素。
     */
    val viewportZoom: Float get() = 1.5f

    // ── 导航 ────────────────────────────────────────────────────────

    /**
     * 异步导航，挂起直到 onPageFinished / onReceivedError 回调或超时。
     * @return [NavigationResult]
     */
    suspend fun navigateAsync(url: String, timeoutMs: Long = 30_000): NavigationResult

    /** 立即导航（fire-and-forget），不等待加载结果。 */
    fun navigate(url: String)

    // ── JS 执行 ─────────────────────────────────────────────────────

    /**
     * 异步执行 JavaScript，返回 `evaluateJavascript` 回调的原始字符串（JSON-encoded）。
     * 默认超时 3 s，可通过 [timeoutMs] 调整。
     */
    suspend fun evaluateJs(expression: String, timeoutMs: Long = 3_000): String

    // ── 截图 ────────────────────────────────────────────────────────

    /**
     * 捕获 WebView 当前内容并以 PNG 字节数组返回；失败时返回 null。
     */
    suspend fun captureScreenshot(): ByteArray?

    // ── 输入 ────────────────────────────────────────────────────────

    /**
     * 在给定的 viewport 坐标 (x, y) 派发一次真实的 down→up 点击。
     *
     * 与 `el.click()` 等 DOM 合成事件不同，本方法应通过原生输入通道
     * （如 Android `View.dispatchTouchEvent` 或 CDP `Input.dispatchTouchEvent`）
     * 派发事件，因此 `event.isTrusted === true`，可触发页面上监听
     * `touchstart / touchend / pointerdown / pointerup` 而未监听 `click`
     * 的元素（例如京东商品卡）。
     *
     * @return 是否成功派发；实现类若无法提供原生输入通道，返回 false，
     *         MCP 层将回退到基于 `elementFromPoint(...).click()` 的旧行为。
     */
    suspend fun dispatchTouchAt(x: Float, y: Float): Boolean = false

    /**
     * 派发原生滑动手势：ACTION_DOWN → 若干 ACTION_MOVE → ACTION_UP。
     *
     * @param fromX  起始 X（viewport px）
     * @param fromY  起始 Y（viewport px）
     * @param toX    终点 X（viewport px）
     * @param toY    终点 Y（viewport px）
     * @param durationMs 手势总时长（毫秒）；越短速度越快，会触发惯性滚动
     */
    suspend fun dispatchSwipe(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        durationMs: Long = 300L
    ): Boolean = false

    // ── 生命周期 ─────────────────────────────────────────────────────

    /**
     * 清空 WebView 内容（加载 about:blank）。
     * 用于 Browser.close 等场景，不销毁 WebView 本身。
     */
    fun clearContent()

    /**
     * 发送系统级 Back 键（KEYCODE_BACK），等效于用户按下实体返回键。
     * 用于从外部 App（如美团）切回本 Activity 的 WebView 界面。
     */
    fun pressSystemBack()

    /** 向 UI 追加一条日志消息（在 Activity 日志面板显示）。 */
    fun appendLog(msg: String)

    /**
     * 配置地理位置响应策略，在 navigate 之前调用。
     * @param mode "deny" | "device" | "mock"
     * @param lat  纬度（mode=mock 时必填）
     * @param lon  经度（mode=mock 时必填）
     * @param accuracy 精度，米（mode=mock 时可选，默认 50）
     */
    fun setGeolocationPolicy(mode: String, lat: Double = 0.0, lon: Double = 0.0, accuracy: Double = 50.0) {}

    /**
     * 尝试从 Android LocationManager 获取最后已知位置。
     * 无位置权限或 GPS 无信号时返回 null。
     */
    fun getLastKnownLocation(): Pair<Double, Double>? = null
}

/** 导航操作的结果。 */
data class NavigationResult(
    val success: Boolean,
    val loaderId: String,
    val errorText: String?
)
