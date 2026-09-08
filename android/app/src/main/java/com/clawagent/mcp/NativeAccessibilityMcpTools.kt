package com.clawagent.mcp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.clawagent.accessibility.NativeAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * NativeAccessibilityMcpTools — 基于 Android AccessibilityService 的原生 UI 控制工具集
 *
 * 与现有 McpTools（WebView/CDP）互补：
 * - McpTools：操控 WebView 内 Web 内容（H5、小程序等）
 * - NativeAccessibilityMcpTools：操控任意原生 Android App 的 UI
 *
 * 工具列表：
 * ┌─ 感知 ─────────────────────────────────────────────────┐
 * │ native_get_foreground_app — 获取当前前台 App 包名        │
 * │ native_get_ui_tree        — 获取任意 App 的 UI 节点树    │
 * │ native_screenshot         — 全屏截图（系统级，非 WebView）│
 * ├─ 交互 ─────────────────────────────────────────────────┤
 * │ native_click              — 坐标点击                    │
 * │ native_click_node         — 按 ref 点击节点              │
 * │ native_long_click         — 长按                        │
 * │ native_scroll             — 手势滑动/滚动                │
 * │ native_input_text         — 向焦点输入框输入文字          │
 * │ native_input_to_node      — 向指定节点设置文字            │
 * │ native_open_miniprogram   — 打开支付宝小程序（按名称/appId）│
 * ├─ 系统 ─────────────────────────────────────────────────┤
 * │ native_press_back         — 返回键                      │
 * │ native_press_home         — Home 键                     │
 * └─────────────────────────────────────────────────────────┘
 *
 * 所有工具名以 "native_" 前缀区分，McpServer 据此路由到本类。
 *
 * 节点引用（[ref=nN]）：
 * 调用 native_get_ui_tree 后，每个节点分配一个 [ref=nN] token，
 * 存储于内部 nodeRefMap，可传给 native_click_node / native_input_to_node 使用。
 * 下次调用 native_get_ui_tree 时旧 ref 失效并自动回收。
 */
class NativeAccessibilityMcpTools {

    companion object {
        private const val TAG = "NativeA11yMcpTools"
    }

    /**
     * 节点引用表 — key: "n1"/"n2"/...，value: AccessibilityNodeInfo
     * 在下次 native_get_ui_tree 调用前持有节点引用（不 recycle）。
     */
    private val nodeRefMap = mutableMapOf<String, AccessibilityNodeInfo>()
    private var nodeCounter = 0

    // ── Tool 定义 ─────────────────────────────────────────────────────────

