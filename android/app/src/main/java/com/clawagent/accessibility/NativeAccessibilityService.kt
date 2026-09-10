package com.clawagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * NativeAccessibilityService — 原生 Android UI 控制服务
 *
 * 作为 MCP Tools 的底层执行引擎，支持：
 * - 手势操作（点击、长按、滑动）
 * - 文字输入
 * - UI 树遍历（任意 App）
 * - 截屏（API 30+）
 * - 系统按键（返回、Home、通知栏）
 *
 * 服务启用方式（二选一）：
 * 1. 车机系统预配置 /system/etc/accessibility/ 自动启用
 * 2. 编程方式：调用 AccessibilityServiceHelper.ensureEnabled(context)
 *    （需要 WRITE_SECURE_SETTINGS 权限，平台签名 App 可获得）
 */
class NativeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NativeA11yService"

        /** 完整的服务组件名，用于编程方式启用服务 */
        const val COMPONENT_NAME = "com.clawagent/" +
            "com.clawagent.accessibility.NativeAccessibilityService"

        @Volatile
        private var instance: NativeAccessibilityService? = null

        /** 直接获取服务实例，服务未启用时返回 null */
        fun getInstance(): NativeAccessibilityService? = instance

        /**
         * 阻塞等待服务就绪，最长等待 [timeoutMs] 毫秒。
         * 用于首次启动时等待服务连接完成。
         */
        fun getInstanceSync(timeoutMs: Long = 5000): NativeAccessibilityService? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                instance?.let { return it }
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
            return null
        }
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Service created")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            // 已含 FLAG_RETRIEVE_INTERACTIVE_WINDOWS，使 windowsOnAllDisplays 能返回多屏窗口。
            // 注：Android 公开 SDK 没有 FLAG_REQUEST_MULTI_DISPLAY 常量（@SystemApi/@hide），
            // 真正限制 AccessibilityService 看到虚拟屏（displayId=10）窗口的是系统级权限，
            // 不是 flag。若 com.alipay.arome.app 在副屏上仍拿不到，AccessibilityService 这条路
            // 走不通，需要走 CDP（WebView 调试协议）或要求小程序渲染到主屏。
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.i(TAG, "Service connected — ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 被动监听，当前无需处理；如需实时感知 UI 变化可在此扩展
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    // ── UI 树 ─────────────────────────────────────────────────────────────

    /**
     * 获取指定包名应用的窗口根节点。
     * [packageName] 为 null 时返回最顶层窗口的根节点。
     *
     * - API 30+ 使用 windowsOnAllDisplays 遍历所有屏幕
     * - 对找到的窗口验证 bounds 有效性，跳过空窗口
     * - 回退到主屏 windows（< API 30 兼容）
     *
     * 调用方获得节点的所有权，需在使用完毕后调用 recycle()（或交给 NativeAccessibilityMcpTools 统一管理）。
     */
    fun findWindowRoot(packageName: String? = null, displayId: Int? = null): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+：windowsOnAllDisplays 返回 SparseArray<List<AccessibilityWindowInfo>>
            // key 是 displayId，value 是该 display 上的所有 window
            val allWindows = windowsOnAllDisplays
            val displayKeys: List<Int> = if (displayId != null) {
                // 调用方指定了 displayId：只在该 display 上找
                listOf(displayId)
            } else {
                // 未指定：遍历所有 display
                (0 until allWindows.size()).map { allWindows.keyAt(it) }
            }
            if (packageName == null) {
                // 第一轮：优先返回非系统窗口（TYPE_APPLICATION / TYPE_INPUT_METHOD 等），
                // 避免在系统导航栏/状态栏窗口（TYPE_SYSTEM）上返回错误结果。
                for (d in displayKeys) {
                    val wins = allWindows.get(d) ?: continue
                    for (w in wins) {
                        if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) continue
                        val root = w.root ?: continue
                        val r = android.graphics.Rect()
                        root.getBoundsInScreen(r)
                        if (r.width() > 0 && r.height() > 0) {
                            Log.d(TAG, "findWindowRoot(null): display=$d type=${w.type} pkg=${root.packageName}")
                            return root
                        }
                    }
                }
                // 第二轮（兜底）：若没有非系统窗口，才接受系统窗口
                for (d in displayKeys) {
                    val wins = allWindows.get(d) ?: continue
                    for (w in wins) {
                        val root = w.root ?: continue
                        val r = android.graphics.Rect()
                        root.getBoundsInScreen(r)
                        if (r.width() > 0 && r.height() > 0) {
                            Log.d(TAG, "findWindowRoot(null) fallback: display=$d pkg=${root.packageName}")
                            return root
                        }
                    }
                }
                return null
            }
            // 指定 packageName
            for (d in displayKeys) {
                val wins = allWindows.get(d) ?: continue
                for (w in wins) {
                    val root = w.root ?: continue
                    if (root.packageName?.toString() != packageName) continue
                    val rect = android.graphics.Rect()
                    root.getBoundsInScreen(rect)
                    if (rect.width() <= 0 || rect.height() <= 0) {
                        Log.w(TAG, "findWindowRoot: skip invalid-bounds window for $packageName on display=$d: $rect")
                        continue
                    }
                    Log.i(TAG, "findWindowRoot: found $packageName on display=$d, bounds=$rect")
                    return root
                }
            }
            Log.w(TAG, "findWindowRoot: no window found for $packageName on displayId=$displayId (searched displays=$displayKeys)")
            return null
        } else {
            // < API 30 回退：仅主屏
            val wins = windows ?: return null
            if (packageName == null) {
                // 同样优先跳过系统窗口
                return wins.firstOrNull { it.type != AccessibilityWindowInfo.TYPE_SYSTEM }?.root
                    ?: wins.firstOrNull()?.root
            }
            for (w in wins) {
                val root = w.root ?: continue
                if (root.packageName?.toString() == packageName) return root
            }
            return null
        }
    }

    /**
     * 返回当前前台应用的包名（遍历所有 display）。
     * 优先跳过系统窗口（导航栏/状态栏），返回最靠前的应用窗口包名。
     */
    fun getForegroundPackage(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val allWindows = windowsOnAllDisplays
            // 第一轮：跳过系统窗口
            for (i in 0 until allWindows.size()) {
                val pkg = allWindows.valueAt(i)
                    .firstOrNull { it.type != AccessibilityWindowInfo.TYPE_SYSTEM }
                    ?.root?.packageName?.toString()
                if (pkg != null) return pkg
            }
            // 第二轮（兜底）：接受所有窗口
            for (i in 0 until allWindows.size()) {
                val pkg = allWindows.valueAt(i).firstOrNull()?.root?.packageName?.toString()
                if (pkg != null) return pkg
            }
            return null
        }
        val wins = windows ?: return null
        return wins.firstOrNull { it.type != AccessibilityWindowInfo.TYPE_SYSTEM }
            ?.root?.packageName?.toString()
            ?: wins.firstOrNull()?.root?.packageName?.toString()
    }

    // ── 手势操作 ──────────────────────────────────────────────────────────

    /**
     * 单击屏幕坐标 ([x], [y])。
     * [displayId] &gt;= 0 时（需 API 31+）将手势定向到指定虚拟屏（如车机副屏 displayId=10），
     * 传 -1（默认）沿用主屏（displayId=0）。
     */
    suspend fun click(x: Float, y: Float, displayId: Int = -1): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        return dispatchGestureSuspend(buildGesture(stroke, displayId))
    }

    /**
     * 长按屏幕坐标 ([x], [y])，持续 [durationMs] 毫秒。
     */
    suspend fun longClick(x: Float, y: Float, durationMs: Long = 1200L, displayId: Int = -1): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGestureSuspend(buildGesture(stroke, displayId))
    }

    /**
     * 从 ([startX], [startY]) 滑动到 ([endX], [endY])，持续 [durationMs] 毫秒。
     * 可用于滚动列表、拖拽、下拉通知栏等。
     */
    suspend fun scroll(startX: Float, startY: Float, endX: Float, endY: Float,
                       durationMs: Long = 300L, displayId: Int = -1): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGestureSuspend(buildGesture(stroke, displayId))
    }

    /**
     * 构建手势描述；当 [displayId] &gt;= 0 且系统支持（API 31+，Android 12）时，
     * 通过 GestureDescription.Builder#setDisplayId 将手势绑定到指定 display，
     * 否则回退到默认显示屏（AccessibilityService#dispatchGesture 始终作用于默认屏）。
     */
    private fun buildGesture(stroke: GestureDescription.StrokeDescription, displayId: Int): GestureDescription {
        val builder = GestureDescription.Builder().addStroke(stroke)
        if (displayId >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setDisplayId(displayId)
        } else if (displayId >= 0) {
            Log.w(TAG, "buildGesture: displayId=$displayId requires API 31+ (current=${Build.VERSION.SDK_INT}); falling back to default display")
        }
        return builder.build()
    }

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(g: GestureDescription) {
                    Log.w(TAG, "Gesture cancelled")
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched && cont.isActive) {
                Log.w(TAG, "dispatchGesture returned false")
                cont.resume(false)
            }
        }

    // ── 文字输入 ──────────────────────────────────────────────────────────

    /**
     * 向当前焦点输入框写入 [text]。
     * 若需要向特定节点写入，请使用 NativeAccessibilityMcpTools 的 native_input_to_node 工具。
     */
    fun inputText(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
            Log.w(TAG, "inputText: no focused input found")
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ── 系统全局按键 ──────────────────────────────────────────────────────

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pressNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ── 截屏 ──────────────────────────────────────────────────────────────

    /**
     * 截取全屏 Bitmap。
     * 优先使用 AccessibilityService.takeScreenshot()（API 30+，需要 canTakeScreenshot=true）；
     * 失败时自动回退到 screencap shell 命令。
     * 返回软件 Bitmap（ARGB_8888），调用方负责 recycle()。
     */
    suspend fun captureScreen(displayId: Int = 0): Bitmap? {
        // 方案一：AccessibilityService takeScreenshot（API 30+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bmp = captureViaAccessibility(displayId)
            if (bmp != null) return bmp
            Log.w(TAG, "captureViaAccessibility failed, falling back to screencap")
        }
        // 方案二：screencap shell 命令（兜底，无需特殊权限）
        return captureViaScreencap(displayId)
    }

    private suspend fun captureViaAccessibility(displayId: Int): Bitmap? =
        suspendCancellableCoroutine { cont ->
            takeScreenshot(
                displayId,
                Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBuffer = screenshot.getHardwareBuffer()
                        val colorSpace = screenshot.getColorSpace()
                        val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        try { hwBuffer.close() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(bmp)
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed: errorCode=$errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }

    private fun captureViaScreencap(displayId: Int = 0): Bitmap? {
        val tmpFile = File(cacheDir, "screencap_tmp.png")
        return try {
            // displayId>0 时附加 -l <displayId>；老版本 screencap 不支持 -l 会失败，由外层兜底回退
            val cmd = if (displayId > 0)
                arrayOf("screencap", "-l", displayId.toString(), "-p", tmpFile.absolutePath)
            else
                arrayOf("screencap", "-p", tmpFile.absolutePath)
            val proc = Runtime.getRuntime().exec(cmd)
            proc.waitFor()
            if (tmpFile.exists() && tmpFile.length() > 0) {
                BitmapFactory.decodeFile(tmpFile.absolutePath)
            } else {
                Log.w(TAG, "captureViaScreencap: file empty or missing")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureViaScreencap error: ${e.message}")
            null
        } finally {
            try { tmpFile.delete() } catch (_: Exception) {}

        }
    }
}