    fun getToolDefinitions(): JsonArray = JsonArray().apply {

        // ── 感知 ──────────────────────────────────────────────────────────

        add(createToolDef(
            "native_get_foreground_app",
            "Get the package name of the currently visible foreground Android application. " +
            "Use this to identify which app is on screen before calling native_get_ui_tree.",
            JsonObject()
        ))

        add(createToolDef(
            "native_get_ui_tree",
            "Capture the accessibility UI tree of the current or specified Android app as compact text. " +
            "Each node shows: className, text/description (quoted), resource-id [res=...], " +
            "interaction flags [clickable/scrollable/editable/...], bounding box [box=x,y,w,h], " +
            "and a node ref token [ref=nN]. " +
            "[ref=nN] tokens can be passed to native_click_node or native_input_to_node. " +
            "Refs are valid until the next native_get_ui_tree call. " +
            "Omit packageName to capture the top foreground window. " +
            "Pass displayId to restrict the search to a specific logical display. " +
            "On virtual displays the app may run under a different package name.",
            JsonObject().apply {
                add("properties", JsonObject().apply {
                    add("packageName", strProp(
                        "Optional: target app package name (e.g. 'com.android.settings'). " +
                        "Omit to capture the current foreground app."
                    ))
                    add("displayId", numProp(
                        "Optional: restrict search to a specific logical display id. " +
                        "Default: search all displays."
                    ))
                    add("maxDepth", numProp(
                        "Optional: maximum tree depth to traverse. Default: unlimited (full tree)."
                    ))
                    add("boxes", boolProp(
                        "Optional: include bounding boxes as [box=left,top,width,height]. Default true."
                    ))
                })
                add("required", jsonArrayOf())
            }
        ))

        add(createToolDef(
            "native_screenshot",
            "Take a screenshot at the system level (works for any app, not just WebView). " +
            "Returns base64-encoded PNG. Requires the NativeAccessibilityService to be enabled. " +
            "Captures the full screen by default; pass left/top/right/bottom to crop. " +
            "Pass displayId to capture a specific logical display; defaults to 0 (default display).",
            JsonObject().apply {
                add("properties", JsonObject().apply {
                    add("displayId", numProp("Optional: logical display id to capture. Default 0 (default display)."))
                    add("left",   numProp("Optional: left edge of crop region in screen pixels (inclusive). Default full-screen."))
                    add("top",    numProp("Optional: top edge of crop region in screen pixels (inclusive). Default full-screen."))
                    add("right",  numProp("Optional: right edge of crop region in screen pixels (exclusive). Default full-screen."))
                    add("bottom", numProp("Optional: bottom edge of crop region in screen pixels (exclusive). Default full-screen."))
                })
                add("required", jsonArrayOf())
            }
        ))

        // ── 交互 ──────────────────────────────────────────────────────────

        add(createToolDef(
            "native_click",
            "Tap at screen coordinates on the native Android UI. " +
            "Coordinates are physical screen pixels. " +
            "Use native_get_ui_tree with boxes=true to discover element coordinates. " +
            "Pass displayId to target a specific logical display; " +
            "REQUIRED whenever the coordinates come from a native_screenshot taken with a non-zero displayId — " +
            "otherwise the tap is dispatched to the default display (0) and silently misses the target screen.",
            JsonObject().apply {
                add("properties", JsonObject().apply {
                    add("x", numProp("Screen X coordinate in pixels"))
                    add("y", numProp("Screen Y coordinate in pixels"))
                    add("displayId", numProp(
                        "Optional: logical display id to target (requires API 31+). Default: 0 (default display). " +
                        "Use the same displayId passed to native_screenshot."
                    ))
                })
                add("required", jsonArrayOf("x", "y"))
            }
        ))

        add(createToolDef(
            "native_click_node",
            "Click a specific UI node by its [ref=nN] token from native_get_ui_tree. " +
            "More reliable than coordinate-based click when the node is scrolled or repositioned.",
            JsonObject().apply {
                add("properties", JsonObject().apply {
                    add("ref", strProp(
                        "Node ref token from the last native_get_ui_tree, e.g. \"n5\""
                    ))
                })
                add("required", jsonArrayOf("ref"))
            }
        ))

        add(createToolDef(
            "native_long_click",
            "Long press at screen coordinates on the native Android UI.",
            JsonObject().apply {
                add("properties", JsonObject().apply {
                    add("x", numProp("Screen X coordinate in pixels"))
                    add("y", numProp("Screen Y coordinate in pixels"))
                    add("durationMs", numProp(
                        "Optional: press duration in milliseconds. Default 1200."
                    ))
                    add("displayId", numProp(
                        "Optional: logical display id to target (requires API 31+). Default: 0 (default display)."
                    ))
                })
                add("required", jsonArrayOf("x", "y"))
            }
        ))

        add(createToolDef(
            "native_scroll",
            "Perform a swipe / scroll gesture on the native Android UI. " +
            "To scroll down (reveal content below): startY > endY. " +
            "To scroll up (reveal content above): startY < endY.",
            JsonObject().apply {
                add("properties", JsonObject().apply {
                    add("startX", numProp("Swipe start X coordinate in pixels"))
                    add("startY", numProp("Swipe start Y coordinate in pixels"))
                    add("endX",   numProp("Swipe end X coordinate in pixels"))
                    add("endY",   numProp("Swipe end Y coordinate in pixels"))
                    add("durationMs", numProp(
                        "Optional: swipe duration in milliseconds. Default 300."
                    ))
                    add("displayId", numProp(
                        "Optional: logical display id to target (requires API 31+). Default: 0 (default display)."
                    ))
                })
                add("required", jsonArrayOf("startX", "startY", "endX", "endY"))
            }
        ))

        add(createToolDef(
            "native_input_text",
            "Type text into the currently focused input field in any Android app. " +
            "First tap the target input to focus it (native_click), then call this tool.",
            JsonObject().apply {
                add("properties", JsonObject().apply {
                    add("text", strProp("Text to type into the focused input"))
                })
                add("required", jsonArrayOf("text"))
            }
        ))

        add(createToolDef(
            "native_input_to_node",
            "Set text directly on a specific input node by its [ref=nN] token from native_get_ui_tree. " +
            "Automatically focuses the node before setting text.",
            JsonObject().apply {
                add("properties", JsonObject().apply {
                    add("ref",  strProp("Node ref from native_get_ui_tree, e.g. \"n3\""))
                    add("text", strProp("Text to set on the node"))
                })
                add("required", jsonArrayOf("ref", "text"))
            }
        ))

        // ── 应用启动 ──────────────────────────────────────────────────────

        add(createToolDef(
            "native_launch_app",
            "Launch an Android app by its package name. " +
            "Uses the system launcher intent (getLaunchIntentForPackage) to start the app's main activity. " +
            "Works for any installed app, e.g. 'com.apple.android.music' to open Apple Music. " +
            "Returns success=true if the intent was dispatched, or an error message if the package is not found.",
            JsonObject().apply {
                add("properties", JsonObject().apply {
                    add("packageName", strProp(
                        "The package name of the app to launch, e.g. 'com.apple.android.music'"
                    ))
                })
                add("required", jsonArrayOf("packageName"))
            }
        ))

        // ── 系统按键 ──────────────────────────────────────────────────────

        add(createToolDef(
            "native_press_back",
            "Press the Android system Back button. Use to dismiss dialogs, go back in navigation, " +
            "or return from a native app to the previous screen.",
            JsonObject()
        ))

        add(createToolDef(
            "native_press_home",
            "Press the Android system Home button. Returns to the home screen.",
            JsonObject()
        ))
    }

    // ── Tool 分发 ─────────────────────────────────────────────────────────

    suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
        val svc = NativeAccessibilityService.getInstance()
            ?: return JsonObject().apply {
                addProperty("error",
                    "NativeAccessibilityService is not running. " +
                    "Please enable 'ClawAgent Native Control' in Settings › Accessibility, " +
                    "or the service will be auto-enabled on next app start if WRITE_SECURE_SETTINGS is granted.")
                addProperty("available", false)
            }

        return when (name) {
            "native_get_foreground_app"  -> toolGetForegroundApp(svc)
            "native_get_ui_tree"         -> toolGetUiTree(svc, arguments)
            "native_screenshot"          -> toolScreenshot(svc, arguments)
            "native_click"               -> toolClick(svc, arguments)
            "native_click_node"          -> toolClickNode(arguments)
            "native_long_click"          -> toolLongClick(svc, arguments)
            "native_scroll"              -> toolScroll(svc, arguments)
            "native_input_text"          -> toolInputText(svc, arguments)
            "native_input_to_node"       -> toolInputToNode(arguments)
            "native_launch_app"          -> toolLaunchApp(svc, arguments)
            "native_press_back"          -> JsonObject().apply { addProperty("success", svc.pressBack()) }
            "native_press_home"          -> JsonObject().apply { addProperty("success", svc.pressHome()) }
            else -> throw IllegalArgumentException("Unknown native tool: $name")
        }
    }

    /** true 说明 AccessibilityService 当前已连接 */
    fun isAvailable(): Boolean = NativeAccessibilityService.getInstance() != null

    /** 释放节点引用（McpServer 停止时调用） */
    fun cleanup() {
        recycleNodes()
    }

    // ── 工具实现 ──────────────────────────────────────────────────────────

    private fun toolGetForegroundApp(svc: NativeAccessibilityService): JsonObject {
        val pkg = svc.getForegroundPackage()
        return JsonObject().apply {
            if (pkg != null) addProperty("packageName", pkg)
            else addProperty("error", "Could not determine foreground app")
        }
    }

    private suspend fun toolGetUiTree(svc: NativeAccessibilityService, args: JsonObject): JsonObject {
        val packageName  = args.get("packageName")?.asString
        val displayId     = args.get("displayId")?.let { if (it.isJsonNull) null else it.asInt }
        // 默认不限深度；调用方可传 maxDepth 截断
        val maxDepth     = args.get("maxDepth")?.asInt ?: Int.MAX_VALUE
        val includeBoxes = args.get("boxes")?.asBoolean ?: true

        // 释放上次的节点引用
        recycleNodes()

        val root = svc.findWindowRoot(packageName, displayId)
            ?: return JsonObject().apply {
                val base = "Window not found" + if (packageName != null) " for: $packageName" else
                    " (no foreground window accessible)"
                addProperty("error", base + (displayId?.let { " on displayId=$it" } ?: ""))
            }

        val sb = StringBuilder()
        try {
            walkNode(root, 0, maxDepth, includeBoxes, sb)
        } catch (e: Exception) {
            Log.e(TAG, "walkNode error", e)
        }

        val foregroundPkg = packageName ?: svc.getForegroundPackage()
        return JsonObject().apply {
            addProperty("tree", sb.toString())
            addProperty("totalNodes", nodeCounter)
            foregroundPkg?.let { addProperty("packageName", it) }
            displayId?.let { addProperty("displayId", it) }
            addProperty("hint",
                "Use [ref=nN] tokens with native_click_node or native_input_to_node. " +
                "Refs expire on next native_get_ui_tree call.")
        }
    }

    /**
     * 递归遍历 AccessibilityNodeInfo 树，将每个节点输出为一行文本，
     * 并存入 nodeRefMap（不立即 recycle — 统一在下次 get_ui_tree 时 recycle）。
     */
    private fun walkNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        boxes: Boolean,
        sb: StringBuilder
    ) {
        if (depth > maxDepth) {
            // 节点超出 maxDepth，持有引用以便统一 recycle，但递归也要回收其子孙
            nodeRefMap["n${++nodeCounter}_skipped"] = node
            // 同样需要递归回收子节点（避免 AccessibilityNodeInfo 泄漏）
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walkNode(child, depth + 1, maxDepth, boxes, sb)
            }
            return
        }

        val ref = "n${++nodeCounter}"
        nodeRefMap[ref] = node   // 取得所有权，由 recycleNodes() 负责回收

        val indent = "  ".repeat(depth)
        val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
        val text = node.text?.toString()
            ?.replace("\n", "\\n")?.trim()?.take(120)
        val contentDesc = node.contentDescription?.toString()
            ?.replace("\n", "\\n")?.trim()?.take(120)
        val resourceId = node.viewIdResourceName?.substringAfter('/')

        sb.append(indent).append("- ").append(className)

        // 文本 / 描述
        val label = text?.takeIf { it.isNotBlank() } ?: contentDesc?.takeIf { it.isNotBlank() }
        if (label != null) sb.append(" \"${label.replace("\"", "\\\"")}\"")

        // Resource ID
        if (!resourceId.isNullOrBlank()) sb.append(" [res=$resourceId]")

        // 交互属性
        buildList {
            if (node.isClickable)     add("clickable")
            if (node.isLongClickable) add("long-clickable")
            if (node.isScrollable)    add("scrollable")
            if (node.isEditable)      add("editable")
            if (node.isCheckable)     add(if (node.isChecked) "checked" else "unchecked")
            if (!node.isEnabled)      add("disabled")
            if (node.isFocused)       add("focused")
        }.takeIf { it.isNotEmpty() }?.let { sb.append(" [${it.joinToString(",")}]") }

        // 包围盒
        if (boxes) {
            val r = Rect()
            node.getBoundsInScreen(r)
            sb.append(" [box=${r.left},${r.top},${r.width()},${r.height()}]")
        }

        sb.append(" [ref=$ref]\n")

        // 递归处理子节点（getChild 返回的节点所有权归调用方，存入 refMap 统一管理）
        val childCount = node.childCount
        if (childCount > 0 && depth == maxDepth) {
            // 当前节点已在 maxDepth，子节点会被截断，输出提示行让调用方知晓
            val childIndent = "  ".repeat(depth + 1)
            sb.append("$childIndent[... $childCount children hidden — increase maxDepth to see them]\n")
        }
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            walkNode(child, depth + 1, maxDepth, boxes, sb)
        }
    }

    private fun recycleNodes() {
        nodeRefMap.values.forEach {
            try { it.recycle() } catch (_: Exception) {}
        }
        nodeRefMap.clear()
        nodeCounter = 0
    }

    private suspend fun toolScreenshot(svc: NativeAccessibilityService, args: JsonObject): JsonObject {
        val displayId = args.get("displayId")?.asInt ?: 0
        val fullBmp: Bitmap = svc.captureScreen(displayId)
            ?: return JsonObject().apply {
                addProperty("error", "Screenshot failed — check that canTakeScreenshots=true in service config and API >= 30")
            }

        // 可选区域裁剪：left/top/right/bottom 均提供时执行裁剪，否则默认全屏
        val left   = args.get("left")?.asInt   ?: 0
        val top    = args.get("top")?.asInt    ?: 0
        val right  = args.get("right")?.asInt  ?: Int.MAX_VALUE
        val bottom = args.get("bottom")?.asInt ?: Int.MAX_VALUE

        // 裁剪：将坐标 clamp 到 Bitmap 实际范围内，避免越界
        val bmpW = fullBmp.width
        val bmpH = fullBmp.height
        val l = left.coerceIn(0, bmpW)
        val t = top.coerceIn(0, bmpH)
        val r = right.coerceIn(l, bmpW)
        val b = bottom.coerceIn(t, bmpH)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) {
            fullBmp.recycle()
            return JsonObject().apply {
                addProperty("error", "Crop region is empty or out of bounds: " +
                    "requested=($left,$top,$right,$bottom), bitmap=(${bmpW}x${bmpH})")
            }
        }
        val bmp = Bitmap.createBitmap(fullBmp, l, t, w, h)
        // createBitmap 在整图复制时可能返回同一实例，避免重复 recycle
        if (bmp !== fullBmp) fullBmp.recycle()

        // 下采样到目标 display 的逻辑坐标系，使返回的 PNG 像素坐标与 native_click 的逻辑坐标 1:1 对应。
        // 某些设备（高 DPI / 虚拟屏）上 takeScreenshot 返回的物理像素与 dispatchGesture 使用的
        // 逻辑坐标不一致，需要缩放对齐，否则 LLM 识别的坐标会整体偏移。
        val logicalSize = resolveLogicalDisplaySize(svc, displayId)
        val scaledBmp = if (logicalSize != null) {
            val (lw, lh) = logicalSize
            if (lw > 0 && lh > 0 && (bmp.width != lw || bmp.height != lh)) {
                val scaled = Bitmap.createScaledBitmap(bmp, lw, lh, true)
                if (scaled !== bmp) bmp.recycle()
                scaled
            } else bmp
        } else bmp

        val b64 = withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use { baos ->
                scaledBmp.compress(Bitmap.CompressFormat.PNG, 90, baos)
                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            }
        }
        try { scaledBmp.recycle() } catch (_: Exception) {}
        return JsonObject().apply {
            addProperty("type", "image")
            addProperty("data", b64)
            addProperty("mimeType", "image/png")
        }
    }

    /**
     * 解析指定 displayId 的逻辑尺寸（用于把截图缩到与 native_click 一致的坐标系）。
     * 优先用 DisplayManager.getDisplay(displayId).getRealSize()；拿不到（如虚拟屏 API 限制）时
     * 退到 Display.getMode().getPhysicalWidth/Height。
     * 返回 null 表示无法解析（保留原图不动，不强行缩放）。
     */
    private fun resolveLogicalDisplaySize(svc: NativeAccessibilityService, displayId: Int): Pair<Int, Int>? {
        return try {
            val dm = svc.getSystemService(DisplayManager::class.java)
                ?: return null
            val display = dm.getDisplay(displayId) ?: return null
            val point = Point()
            display.getRealSize(point)
            if (point.x > 0 && point.y > 0) Pair(point.x, point.y)
            else {
                val mode = display.mode
                val mw = mode?.physicalWidth ?: 0
                val mh = mode?.physicalHeight ?: 0
                if (mw > 0 && mh > 0) Pair(mw, mh) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveLogicalDisplaySize failed for displayId=$displayId: ${e.message}")
            null
        }
    }

    private suspend fun toolClick(svc: NativeAccessibilityService, args: JsonObject): JsonObject {
        val x = args.get("x")?.asFloat ?: return missingParam("x")
        val y = args.get("y")?.asFloat ?: return missingParam("y")
        val displayId = args.get("displayId")?.asInt ?: -1
        return JsonObject().apply { addProperty("success", svc.click(x, y, displayId)) }
    }

    private fun toolClickNode(args: JsonObject): JsonObject {
        val ref  = args.get("ref")?.asString ?: return missingParam("ref")
        val node = nodeRefMap[ref] ?: return JsonObject().apply {
            addProperty("error", "Node ref '$ref' not found. Call native_get_ui_tree first.")
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return JsonObject().apply { addProperty("success", ok) }
    }

    private suspend fun toolLongClick(svc: NativeAccessibilityService, args: JsonObject): JsonObject {
        val x   = args.get("x")?.asFloat ?: return missingParam("x")
        val y   = args.get("y")?.asFloat ?: return missingParam("y")
        val dur = args.get("durationMs")?.asLong ?: 1200L
        val displayId = args.get("displayId")?.asInt ?: -1
        return JsonObject().apply { addProperty("success", svc.longClick(x, y, dur, displayId)) }
    }

    private suspend fun toolScroll(svc: NativeAccessibilityService, args: JsonObject): JsonObject {
        val sx  = args.get("startX")?.asFloat ?: return missingParam("startX")
        val sy  = args.get("startY")?.asFloat ?: return missingParam("startY")
        val ex  = args.get("endX")?.asFloat   ?: return missingParam("endX")
        val ey  = args.get("endY")?.asFloat   ?: return missingParam("endY")
        val dur = args.get("durationMs")?.asLong ?: 300L
        val displayId = args.get("displayId")?.asInt ?: -1
        return JsonObject().apply { addProperty("success", svc.scroll(sx, sy, ex, ey, dur, displayId)) }
    }

    private fun toolInputText(svc: NativeAccessibilityService, args: JsonObject): JsonObject {
        val text = args.get("text")?.asString ?: return missingParam("text")
        return JsonObject().apply { addProperty("success", svc.inputText(text)) }
    }

    private fun toolInputToNode(args: JsonObject): JsonObject {
        val ref  = args.get("ref")?.asString  ?: return missingParam("ref")
        val text = args.get("text")?.asString ?: return missingParam("text")
        val node = nodeRefMap[ref] ?: return JsonObject().apply {
            addProperty("error", "Node ref '$ref' not found. Call native_get_ui_tree first.")
        }
        // 先聚焦再写入
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        return JsonObject().apply {
            addProperty("success", ok)
            if (!ok) addProperty("hint", "ACTION_SET_TEXT failed — try native_input_text after tapping the field")
        }
    }

    /**
     * 通过包名启动应用。
     * 优先使用 getLaunchIntentForPackage（标准 MAIN/LAUNCHER），
     * 若返回 null（某些车机定制应用无标准 launcher），则回退到 queryIntentActivities 查找。
     */
    private fun toolLaunchApp(svc: NativeAccessibilityService, args: JsonObject): JsonObject {
        val packageName = args.get("packageName")?.asString ?: return missingParam("packageName")
        val pm = svc.packageManager

        // 方式 1：标准 launcher intent
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (launchIntent != null) {
            return try {
                svc.startActivity(launchIntent)
                Log.i(TAG, "Launched $packageName via getLaunchIntentForPackage")
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("packageName", packageName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch $packageName", e)
                JsonObject().apply {
                    addProperty("success", false)
                    addProperty("error", "startActivity failed: ${e.message}")
                }
            }
        }

        // 方式 2：查询该包名下任意 MAIN/LAUNCHER activity
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        @Suppress("DEPRECATION")
        val resolveInfoList = pm.queryIntentActivities(mainIntent, 0)
        val activityInfo = resolveInfoList.firstOrNull()?.activityInfo
        if (activityInfo != null) {
            val fallbackIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(activityInfo.packageName, activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                svc.startActivity(fallbackIntent)
                Log.i(TAG, "Launched $packageName via queryIntentActivities -> ${activityInfo.name}")
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("packageName", packageName)
                    addProperty("launchedActivity", activityInfo.name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed fallback launch $packageName", e)
                JsonObject().apply {
                    addProperty("success", false)
                    addProperty("error", "Fallback startActivity failed: ${e.message}")
                }
            }
        }

        // 包名不存在或未安装
        return JsonObject().apply {
            addProperty("success", false)
            addProperty("error", "Package '$packageName' not found or has no launchable activity. " +
                "Make sure the app is installed on the device.")
        }
    }

    // ── Prop helpers（与 McpTools 保持一致）──────────────────────────────

    private fun createToolDef(name: String, description: String, inputSchema: JsonObject): JsonObject {
        if (!inputSchema.has("type")) inputSchema.addProperty("type", "object")
        return JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("inputSchema", inputSchema)
        }
    }

    private fun strProp(description: String) = JsonObject().apply {
        addProperty("type", "string"); addProperty("description", description)
    }
    private fun numProp(description: String) = JsonObject().apply {
        addProperty("type", "number"); addProperty("description", description)
    }
    private fun boolProp(description: String) = JsonObject().apply {
        addProperty("type", "boolean"); addProperty("description", description)
    }
    private fun jsonArrayOf(vararg items: String) = JsonArray().apply { items.forEach { add(it) } }

    private fun missingParam(name: String) = JsonObject().apply {
        addProperty("error", "Missing required parameter: $name")
    }
}
