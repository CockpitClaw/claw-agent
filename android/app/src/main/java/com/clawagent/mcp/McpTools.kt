package com.clawagent.mcp

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.clawagent.cdp.WebViewBridge
import com.clawagent.mcp.browser.CdpBrowserBridge
import com.clawagent.mcp.browser.DevToolsDiscovery
import com.clawagent.util.LogUtil
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MCP Tools — tool definitions and implementations.
 *
 * Supports controlling both internal WebView pages and external browser app pages.
 *
 * Element references
 * ------------------
 * `take_snapshot` assigns [ref=eN] tokens to every visible element via computed-style
 * analysis. All interaction tools accept a `target` parameter that is either one of
 * these ref strings (e.g. "e7") or a plain CSS selector as a fallback.
 * `take_snapshot_aria` is the legacy ARIA-based snapshot for sites with proper markup.
 *
 * Snapshot granularity
 * --------------------
 * The snapshot returns compact ARIA-formatted text instead of a raw JSON DOM
 * tree.  Optional parameters control depth, subtree scoping, and bounding-box
 * inclusion so the agent can progressively request more detail without
 * receiving more data than it needs up-front.
 */
class McpTools(private val bridge: WebViewBridge, private val apkPath: String = "") {
    companion object {
        private const val TAG = "McpTools"
        private const val BROWSER_PAGE_ID_START = 1000

        // Fallback location when device mode has no GPS signal
        private const val GEO_FALLBACK_LAT = 0.0
        private const val GEO_FALLBACK_LON = 0.0

        // Preset User-Agent strings
        private const val UA_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"
        private const val UA_WIN =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"
        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 11; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

        // Script injected at document creation to patch JS touch-detection properties
        private const val DESKTOP_NAVIGATOR_PATCH =
            "Object.defineProperty(navigator,'maxTouchPoints',{get:()=>0,configurable:true});" +
            "delete window.ontouchstart;" +
            "Object.defineProperty(window,'TouchEvent',{get:()=>undefined,configurable:true});"
    }

    private val gson = Gson()
    private val pages = mutableMapOf<Int, PageInfo>()
    private val discovery = DevToolsDiscovery()
    private val browserBridges = mutableMapOf<Int, CdpBrowserBridge>()
    private val browserPageMap = mutableMapOf<Int, DevToolsDiscovery.BrowserPageInfo>()

    // Tracks the Page.addScriptToEvaluateOnNewDocument identifier for navigator patch (touch/desktop)
    @Volatile private var navigatorPatchScriptId: String? = null

    // ── 真实 CDP 通道（进程内直连 WebView DevTools socket）─────────────────
    // 懒初始化：首次需要时在 IO 线程建立，失败则保持 null，所有用到它的地方回退到 JS。
    private var cdpChannel: InternalCdpChannel? = null
    private var cdpInitAttempted = false

    private suspend fun cdp(): InternalCdpChannel? {
        if (cdpInitAttempted) return cdpChannel
        cdpInitAttempted = true
        val ch = InternalCdpChannel(android.os.Process.myPid())
        val ok = withContext(Dispatchers.IO) { ch.init() }
        cdpChannel = if (ok) ch else null
        if (!ok) {
            LogUtil.w(TAG, "CDP channel init failed, using JS fallback")
        } else {
            // WebView 已通过 layout 尺寸缩为半屏（CSS viewport = physW/viewportZoom），
            // 此处覆盖 devicePixelRatio=viewportZoom，让地图等 canvas SDK 创建 HiDPI 画布。
            try {
                val dpr = bridge.viewportZoom
                val script = "Object.defineProperty(window,'devicePixelRatio',{get:function(){return $dpr;},configurable:true});"
                ch.addScriptToEvaluateOnNewDocument(script)
                LogUtil.i(TAG, "DPR=$dpr script registered")
            } catch (e: Exception) {
                LogUtil.w(TAG, "DPR script injection failed: ${e.message}")
            }
        }
        return cdpChannel
    }

    // CDP 健康检查：任何 CDP 操作失败后调用此函数，让下次重试走回退路径
    private fun invalidateCdp() {
        cdpChannel?.close()
        cdpChannel = null
        cdpInitAttempted = false
    }

    /** McpServer 启动时调用，提前建立 CDP 通道。 */
    suspend fun initCdp() { cdp() }

    enum class PageSource { INTERNAL, BROWSER }

    data class PageInfo(
        val id: Int,
        var url: String,
        var title: String,
        var selected: Boolean = false,
        val source: PageSource = PageSource.INTERNAL
    )

    init {
        pages[1] = PageInfo(1, bridge.currentUrl, bridge.pageTitle, true)
    }

    // ── Tool definitions ──────────────────────────────────────────────────

    fun getToolDefinitions(): JsonArray {
        return JsonArray().apply {

            // ── Page management ──────────────────────────────────────────

            add(createToolDef("list_pages", "List all open pages (internal WebView + external browser app)", JsonObject()))

            add(createToolDef(
                "set_user_agent",
                "Override the browser User-Agent and related navigator properties. " +
                "mode=mac (default): macOS arm64 Chrome — appears as desktop Mac browser, no touch. " +
                "mode=win: Windows 10 x64 Chrome. " +
                "mode=mobile: restore original Android WebView UA. " +
                "Applies to HTTP headers, navigator.userAgent, navigator.platform, navigator.userAgentData, " +
                "navigator.maxTouchPoints, and CSS pointer/hover media features. Call BEFORE navigating.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("mode", strProp("\"mac\" | \"win\" | \"mobile\" (default: mac)"))
                    })
                    add("required", jsonArrayOf())
                }
            ))

            add(createToolDef(
                "set_geolocation_policy",
                "Configure how the WebView responds to geolocation (navigator.geolocation) requests. " +
                "Call BEFORE navigating to a page that needs location. " +
                "mode=deny: block all requests (default). " +
                "mode=device: pass through to real Android device location (requires location permission on device). " +
                "mode=mock: return the specified lat/lon coordinates.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("mode",     strProp("Policy: \"deny\" | \"device\" | \"mock\""))
                        add("lat",      numProp("Latitude — required when mode=mock"))
                        add("lon",      numProp("Longitude — required when mode=mock"))
                        add("accuracy", numProp("Accuracy in metres (optional, default 50) — only used when mode=mock"))
                    })
                    add("required", jsonArrayOf("mode"))
                }
            ))

            add(createToolDef("navigate_page", "Navigate a page to a new URL",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("url", strProp("URL to navigate to"))
                    })
                    add("required", jsonArrayOf("pageId", "url"))
                }))

            // ── Perception ───────────────────────────────────────────────

            add(createToolDef(
                "take_snapshot_aria",
                "Capture an accessibility snapshot of the page as compact ARIA text. " +
                "Each visible element is assigned a [ref=eN] token stored in the page's " +
                "element registry and usable by all interaction tools. " +
                "Only useful on sites with proper ARIA markup; use take_snapshot for div-soup sites. " +
                "Use `target` to scope the snapshot to a subtree, `depth` to limit " +
                "verbosity, and `boxes` to include viewport bounding boxes.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("target", strProp(
                            "Optional: element ref (e.g. \"e5\") or CSS selector to scope " +
                            "the snapshot to a subtree. Omit for the full page."
                        ))
                        add("depth", numProp(
                            "Optional: maximum depth of the tree to include. Default 8."
                        ))
                        add("boxes", boolProp(
                            "Optional: include each element's viewport bounding box as " +
                            "[box=x,y,w,h]. Default false."
                        ))
                    })
                    add("required", jsonArrayOf("pageId"))
                }))

            add(createToolDef(
                "take_screenshot",
                "Take a screenshot of the page and return it as a base64-encoded PNG image. " +
                "Screenshots cannot be used to obtain element references; use take_snapshot for interactions.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                    })
                    add("required", jsonArrayOf("pageId"))
                }))

            add(createToolDef(
                "take_snapshot",
                "Capture a semantic snapshot of the page by analysing computed styles and layout, " +
                "rather than relying on ARIA attributes. Works well on all sites including div-soup " +
                "(e.g. Chinese e-commerce). Also captures cross-origin iframe content via CDP. " +
                "Returns a pruned tree where every visible node is classified as one of: " +
                "[action] (large clickable, e.g. a buy button), " +
                "[option] (small clickable, e.g. a size/colour chip), " +
                "[content] (prominent text: title, price, label — leaf node with large font or bold weight), " +
                "[img-group:gallery] (a cluster of content images inside the same container). " +
                "Container nodes with no classified descendants are pruned. " +
                "All classified nodes carry a [ref=eN] token usable by click/fill/hover. " +
                "Use `target` to scope to a subtree, `depth` to limit verbosity, " +
                "`boxes` to include bounding boxes.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("target", strProp(
                            "Optional: element ref (e.g. \"e5\") or CSS selector to scope " +
                            "the snapshot to a subtree. Omit for the full page."
                        ))
                        add("depth", numProp(
                            "Optional: maximum depth of the tree to include. Default 12."
                        ))
                        add("boxes", boolProp(
                            "Optional: include each element's viewport bounding box as " +
                            "[box=x,y,w,h]. Default false."
                        ))
                    })
                    add("required", jsonArrayOf("pageId"))
                }))

            // ── Interactions ─────────────────────────────────────────────

            add(createToolDef(
                "click",
                "Click an element or coordinate. " +
                "Option A — pass `target`: a [ref=eN] from the last take_snapshot/take_snapshot_aria or a CSS selector; " +
                "the element is scrolled into view and a native touch is dispatched. " +
                "Option B — pass `x`/`y` (viewport px) and optionally `displayedWidth` to auto-scale: " +
                "realX = x * (viewportWidth / displayedWidth). displayedWidth is the 'displayed at' width " +
                "shown in the read-image hint (usually 2000). Native touch first, synthetic mouse fallback.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("target", strProp(
                            "Element ref from snapshot (e.g. \"e7\") or CSS selector. Mutually exclusive with x/y."
                        ))
                        add("x", numProp("Viewport X coordinate (displayed pixels). Use with displayedWidth for auto-scaling."))
                        add("y", numProp("Viewport Y coordinate (displayed pixels)."))
                        add("displayedWidth", numProp(
                            "Width of the displayed image (e.g. 2000). realX = x * viewportWidth / displayedWidth."
                        ))
                        add("doubleClick", boolProp(
                            "Optional: perform a double-click instead of a single click. Default false."
                        ))
                        add("button", strProp(
                            "Optional: mouse button — \"left\" (default), \"right\", or \"middle\"."
                        ))
                    })
                    add("required", jsonArrayOf("pageId"))
                }))

            add(createToolDef(
                "type",
                "Type text into the focused or targeted editable element by dispatching keyboard events. " +
                "Triggers framework input handlers (React, Vue, etc.). " +
                "Use `fill` when you only need to set the raw value without key events.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("target", strProp(
                            "Element ref (e.g. \"e3\") or CSS selector for the editable element"
                        ))
                        add("text", strProp("Text to type"))
                        add("submit", boolProp(
                            "Optional: press Enter after typing. Default false."
                        ))
                    })
                    add("required", jsonArrayOf("pageId", "target", "text"))
                }))

            add(createToolDef(
                "fill",
                "Directly set the value of an input or textarea and dispatch an input event. " +
                "Faster than `type` but bypasses key-press handlers. " +
                "Use `type` when the page reacts to individual keystrokes.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("target", strProp(
                            "Element ref (e.g. \"e3\") or CSS selector for the input"
                        ))
                        add("value", strProp("Value to fill in"))
                    })
                    add("required", jsonArrayOf("pageId", "target", "value"))
                }))

            add(createToolDef(
                "fill_form",
                "Fill multiple form fields in one call. " +
                "Each entry specifies a `target` (ref or CSS selector) and a `value`.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("fields", JsonObject().apply {
                            addProperty("type", "array")
                            addProperty("description",
                                "Array of {target, value} objects. " +
                                "`target` is an element ref or CSS selector."
                            )
                        })
                    })
                    add("required", jsonArrayOf("pageId", "fields"))
                }))

            add(createToolDef(
                "select_option",
                "Select one or more options in a <select> element.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("target", strProp(
                            "Element ref (e.g. \"e4\") or CSS selector for the <select> element"
                        ))
                        add("values", JsonObject().apply {
                            addProperty("type", "array")
                            addProperty("description",
                                "Array of option values to select. " +
                                "For a single-select element, provide one value."
                            )
                        })
                    })
                    add("required", jsonArrayOf("pageId", "target", "values"))
                }))

            add(createToolDef(
                "hover",
                "Hover the pointer over an element to trigger mouseover/mouseenter handlers.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("target", strProp(
                            "Element ref (e.g. \"e6\") or CSS selector"
                        ))
                    })
                    add("required", jsonArrayOf("pageId", "target"))
                }))

            add(createToolDef(
                "drag",
                "Drag from one element to another (mousedown → mousemove → mouseup).",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("startTarget", strProp(
                            "Source element ref (e.g. \"e2\") or CSS selector"
                        ))
                        add("endTarget", strProp(
                            "Destination element ref (e.g. \"e9\") or CSS selector"
                        ))
                    })
                    add("required", jsonArrayOf("pageId", "startTarget", "endTarget"))
                }))

            add(createToolDef(
                "press_key",
                "Press a keyboard key on the active element. " +
                "Use standard key names such as Enter, Escape, Tab, ArrowDown, Backspace, " +
                "or a single character. " +
                "Special: key=\"Back\" sends the Android system KEYCODE_BACK — use this to return " +
                "from an external app (e.g. after opening Meituan) back to the WebView screen.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("key", strProp(
                            "Key name (e.g. \"Enter\", \"Escape\", \"Tab\", \"a\")"
                        ))
                    })
                    add("required", jsonArrayOf("pageId", "key"))
                }))

            add(createToolDef(
                "upload_file",
                "Upload a file to a file-input element.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("target", strProp(
                            "Element ref (e.g. \"e8\") or CSS selector for the file input"
                        ))
                        add("filePath", strProp("Absolute path to the file on device"))
                    })
                    add("required", jsonArrayOf("pageId", "target", "filePath"))
                }))

            // ── Advanced ──────────────────────────────────────────────────

            add(createToolDef(
                "evaluate_script_in_frame",
                "Execute JavaScript inside a cross-origin iframe by targeting its CDP execution context. " +
                "Useful for reading or interacting with iframes that block normal evaluate_script access " +
                "(e.g. JD payment popup at https://pc-settlement-lite-pro.pf.jd.com). " +
                "Waits up to 8 s for the frame context to appear after the iframe loads.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID (must be an internal WebView page)"))
                        add("frameUrl", strProp(
                            "Origin of the target iframe, e.g. \"https://pc-settlement-lite-pro.pf.jd.com\""
                        ))
                        add("script", strProp("JavaScript expression or IIFE to evaluate inside the iframe"))
                    })
                    add("required", jsonArrayOf("pageId", "frameUrl", "script"))
                }))

            add(createToolDef(
                "evaluate_script",
                "Execute arbitrary JavaScript and return the result. " +
                "If frameUrl is provided (substring match), executes inside the matching cross-origin iframe via CDP instead of the main page.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("script", strProp("JavaScript expression or IIFE to evaluate"))
                        add("frameUrl", strProp("Optional: substring of the iframe URL to target (e.g. 'pf.jd.com'). Requires CDP."))
                    })
                    add("required", jsonArrayOf("pageId", "script"))
                }))

            add(createToolDef(
                "resize_page",
                "Resize the page viewport. (Not supported in Android WebView; returns an error.)",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("width", numProp("Viewport width in pixels"))
                        add("height", numProp("Viewport height in pixels"))
                    })
                    add("required", jsonArrayOf("pageId", "width", "height"))
                }))

            add(createToolDef(
                "handle_dialog",
                "Accept or dismiss a browser dialog (alert, confirm, prompt).",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("accept", boolProp("true to accept, false to dismiss"))
                        add("promptText", strProp(
                            "Optional: text to enter for prompt dialogs"
                        ))
                    })
                    add("required", jsonArrayOf("pageId", "accept"))
                }))

            add(createToolDef(
                "wait_for",
                "Wait until text appears or disappears on the page (including cross-origin iframes), or for a fixed duration.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                        add("text", strProp("Optional: text to wait for to appear"))
                        add("textGone", strProp("Optional: text to wait for to disappear"))
                        add("timeout", numProp("Optional: maximum wait time in ms. Default 30000."))
                    })
                    add("required", jsonArrayOf("pageId"))
                }))

            add(createToolDef(
                "list_frames",
                "List all frames (main page + cross-origin iframes) via CDP Page.getFrameTree. " +
                "Returns [{frameId, url}] for each frame. Use to detect cross-origin iframes and get their frameId.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                    })
                    add("required", jsonArrayOf("pageId"))
                }))

            add(createToolDef(
                "scroll_up",
                "Scroll the page up by a fixed distance using native Android touch events " +
                "(ACTION_DOWN → ACTION_MOVE × N → ACTION_UP, isTrusted=true). " +
                "Works for pages that rely on touchstart/touchmove/touchend listeners.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                    })
                    add("required", jsonArrayOf("pageId"))
                }))

            add(createToolDef(
                "scroll_down",
                "Scroll the page down by a fixed distance using native Android touch events " +
                "(ACTION_DOWN → ACTION_MOVE × N → ACTION_UP, isTrusted=true). " +
                "Works for pages that rely on touchstart/touchmove/touchend listeners.",
                JsonObject().apply {
                    add("properties", JsonObject().apply {
                        add("pageId", numProp("Page ID"))
                    })
                    add("required", jsonArrayOf("pageId"))
                }))

            }
    }

    // ── Tool dispatch ─────────────────────────────────────────────────────

    suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
        LogUtil.d(TAG, "callTool: $name  args=$arguments")
        return when (name) {
            "list_pages"      -> toolListPages()
            "set_user_agent"  -> toolSetUserAgent(arguments)
            "navigate_page"   -> toolNavigatePage(arguments)
            "take_snapshot_aria" -> toolTakeSnapshotAria(arguments)
            "take_screenshot" -> toolTakeScreenshot(arguments)
            "take_snapshot"   -> toolTakeSnapshot(arguments)
            "click"           -> toolClick(arguments)
            "type"            -> toolType(arguments)
            "fill"            -> toolFill(arguments)
            "fill_form"       -> toolFillForm(arguments)
            "select_option"   -> toolSelectOption(arguments)
            "hover"           -> toolHover(arguments)
            "drag"            -> toolDrag(arguments)
            "press_key"       -> toolPressKey(arguments)
            "upload_file"     -> toolUploadFile(arguments)
            "evaluate_script_in_frame" -> toolEvaluateScriptInFrame(arguments)
            "evaluate_script" -> toolEvaluateScript(arguments)
            "resize_page"     -> toolResizePage(arguments)
            "handle_dialog"   -> toolHandleDialog(arguments)
            "wait_for"        -> toolWaitFor(arguments)
            "list_frames"     -> toolListFrames(arguments)
            "scroll_up"       -> toolScrollUp(arguments)
            "scroll_down"     -> toolScrollDown(arguments)
            "set_geolocation_policy" -> toolSetGeolocationPolicy(arguments)
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }

    private suspend fun toolSetUserAgent(args: JsonObject): JsonObject {
        val mode = args.get("mode")?.asString?.lowercase()?.trim() ?: "mac"
        if (mode !in listOf("mac", "win", "mobile")) {
            return JsonObject().apply { addProperty("error", "mode must be mac, win, or mobile") }
        }

        val ch = try { cdp() } catch (_: Exception) { null }
            ?: return JsonObject().apply { addProperty("error", "CDP not available") }

        val (ua, platform, mobile) = when (mode) {
            "win"    -> Triple(UA_WIN,    "Win32",    false)
            "mobile" -> Triple(UA_MOBILE, "Linux",    true)
            else     -> Triple(UA_MAC,    "MacIntel", false)
        }

        ch.setUserAgentOverride(ua, platform, mobile)

        // Remove previous navigator patch script if any
        navigatorPatchScriptId?.let { ch.removeScriptToEvaluateOnNewDocument(it) }
        navigatorPatchScriptId = null

        if (!mobile) {
            // Inject desktop navigator properties: maxTouchPoints=0, remove ontouchstart/TouchEvent
            navigatorPatchScriptId = ch.addScriptToEvaluateOnNewDocument(DESKTOP_NAVIGATOR_PATCH)
            // Apply immediately to the current page as well
            try { ch.evaluate(DESKTOP_NAVIGATOR_PATCH) } catch (_: Exception) {}
            // Override CSS pointer/hover media features so (pointer:coarse) doesn't match
            ch.setEmulatedMediaFeatures(mapOf("pointer" to "fine", "hover" to "hover"))
        } else {
            // Restore touch behaviour for mobile mode
            ch.setEmulatedMediaFeatures(emptyMap())
        }

        return JsonObject().apply {
            addProperty("success", true)
            addProperty("mode", mode)
            addProperty("ua", ua)
        }
    }

    private suspend fun toolSetGeolocationPolicy(args: JsonObject): JsonObject {
        val mode     = args.get("mode")?.asString?.lowercase()?.trim() ?: "deny"
        val lat      = args.get("lat")?.asDouble      ?: 0.0
        val lon      = args.get("lon")?.asDouble      ?: 0.0
        val accuracy = args.get("accuracy")?.asDouble ?: 50.0

        if (mode !in listOf("deny", "device", "mock")) {
            return JsonObject().apply { addProperty("error", "mode must be one of: deny, device, mock") }
        }
        if (mode == "mock" && lat == 0.0 && lon == 0.0) {
            return JsonObject().apply { addProperty("error", "lat and lon are required for mock mode") }
        }

        bridge.setGeolocationPolicy(mode, lat, lon, accuracy)

        val ch = cdp()
        if (ch != null) {
            try {
                when (mode) {
                    "mock" -> ch.setGeolocationOverride(lat, lon, accuracy)
                    "device" -> {
                        val real = bridge.getLastKnownLocation()
                        if (real != null) {
                            ch.setGeolocationOverride(real.first, real.second, 20.0)
                        } else {
                            // No GPS signal — fall back to fixed location via both CDP and JS mock
                            ch.setGeolocationOverride(GEO_FALLBACK_LAT, GEO_FALLBACK_LON, 65.0)
                            bridge.setGeolocationPolicy("mock", GEO_FALLBACK_LAT, GEO_FALLBACK_LON, 65.0)
                            LogUtil.i(TAG, "device mode: no GPS signal, using fallback location ($GEO_FALLBACK_LAT, $GEO_FALLBACK_LON)")
                        }
                    }
                    else -> ch.clearGeolocationOverride()
                }
            } catch (e: Exception) {
                LogUtil.w(TAG, "Emulation geolocation override failed: ${e.message}")
            }
        } else if (mode == "device") {
            // CDP unavailable — check GPS and fall back to JS mock if no signal
            val real = bridge.getLastKnownLocation()
            if (real == null) {
                bridge.setGeolocationPolicy("mock", GEO_FALLBACK_LAT, GEO_FALLBACK_LON, 65.0)
                LogUtil.i(TAG, "device mode (no CDP): no GPS signal, using JS mock fallback ($GEO_FALLBACK_LAT, $GEO_FALLBACK_LON)")
            }
        }

        return JsonObject().apply {
            addProperty("success", true)
            addProperty("mode", mode)
            if (mode == "mock") { addProperty("lat", lat); addProperty("lon", lon) }
        }
    }

    fun cleanup() {
        browserBridges.values.forEach { it.disconnect() }
        browserBridges.clear()
        browserPageMap.clear()
        discovery.stopForwarder()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun parseJsResult(result: String): JsonObject {
        return try {
            val trimmed = result.trim()
            if (trimmed.startsWith("{")) {
                gson.fromJson(trimmed, JsonObject::class.java)
            } else if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                val inner = gson.fromJson(trimmed, String::class.java)
                gson.fromJson(inner, JsonObject::class.java)
            } else {
                JsonObject().apply { addProperty("success", true) }
            }
        } catch (_: Exception) {
            JsonObject().apply { addProperty("success", true) }
        }
    }

    // ── Ref helpers ───────────────────────────────────────────────────────
    // ref 格式：普通主 frame → "eN"；跨域 iframe → "frameId:eN"
    // 主 frame sessionId 约定为 null。

    data class ParsedRef(val sessionId: String?, val localRef: String)

    private fun parseRef(ref: String): ParsedRef {
        val colon = ref.indexOf(':')
        return if (colon > 0 && !ref.startsWith("e")) {
            ParsedRef(ref.substring(0, colon), ref.substring(colon + 1))
        } else {
            ParsedRef(null, ref)
        }
    }

    // 把 walk JS 里的 [ref=eN] 替换为带 frameId 前缀的 [ref=frameId:eN]
    private fun prefixRefs(snapshot: String, frameId: String): String {
        return snapshot.replace(Regex("""\[ref=(e\d+)\]""")) { mr ->
            "[ref=$frameId:${mr.groupValues[1]}]"
        }
    }

    private suspend fun getBridgeForPage(pageId: Int): WebViewBridge {
        val page = pages[pageId] ?: throw IllegalArgumentException("Page not found: $pageId")
        return when (page.source) {
            PageSource.INTERNAL -> bridge
            PageSource.BROWSER -> {
                val existing = browserBridges[pageId]
                if (existing != null && existing.isConnected) return existing
                val bpi = browserPageMap[pageId]
                    ?: throw IllegalStateException("Browser page info not found for $pageId")
                val newBridge = CdpBrowserBridge(bpi)
                if (!newBridge.connect()) {
                    throw IllegalStateException("Failed to connect to browser page: ${bpi.title}")
                }
                browserBridges[pageId] = newBridge
                newBridge
            }
        }
    }

    // ── Page management implementations ──────────────────────────────────

    private suspend fun toolListPages(): JsonObject {
        pages.values.filter { it.source == PageSource.INTERNAL }.forEach { page ->
            page.url = bridge.currentUrl
            page.title = bridge.pageTitle
        }

        try {
            val browserPages = discovery.discoverAllBrowserPages(apkPath)

            val oldBrowserIds = pages.keys.filter { it >= BROWSER_PAGE_ID_START }
            oldBrowserIds.forEach { id ->
                pages.remove(id)
                browserPageMap.remove(id)
            }

            var nextId = BROWSER_PAGE_ID_START
            for (bpi in browserPages) {
                pages[nextId] = PageInfo(nextId, bpi.url, bpi.title, false, PageSource.BROWSER)
                browserPageMap[nextId] = bpi
                nextId++
            }

            val validIds = browserPageMap.keys
            browserBridges.keys.filter { it !in validIds }.forEach { staleId ->
                browserBridges.remove(staleId)?.disconnect()
            }
        } catch (e: Exception) {
            LogUtil.w(TAG, "Browser page discovery failed: ${e.message}")
        }

        val pageList = JsonArray().apply {
            pages.values.sortedBy { it.id }.forEach { page ->
                add(JsonObject().apply {
                    addProperty("id", page.id)
                    addProperty("url", page.url)
                    addProperty("title", page.title)
                    addProperty("selected", page.selected)
                    addProperty("source", page.source.name.lowercase())
                })
            }
        }
        return JsonObject().apply { add("pages", pageList) }
    }

    private suspend fun toolNavigatePage(args: JsonObject): JsonObject {
        val pageId = args.get("pageId")?.asInt ?: throw IllegalArgumentException("Missing pageId")
        val url    = args.get("url")?.asString    ?: throw IllegalArgumentException("Missing url")
        val page   = pages[pageId]                ?: throw IllegalArgumentException("Page not found: $pageId")
        val pageBridge = getBridgeForPage(pageId)
        // 确保 CDP 已初始化并注册了 addScriptToEvaluateOnNewDocument，
        // 然后再导航，使脚本在新页面的 HTML 解析前就已注册。
        cdp()
        val result = pageBridge.navigateAsync(url)
        page.url   = url
        page.title = pageBridge.pageTitle

        return JsonObject().apply {
            addProperty("success", result.success)
            result.errorText?.let { addProperty("error", it) }
        }
    }

    // ── Snapshot implementation ───────────────────────────────────────────

    private suspend fun toolTakeSnapshotAria(args: JsonObject): JsonObject {
        val pageId      = args.get("pageId")?.asInt ?: throw IllegalArgumentException("Missing pageId")
        val depth       = args.get("depth")?.asInt ?: 8
        val target      = args.get("target")?.asString ?: ""
        val boxes       = args.get("boxes")?.asBoolean ?: false
        val pageBridge  = getBridgeForPage(pageId)

        val targetJson   = gson.toJson(target)
        val depthJson    = depth.toString()
        val boxesJs      = if (boxes) "true" else "false"
        val coordScale   = pageBridge.viewportZoom

        // language=JavaScript
        val js = """
            (function() {
                var MAX_DEPTH    = $depthJson;
                var INCLUDE_BOXES = $boxesJs;
                var COORD_SCALE  = $coordScale;
                var targetSpec   = $targetJson;
                var MAX_CHILDREN = 60;

                // Resolve old ref → element before resetting the map.
                var rootEl = document.body;
                if (targetSpec) {
                    var prevMap = window.__mcpRefMap;
                    if (prevMap && prevMap[targetSpec]) {
                        rootEl = prevMap[targetSpec];
                    } else {
                        try { rootEl = document.querySelector(targetSpec) || document.body; } catch(e) {}
                    }
                }

                // Reset the ref registry for this snapshot.
                window.__mcpRefMap = {};
                var counter = 0;

                function implicitRole(el) {
                    var tag  = el.tagName ? el.tagName.toLowerCase() : '';
                    var type = (el.getAttribute && el.getAttribute('type') || '').toLowerCase();
                    if (tag === 'input') {
                        if (type === 'checkbox') return 'checkbox';
                        if (type === 'radio')    return 'radio';
                        if (type === 'submit' || type === 'button' || type === 'reset') return 'button';
                        if (type === 'range')    return 'slider';
                        return 'textbox';
                    }
                    var map = {
                        a:'link', button:'button', select:'combobox', textarea:'textbox',
                        nav:'navigation', main:'main', header:'banner', footer:'contentinfo',
                        aside:'complementary', section:'region', article:'article', form:'form',
                        h1:'heading', h2:'heading', h3:'heading', h4:'heading', h5:'heading', h6:'heading',
                        ul:'list', ol:'list', li:'listitem',
                        img:'img', figure:'figure', dialog:'dialog',
                        table:'table', tr:'row', td:'cell', th:'columnheader',
                        menu:'menu', menuitem:'menuitem', details:'group', summary:'term',
                        label:'label', fieldset:'group', legend:'legend'
                    };
                    return map[tag] || tag || 'generic';
                }

                // Tags whose visible text content is a useful accessible name.
                var TEXT_ROLES = {
                    link:1, button:1, heading:1, listitem:1, cell:1, columnheader:1,
                    label:1, term:1, menuitem:1, option:1
                };

                function accessibleName(el, role) {
                    if (!el.getAttribute) return '';
                    var name = el.getAttribute('aria-label') || '';
                    if (!name) {
                        var lby = el.getAttribute('aria-labelledby');
                        if (lby) {
                            var lel = document.getElementById(lby);
                            if (lel) name = (lel.textContent || '').trim();
                        }
                    }
                    if (!name) name = el.getAttribute('title') || '';
                    if (!name) name = el.getAttribute('placeholder') || '';
                    if (!name) name = el.getAttribute('alt') || '';
                    if (!name && TEXT_ROLES[role]) {
                        name = (el.textContent || '').trim().replace(/\s+/g, ' ').substring(0, 80);
                    }
                    if (!name) {
                        if (el.value) name = el.value.substring(0, 60);
                    }
                    return name;
                }

                function buildLine(el, depth) {
                    var role   = (el.getAttribute && el.getAttribute('role')) || implicitRole(el);
                    var name   = accessibleName(el, role);
                    var ref    = 'e' + (++counter);
                    window.__mcpRefMap[ref] = el;

                    var indent = '';
                    for (var i = 0; i < depth; i++) indent += '  ';

                    var line = indent + '- ' + role;
                    if (name) line += ' "' + name.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
                    line += ' [ref=' + ref + ']';

                    if (INCLUDE_BOXES) {
                        var r = el.getBoundingClientRect();
                        line += ' [box=' + Math.round(r.left*COORD_SCALE) + ',' + Math.round(r.top*COORD_SCALE) + ','
                              + Math.round(r.width*COORD_SCALE) + ',' + Math.round(r.height*COORD_SCALE) + ']';
                    }
                    return line;
                }

                // Tags to skip entirely (not user-visible content).
                var SKIP_TAGS = { script:1, style:1, link:1, meta:1, head:1, noscript:1, template:1 };

                function walk(el, depth) {
                    if (depth > MAX_DEPTH) return '';
                    if (!el || !el.tagName) return '';
                    var tag = el.tagName.toLowerCase();
                    if (SKIP_TAGS[tag]) return '';

                    var rect = el.getBoundingClientRect();
                    if (rect.width === 0 && rect.height === 0) return '';

                    var line     = buildLine(el, depth);
                    var children = '';
                    if (el.children && depth < MAX_DEPTH) {
                        var count = Math.min(el.children.length, MAX_CHILDREN);
                        for (var i = 0; i < count; i++) {
                            children += walk(el.children[i], depth + 1);
                        }
                    }

                    // Suppress anonymous generic containers with no name and no visible children.
                    var role = (el.getAttribute && el.getAttribute('role')) || implicitRole(el);
                    if (!children && role === 'generic' && !accessibleName(el, role)) return '';

                    return line + '\n' + children;
                }

                return walk(rootEl, 0);
            })()
        """.trimIndent()

        // ── 主 frame snapshot ─────────────────────────────────────────────
        val mainSnapshot = pageBridge.evaluateJs(js).trimQuotes()

        // ── 跨域 iframe snapshot（仅内部 WebView page，BROWSER page 不走此路径）─
        val page = pages[pageId]
        val iframeSnapshots = if (page?.source == PageSource.INTERNAL) {
            fetchIframeSnapshots(js)
        } else emptyList()

        val combined = buildString {
            append(mainSnapshot)
            for ((frameLabel, frameSnap) in iframeSnapshots) {
                if (frameSnap.isNotBlank()) {
                    append("\n--- iframe: $frameLabel ---\n")
                    append(frameSnap)
                }
            }
        }
        return JsonObject().apply { addProperty("snapshot", combined) }
    }

    private suspend fun toolTakeScreenshot(args: JsonObject): JsonObject {
        val pageId = args.get("pageId")?.asInt ?: throw IllegalArgumentException("Missing pageId")
        val pageBridge = getBridgeForPage(pageId)

        val bytes = pageBridge.captureScreenshot()
        return if (bytes != null) {
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            JsonObject().apply {
                addProperty("type", "image")
                addProperty("data", b64)
                addProperty("mimeType", "image/png")
            }
        } else {
            JsonObject().apply { addProperty("success", false); addProperty("error", "Screenshot failed") }
        }
    }

    // ── take_snapshot implementation ─────────────────────────────────────

    private suspend fun toolTakeSnapshot(args: JsonObject): JsonObject {
        val pageId     = args.get("pageId")?.asInt ?: throw IllegalArgumentException("Missing pageId")
        val depth      = args.get("depth")?.asInt ?: 12
        val target     = args.get("target")?.asString ?: ""
        val boxes      = args.get("boxes")?.asBoolean ?: false
        val pageBridge = getBridgeForPage(pageId)

        val targetJson  = gson.toJson(target)
        val depthJson   = depth.toString()
        val boxesJs     = if (boxes) "true" else "false"
        val coordScale  = pageBridge.viewportZoom

        // language=JavaScript
        val js = """
            (function() {
                var MAX_DEPTH    = $depthJson;
                var INCLUDE_BOXES = $boxesJs;
                var COORD_SCALE  = $coordScale;
                var targetSpec   = $targetJson;

                // ── Resolve root ──────────────────────────────────────────
                var rootEl = document.body;
                if (targetSpec) {
                    var prevMap = window.__mcpRefMap;
                    if (prevMap && prevMap[targetSpec]) {
                        rootEl = prevMap[targetSpec];
                    } else {
                        try { rootEl = document.querySelector(targetSpec) || document.body; } catch(e) {}
                    }
                }

                // ── Reset ref registry ────────────────────────────────────
                window.__mcpRefMap = {};
                var counter = 0;

                // ── Classification ────────────────────────────────────────
                var SKIP_TAGS = { script:1, style:1, link:1, meta:1, head:1,
                                  noscript:1, template:1, svg:1, path:1 };

                function isInteractive(el, cs) {
                    var tag = el.tagName.toLowerCase();
                    if (tag === 'a' || tag === 'button' || tag === 'select' ||
                        tag === 'input' || tag === 'textarea') return true;
                    if (cs.cursor === 'pointer') return true;
                    var role = el.getAttribute('role') || '';
                    if (role === 'button' || role === 'link' || role === 'checkbox' ||
                        role === 'menuitem' || role === 'option' || role === 'tab') return true;
                    if (el.getAttribute('onclick') || el.getAttribute('tabindex') === '0') return true;
                    return false;
                }

                // classify returns null for unclassified container nodes.
                function classify(el, cs, rect) {
                    var tag = el.tagName.toLowerCase();

                    // ── Images ────────────────────────────────────────────
                    if (tag === 'img') {
                        var nw = el.naturalWidth || 0;
                        var nh = el.naturalHeight || 0;
                        // skip icons / thumbnails
                        if (nw < 200 || nh < 200) return null;
                        return 'img';
                    }

                    var text = (el.innerText || el.textContent || '').trim()
                                  .replace(/\s+/g, ' ');

                    // ── Interactive: action or option ──────────────────────
                    if (isInteractive(el, cs)) {
                        if (rect.width >= 80 && rect.height >= 28) return 'action';
                        return 'option';
                    }

                    // ── Prominent text (leaf nodes only) ──────────────────
                    if (el.children.length === 0 && text.length > 0 && text.length <= 80) {
                        var fs = parseFloat(cs.fontSize) || 0;
                        var fw = parseInt(cs.fontWeight) || 400;
                        if (fs >= 18 || fw >= 600) return 'content';
                    }

                    return null;
                }

                // ── Image group collector ─────────────────────────────────
                // After walking children, if ≥3 img children clustered → merge into img-group.
                function buildImgGroup(imgChildren, indent) {
                    var lines = indent + '- [img-group:gallery] ' + imgChildren.length + '张\n';
                    for (var i = 0; i < imgChildren.length; i++) {
                        var ic = imgChildren[i];
                        lines += indent + '  - img ' + ic.nw + 'x' + ic.nh +
                                 ' src="' + ic.src + '" [ref=' + ic.ref + ']\n';
                    }
                    return lines;
                }

                // ── Main walk ─────────────────────────────────────────────
                function walk(el, depth) {
                    if (depth > MAX_DEPTH) return '';
                    if (!el || !el.tagName) return '';
                    var tag = el.tagName.toLowerCase();
                    if (SKIP_TAGS[tag]) return '';

                    var rect = el.getBoundingClientRect();
                    if (rect.width === 0 && rect.height === 0) return '';

                    var cs   = window.getComputedStyle(el);
                    var kind = classify(el, cs, rect);

                    var ref    = 'e' + (++counter);
                    window.__mcpRefMap[ref] = el;

                    var indent = '';
                    for (var i = 0; i < depth; i++) indent += '  ';

                    // ── Classified leaf: emit and stop recursion ──────────
                    if (kind !== null && kind !== 'img') {
                        var text = (el.innerText || el.textContent || '').trim()
                                      .replace(/\s+/g, ' ').substring(0, 80);
                        var line = indent + '- ' + tag;
                        if (text) line += ' "' + text.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
                        line += ' [' + kind + '] [ref=' + ref + ']';
                        if (INCLUDE_BOXES) {
                            line += ' [box=' + Math.round(rect.left*COORD_SCALE) + ',' + Math.round(rect.top*COORD_SCALE) + ','
                                  + Math.round(rect.width*COORD_SCALE) + ',' + Math.round(rect.height*COORD_SCALE) + ']';
                        }
                        return line + '\n';
                    }

                    // ── img node: collect for potential grouping ──────────
                    if (kind === 'img') {
                        var src = el.src || el.getAttribute('data-src') || '';
                        return JSON.stringify({
                            __imgLeaf: true, ref: ref,
                            src: src.substring(0, 200),
                            nw: el.naturalWidth || 0,
                            nh: el.naturalHeight || 0
                        }) + '\n';
                    }

                    // ── Container: recurse, then prune if empty ───────────
                    var childLines = '';
                    var imgBuffer  = [];
                    var count = Math.min(el.children.length, 80);
                    for (var ci = 0; ci < count; ci++) {
                        var childOut = walk(el.children[ci], depth + 1);
                        if (!childOut) continue;

                        // Check if child emitted an img-leaf marker
                        var trimmed = childOut.trim();
                        if (trimmed.charAt(0) === '{') {
                            try {
                                var obj = JSON.parse(trimmed);
                                if (obj.__imgLeaf) { imgBuffer.push(obj); continue; }
                            } catch(e) {}
                        }

                        // Flush pending img buffer if a non-img child appears
                        if (imgBuffer.length > 0) {
                            if (imgBuffer.length >= 3) {
                                childLines += buildImgGroup(imgBuffer, indent + '  ');
                            } else {
                                for (var bi = 0; bi < imgBuffer.length; bi++) {
                                    var ib = imgBuffer[bi];
                                    childLines += indent + '  - img ' + ib.nw + 'x' + ib.nh +
                                                  ' src="' + ib.src + '" [ref=' + ib.ref + ']\n';
                                }
                            }
                            imgBuffer = [];
                        }
                        childLines += childOut;
                    }

                    // Flush remaining img buffer
                    if (imgBuffer.length > 0) {
                        if (imgBuffer.length >= 3) {
                            childLines += buildImgGroup(imgBuffer, indent + '  ');
                        } else {
                            for (var bi2 = 0; bi2 < imgBuffer.length; bi2++) {
                                var ib2 = imgBuffer[bi2];
                                childLines += indent + '  - img ' + ib2.nw + 'x' + ib2.nh +
                                              ' src="' + ib2.src + '" [ref=' + ib2.ref + ']\n';
                            }
                        }
                    }

                    if (!childLines) return '';  // prune empty containers

                    var containerLine = indent + '- ' + tag + ' [ref=' + ref + ']';
                    if (INCLUDE_BOXES) {
                        containerLine += ' [box=' + Math.round(rect.left*COORD_SCALE) + ',' + Math.round(rect.top*COORD_SCALE) + ','
                                       + Math.round(rect.width*COORD_SCALE) + ',' + Math.round(rect.height*COORD_SCALE) + ']';
                    }
                    return containerLine + '\n' + childLines;
                }

                return walk(rootEl, 0);
            })()
        """.trimIndent()

        val mainSnapshot = pageBridge.evaluateJs(js).trimQuotes()

        val page = pages[pageId]
        val iframeSnapshots = if (page?.source == PageSource.INTERNAL) {
            fetchIframeSnapshots(js)
        } else emptyList()

        val combined = buildString {
            append(mainSnapshot)
            for ((frameLabel, frameSnap) in iframeSnapshots) {
                if (frameSnap.isNotBlank()) {
                    append("\n--- iframe: $frameLabel ---\n")
                    append(frameSnap)
                }
            }
        }
        return JsonObject().apply { addProperty("snapshot", combined) }
    }

    // ── CDP iframe helpers ────────────────────────────────────────────────

    // evaluateJs 把字符串结果包在 JSON 引号里（"..."），此处去掉外层引号。
    private fun String.trimQuotes(): String {
        val s = this.trim()
        return if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) {
            s.substring(1, s.length - 1)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else s
    }

    /**
     * 用 CDP 枚举所有跨域 iframe targets，对每个执行 walkJs，返回 (url, snapshotText) 列表。
     * CDP 不可用或失败时静默返回空列表（回退到主 frame 单独 snapshot）。
     */
    private suspend fun fetchIframeSnapshots(walkJs: String): List<Pair<String, String>> {
        val ch = try { cdp() } catch (_: Exception) { null } ?: return emptyList()
        return try {
            val frames = ch.getIframeFrames()
            LogUtil.i(TAG, "CDP iframe frames (${frames.size}): ${frames.joinToString { it.url.take(60) }}")
            if (frames.isEmpty()) return emptyList()

            val results = mutableListOf<Pair<String, String>>()
            for (frame in frames) {
                try {
                    val raw = ch.evaluateInFrame(frame.frameId, walkJs)
                    if (raw == "null" || raw.isBlank()) continue
                    val snap = raw.trimQuotes()
                    if (snap.isBlank()) continue
                    val labeled = prefixRefs(snap, frame.frameId)
                    results.add(frame.url to labeled)
                } catch (e: Exception) {
                    LogUtil.w(TAG, "iframe snapshot failed for ${frame.url}: ${e.message}")
                }
            }
            results
        } catch (e: Exception) {
            LogUtil.w(TAG, "fetchIframeSnapshots failed: ${e.message}")
            invalidateCdp()
            emptyList()
        }
    }

    // ── Interaction implementations ───────────────────────────────────────

    /**
     * 在正确的 frame context 里执行 JS（ref 可能是 "targetId:eN" 跨域 iframe ref）。
     * jsBody 是一段 JS，其中 $REF$ 会被替换为本地 ref（不带 frameId 前缀），
     * 执行结果以字符串返回（与 evaluateJs 格式一致）。
     *
     * 主 frame ref（无 ":"）：走 pageBridge.evaluateJs。
     * iframe ref（含 ":"）：通过 CDP attachToTarget 在目标 frame 执行，失败时返回 null。
     */
    private suspend fun evalInFrame(pageBridge: WebViewBridge, ref: String, jsBody: String): String? {
        val parsed = parseRef(ref)
        val js = jsBody.replace("\$REF\$", gson.toJson(parsed.localRef))
        return if (parsed.sessionId == null) {
            pageBridge.evaluateJs(js)
        } else {
            val ch = try { cdp() } catch (_: Exception) { null } ?: return null
            var sid: String? = null
            try {
                sid = ch.attachToTarget(parsed.sessionId)
                ch.evaluateJs(js, sid)
            } catch (e: Exception) {
                LogUtil.w(TAG, "evalInFrame CDP failed for ref=$ref: ${e.message}")
                null
            } finally {
                sid?.let { ch.detachFromTarget(it) }
            }
        }
    }

    private suspend fun toolClick(args: JsonObject): JsonObject {
        val pageId      = args.get("pageId")?.asInt    ?: throw IllegalArgumentException("Missing pageId")
        val target      = args.get("target")?.asString
        val doubleClick = args.get("doubleClick")?.asBoolean ?: false
        val button      = args.get("button")?.asString ?: "left"
        val buttonCode  = when (button) { "right" -> 2; "middle" -> 1; else -> 0 }
        val pageBridge  = getBridgeForPage(pageId)

        // Coordinate mode: x/y/displayedWidth provided instead of target selector.
        // Coordinate mode: x/y are physical pixel coordinates (same system as take_screenshot / take_snapshot).
        if (target == null) {
            val rawX           = args.get("x")?.asFloat ?: throw IllegalArgumentException("Missing target or x/y")
            val rawY           = args.get("y")?.asFloat ?: throw IllegalArgumentException("Missing target or x/y")
            val displayedWidth = args.get("displayedWidth")?.asFloat
            val cx: Float
            val cy: Float
            if (displayedWidth != null && displayedWidth > 0f) {
                val ratio = pageBridge.viewportWidth.toFloat() / displayedWidth
                cx = rawX * ratio
                cy = rawY * ratio
            } else {
                cx = rawX
                cy = rawY
            }
            val nativeOk = pageBridge.dispatchTouchAt(cx, cy)
            return JsonObject().apply {
                addProperty("success", nativeOk)
                addProperty("mode", if (nativeOk) "native" else "fallback")
                addProperty("realX", cx)
                addProperty("realY", cy)
                if (displayedWidth != null) addProperty("coordScale", cx / rawX)
            }
        }

        // Selector / ref mode.
        // language=JavaScript — $REF$ is replaced by evalInFrame with the local ref (no frame prefix)
        val prepJs = """
            (function() {
                var ref = ${'$'}REF${'$'};
                var el = (window.__mcpRefMap && window.__mcpRefMap[ref])
                       || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
                if (!el) return JSON.stringify({ found: false });

                el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
                var rect = el.getBoundingClientRect();
                var cx   = rect.left + rect.width  / 2;
                var cy   = rect.top  + rect.height / 2;

                (function() {
                    var ripple = document.createElement('div');
                    var size   = 80;
                    ripple.style.cssText = [
                        'position:fixed',
                        'left:' + (cx - size / 2) + 'px',
                        'top:' + (cy - size / 2) + 'px',
                        'width:' + size + 'px',
                        'height:' + size + 'px',
                        'border-radius:50%',
                        'background:rgba(33,150,243,0.6)',
                        'pointer-events:none',
                        'z-index:2147483647',
                        'transform:scale(0)',
                        'transition:transform 2s ease-out,opacity 2s ease-out',
                        'opacity:1'
                    ].join(';');
                    document.documentElement.appendChild(ripple);
                    requestAnimationFrame(function() {
                        requestAnimationFrame(function() {
                            ripple.style.transform = 'scale(1)';
                            ripple.style.opacity   = '0';
                            setTimeout(function() {
                                if (ripple.parentNode) ripple.parentNode.removeChild(ripple);
                            }, 2200);
                        });
                    });
                })();

                return JSON.stringify({ found: true, cx: cx, cy: cy });
            })()
        """.trimIndent()

        val prepRaw = evalInFrame(pageBridge, target, prepJs)
        val prepResult = try { prepRaw?.let { parseJsResult(it) } } catch (_: Exception) { null }
        if (prepResult?.get("found")?.asBoolean != true) {
            return JsonObject().apply {
                addProperty("success", false)
                addProperty("error", "Element not found: $target")
            }
        }

        val cx = prepResult.get("cx").asFloat
        val cy = prepResult.get("cy").asFloat

        // getBoundingClientRect() 返回 CSS 像素，dispatchTouchAt 需要 View 物理像素。
        val zoom = pageBridge.viewportZoom
        val nativeOk = pageBridge.dispatchTouchAt(cx * zoom, cy * zoom)
        if (nativeOk) {
            return JsonObject().apply {
                addProperty("success", true)
                addProperty("mode", "native")
            }
        }

        // Fallback: synthetic mouse events via JS.
        // language=JavaScript
        val fallbackJs = """
            (function() {
                var ref = ${'$'}REF${'$'};
                var el = (window.__mcpRefMap && window.__mcpRefMap[ref])
                       || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
                if (!el) return JSON.stringify({ success: false, error: 'Element not found: ' + ref });
                var rect = el.getBoundingClientRect();
                var cx   = rect.left + rect.width  / 2;
                var cy   = rect.top  + rect.height / 2;
                var init = { bubbles:true, cancelable:true, view:window,
                             clientX:cx, clientY:cy, button:$buttonCode, buttons:1, detail:1 };
                el.dispatchEvent(new MouseEvent('mousedown', init));
                el.dispatchEvent(new MouseEvent('mouseup',   init));
                el.dispatchEvent(new MouseEvent('click',     init));
                if (${if (doubleClick) "true" else "false"}) {
                    el.dispatchEvent(new MouseEvent('dblclick', Object.assign({}, init, { detail: 2 })));
                }
                return JSON.stringify({ success: true });
            })()
        """.trimIndent()

        val result = evalInFrame(pageBridge, target, fallbackJs) ?: return JsonObject().apply {
            addProperty("success", false)
            addProperty("error", "CDP frame eval failed for ref: $target")
        }
        return parseJsResult(result)
    }

    private suspend fun toolType(args: JsonObject): JsonObject {
        val pageId     = args.get("pageId")?.asInt    ?: throw IllegalArgumentException("Missing pageId")
        val target     = args.get("target")?.asString ?: throw IllegalArgumentException("Missing target")
        val text       = args.get("text")?.asString   ?: throw IllegalArgumentException("Missing text")
        val submit     = args.get("submit")?.asBoolean ?: false
        val textJson   = gson.toJson(text)
        val pageBridge = getBridgeForPage(pageId)

        // language=JavaScript
        val js = """
            (function() {
                var ref = ${'$'}REF${'$'};
                var el  = (window.__mcpRefMap && window.__mcpRefMap[ref])
                        || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
                if (!el) return JSON.stringify({ success: false, error: 'Element not found: ' + ref });

                el.focus();
                var chars = $textJson;
                for (var i = 0; i < chars.length; i++) {
                    var c = chars[i];
                    el.dispatchEvent(new KeyboardEvent('keydown',  { key:c, bubbles:true, cancelable:true }));
                    el.dispatchEvent(new KeyboardEvent('keypress', { key:c, bubbles:true, cancelable:true }));
                    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable) {
                        if (el.isContentEditable) {
                            el.textContent += c;
                        } else {
                            var v = el.value || '';
                            var s = el.selectionStart !== undefined ? el.selectionStart : v.length;
                            var e2 = el.selectionEnd   !== undefined ? el.selectionEnd   : v.length;
                            el.value = v.substring(0, s) + c + v.substring(e2);
                            el.selectionStart = el.selectionEnd = s + 1;
                        }
                        el.dispatchEvent(new InputEvent('input', { bubbles:true, cancelable:true }));
                    }
                    el.dispatchEvent(new KeyboardEvent('keyup',   { key:c, bubbles:true, cancelable:true }));
                }
                if (${if (submit) "true" else "false"}) {
                    el.dispatchEvent(new KeyboardEvent('keydown', { key:'Enter', bubbles:true, cancelable:true }));
                    var form = el.closest ? el.closest('form') : null;
                    if (form) form.dispatchEvent(new Event('submit', { bubbles:true, cancelable:true }));
                }
                return JSON.stringify({ success: true });
            })()
        """.trimIndent()

        val result = evalInFrame(pageBridge, target, js) ?: return JsonObject().apply {
            addProperty("success", false); addProperty("error", "eval failed for ref: $target")
        }
        return parseJsResult(result)
    }

    private suspend fun toolFill(args: JsonObject): JsonObject {
        val pageId     = args.get("pageId")?.asInt    ?: throw IllegalArgumentException("Missing pageId")
        val target     = args.get("target")?.asString ?: throw IllegalArgumentException("Missing target")
        val value      = args.get("value")?.asString  ?: throw IllegalArgumentException("Missing value")
        val valueJson  = gson.toJson(value)
        val pageBridge = getBridgeForPage(pageId)

        // language=JavaScript
        val js = """
            (function() {
                var ref = ${'$'}REF${'$'};
                var el  = (window.__mcpRefMap && window.__mcpRefMap[ref])
                        || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
                if (!el) return JSON.stringify({ success: false, error: 'Element not found: ' + ref });
                el.focus();
                el.value = $valueJson;
                el.dispatchEvent(new Event('input',  { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return JSON.stringify({ success: true });
            })()
        """.trimIndent()

        val result = evalInFrame(pageBridge, target, js) ?: return JsonObject().apply {
            addProperty("success", false); addProperty("error", "eval failed for ref: $target")
        }
        return parseJsResult(result)
    }

    private suspend fun toolFillForm(args: JsonObject): JsonObject {
        val pageId = args.get("pageId")?.asInt ?: throw IllegalArgumentException("Missing pageId")
        val fields = args.getAsJsonArray("fields") ?: throw IllegalArgumentException("Missing fields")
        val pageBridge = getBridgeForPage(pageId)
        val results = JsonArray()

        for (field in fields) {
            val obj    = field.asJsonObject
            val target = obj.get("target")?.asString ?: continue
            val value  = obj.get("value")?.asString  ?: continue
            val valueJson = gson.toJson(value)

            // language=JavaScript
            val js = """
                (function() {
                    var ref = ${'$'}REF${'$'};
                    var el  = (window.__mcpRefMap && window.__mcpRefMap[ref])
                            || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
                    if (!el) return false;
                    el.focus();
                    el.value = $valueJson;
                    el.dispatchEvent(new Event('input',  { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                })()
            """.trimIndent()

            val r = evalInFrame(pageBridge, target, js) ?: "false"
            results.add(r.toBoolean())
        }

        return JsonObject().apply { add("results", results) }
    }

    private suspend fun toolSelectOption(args: JsonObject): JsonObject {
        val pageId     = args.get("pageId")?.asInt    ?: throw IllegalArgumentException("Missing pageId")
        val target     = args.get("target")?.asString ?: throw IllegalArgumentException("Missing target")
        val values     = args.getAsJsonArray("values") ?: throw IllegalArgumentException("Missing values")
        val valuesJson = gson.toJson(values)
        val pageBridge = getBridgeForPage(pageId)

        // language=JavaScript
        val js = """
            (function() {
                var ref = ${'$'}REF${'$'};
                var el  = (window.__mcpRefMap && window.__mcpRefMap[ref])
                        || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
                if (!el || el.tagName !== 'SELECT')
                    return JSON.stringify({ success: false, error: 'Select element not found: ' + ref });
                var vals = $valuesJson;
                for (var i = 0; i < el.options.length; i++) {
                    el.options[i].selected = vals.indexOf(el.options[i].value) !== -1;
                }
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return JSON.stringify({ success: true });
            })()
        """.trimIndent()

        val result = evalInFrame(pageBridge, target, js) ?: return JsonObject().apply {
            addProperty("success", false); addProperty("error", "eval failed for ref: $target")
        }
        return parseJsResult(result)
    }

    private suspend fun toolHover(args: JsonObject): JsonObject {
        val pageId     = args.get("pageId")?.asInt    ?: throw IllegalArgumentException("Missing pageId")
        val target     = args.get("target")?.asString ?: throw IllegalArgumentException("Missing target")
        val pageBridge = getBridgeForPage(pageId)

        // language=JavaScript
        val js = """
            (function() {
                var ref = ${'$'}REF${'$'};
                var el  = (window.__mcpRefMap && window.__mcpRefMap[ref])
                        || (function(){ try { return document.querySelector(ref); } catch(e){ return null; } })();
                if (!el) return JSON.stringify({ success: false, error: 'Element not found: ' + ref });
                var rect = el.getBoundingClientRect();
                var cx   = rect.left + rect.width  / 2;
                var cy   = rect.top  + rect.height / 2;
                var init = { bubbles:true, cancelable:true, view:window, clientX:cx, clientY:cy };
                el.dispatchEvent(new MouseEvent('mouseover',  init));
                el.dispatchEvent(new MouseEvent('mouseenter', init));
                el.dispatchEvent(new MouseEvent('mousemove',  init));
                return JSON.stringify({ success: true });
            })()
        """.trimIndent()

        val result = evalInFrame(pageBridge, target, js) ?: return JsonObject().apply {
            addProperty("success", false); addProperty("error", "eval failed for ref: $target")
        }
        return parseJsResult(result)
    }

    private suspend fun toolDrag(args: JsonObject): JsonObject {
        val pageId      = args.get("pageId")?.asInt    ?: throw IllegalArgumentException("Missing pageId")
        val startTarget = args.get("startTarget")?.asString ?: throw IllegalArgumentException("Missing startTarget")
        val endTarget   = args.get("endTarget")?.asString   ?: throw IllegalArgumentException("Missing endTarget")
        val pageBridge  = getBridgeForPage(pageId)

        // Drag only makes sense within the same frame; use startTarget's frame.
        // endTarget is resolved in the same frame context.
        val endTargetJson = gson.toJson(parseRef(endTarget).localRef)

        // language=JavaScript
        val js = """
            (function() {
                function resolve(spec) {
                    return (window.__mcpRefMap && window.__mcpRefMap[spec])
                        || (function(){ try { return document.querySelector(spec); } catch(e){ return null; } })();
                }
                var from = resolve(${'$'}REF${'$'});
                var to   = resolve($endTargetJson);
                if (!from) return JSON.stringify({ success: false, error: 'Source element not found' });
                if (!to)   return JSON.stringify({ success: false, error: 'Destination element not found' });

                function center(el) {
                    var r = el.getBoundingClientRect();
                    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
                }
                var s = center(from), e = center(to);

                var makeEvent = function(type, x, y, buttons) {
                    return new MouseEvent(type, {
                        bubbles:true, cancelable:true, view:window,
                        clientX:x, clientY:y, button:0, buttons:buttons
                    });
                };
                from.dispatchEvent(makeEvent('mousedown', s.x, s.y, 1));
                from.dispatchEvent(makeEvent('dragstart', s.x, s.y, 1));
                to.dispatchEvent(makeEvent('dragover',   e.x, e.y, 1));
                to.dispatchEvent(makeEvent('drop',       e.x, e.y, 0));
                from.dispatchEvent(makeEvent('dragend',  e.x, e.y, 0));
                to.dispatchEvent(makeEvent('mouseup',    e.x, e.y, 0));
                return JSON.stringify({ success: true });
            })()
        """.trimIndent()

        val result = evalInFrame(pageBridge, startTarget, js) ?: return JsonObject().apply {
            addProperty("success", false); addProperty("error", "eval failed for ref: $startTarget")
        }
        return parseJsResult(result)
    }

    private suspend fun toolPressKey(args: JsonObject): JsonObject {
        val pageId     = args.get("pageId")?.asInt    ?: throw IllegalArgumentException("Missing pageId")
        val key        = args.get("key")?.asString    ?: throw IllegalArgumentException("Missing key")

        // "Back" = Android system KEYCODE_BACK — brings the foreground app back (e.g. after
        // jumping to Meituan). This is a device-level key, not a WebView JS event.
        if (key == "Back") {
            bridge.pressSystemBack()
            return JsonObject().apply { addProperty("success", true) }
        }

        val keyJson    = gson.toJson(key)
        val pageBridge = getBridgeForPage(pageId)

        // language=JavaScript
        val js = """
            (function() {
                var key = $keyJson;
                var el  = document.activeElement || document.body;
                var init = { key:key, bubbles:true, cancelable:true };
                el.dispatchEvent(new KeyboardEvent('keydown',  init));
                el.dispatchEvent(new KeyboardEvent('keypress', init));
                el.dispatchEvent(new KeyboardEvent('keyup',    init));
                if (key === 'Enter') {
                    var form = el.closest ? el.closest('form') : null;
                    if (form) form.dispatchEvent(new Event('submit', { bubbles:true, cancelable:true }));
                }
                return JSON.stringify({ success: true });
            })()
        """.trimIndent()

        val result = pageBridge.evaluateJs(js)
        return parseJsResult(result)
    }

    private suspend fun toolUploadFile(args: JsonObject): JsonObject {
        val target   = args.get("target")?.asString   ?: throw IllegalArgumentException("Missing target")
        val filePath = args.get("filePath")?.asString ?: throw IllegalArgumentException("Missing filePath")

        if (!File(filePath).exists()) {
            return JsonObject().apply {
                addProperty("success", false)
                addProperty("error", "File not found: $filePath")
            }
        }
        // Android WebView cannot programmatically set files on a file input via JS.
        return JsonObject().apply {
            addProperty("success", false)
            addProperty("error", "Programmatic file upload is not supported in Android WebView. " +
                "Use the device file picker triggered by clicking the file input.")
        }
    }

    // ── evaluate_script_in_frame ──────────────────────────────────────────

    /**
     * 获取或新建一个指向本 app 自身 WebView 的 LocalSocketCdpBridge。
     * 通过 Android LocalSocket 直连 webview_devtools_remote_<pid>，
     * 不需要 TcpForwarder，也不受 SELinux 网络隔离影响。
     * 连接复用：只要 isConnected 为 true 就不重建。
     */
    private suspend fun toolEvaluateScriptInFrame(args: JsonObject): JsonObject {
        val frameUrl = args.get("frameUrl")?.asString
            ?: throw IllegalArgumentException("Missing frameUrl")
        val script   = args.get("script")?.asString
            ?: throw IllegalArgumentException("Missing script")

        val ch = try { cdp() } catch (_: Exception) { null }
            ?: return JsonObject().apply {
                addProperty("success", false)
                addProperty("error", "CDP channel not available")
            }

        return try {
            val result = ch.evaluateInFrameByOrigin(frameUrl, script)
            JsonObject().apply { addProperty("result", result) }
        } catch (e: Exception) {
            LogUtil.e(TAG, "evaluate_script_in_frame failed: ${e.message}")
            JsonObject().apply {
                addProperty("success", false)
                addProperty("error", "Frame evaluation failed: ${e.message}")
            }
        }
    }

    private suspend fun toolEvaluateScript(args: JsonObject): JsonObject {
        val pageId   = args.get("pageId")?.asInt    ?: throw IllegalArgumentException("Missing pageId")
        val script   = args.get("script")?.asString ?: throw IllegalArgumentException("Missing script")
        val frameUrl = args.get("frameUrl")?.asString

        if (!frameUrl.isNullOrBlank()) {
            val ch = try { cdp() } catch (_: Exception) { null }
                ?: return JsonObject().apply { addProperty("error", "CDP not available") }
            val frames = ch.getIframeFrames()
            val frame = frames.firstOrNull { it.url.contains(frameUrl) }
                ?: return JsonObject().apply {
                    addProperty("error", "iframe not found: $frameUrl")
                    addProperty("available", frames.joinToString { it.url })
                }
            val result = ch.evaluateInFrame(frame.frameId, script)
            return JsonObject().apply { addProperty("result", result) }
        }

        val pageBridge = getBridgeForPage(pageId)
        val result = pageBridge.evaluateJs(script)
        return JsonObject().apply { addProperty("result", result) }
    }

    private fun toolResizePage(args: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("success", false)
            addProperty("error", "Viewport resize is not supported in Android WebView.")
        }
    }

    private fun toolHandleDialog(args: JsonObject): JsonObject {
        val accept     = args.get("accept")?.asBoolean ?: throw IllegalArgumentException("Missing accept")
        val promptText = args.get("promptText")?.asString
        // Dialog handling is forwarded to WebChromeClient by the hosting Activity.
        return JsonObject().apply {
            addProperty("success", true)
            addProperty("accept", accept)
            promptText?.let { addProperty("promptText", it) }
        }
    }

    private suspend fun toolWaitFor(args: JsonObject): JsonObject {
        val pageId    = args.get("pageId")?.asInt ?: throw IllegalArgumentException("Missing pageId")
        val text      = args.get("text")?.asString
        val textGone  = args.get("textGone")?.asString
        val timeout   = args.get("timeout")?.asLong ?: 30_000L
        val pageBridge = getBridgeForPage(pageId)
        val page = pages[pageId]
        val startTime = System.currentTimeMillis()

        if (text == null && textGone == null) {
            kotlinx.coroutines.delay(timeout)
            return JsonObject().apply { addProperty("success", true) }
        }

        val textJson     = gson.toJson(text ?: "")
        val textGoneJson = gson.toJson(textGone ?: "")

        // language=JavaScript
        val bodyTextJs = """
            (function() {
                return document.body ? document.body.innerText : '';
            })()
        """.trimIndent()

        while (System.currentTimeMillis() - startTime < timeout) {
            // 收集所有 frame 的 innerText
            val bodies = mutableListOf<String>()
            bodies.add(pageBridge.evaluateJs(bodyTextJs).trimQuotes())

            // 如果是内部 WebView page，通过 CDP 也查 iframe frames
            if (page?.source == PageSource.INTERNAL) {
                val ch = try { cdp() } catch (_: Exception) { null }
                if (ch != null) {
                    try {
                        val frames = ch.getIframeFrames()
                        for (frame in frames) {
                            try {
                                val text = ch.evaluateInFrame(frame.frameId, bodyTextJs)
                                bodies.add(text.trimQuotes())
                            } catch (_: Exception) { }
                        }
                    } catch (e: Exception) {
                        LogUtil.w(TAG, "waitFor CDP iframe check failed: ${e.message}")
                    }
                }
            }

            val combined = bodies.joinToString("\n")
            val foundFor  = text == null     || combined.contains(text)
            val foundGone = textGone == null || !combined.contains(textGone)
            if (foundFor && foundGone) {
                return JsonObject().apply { addProperty("success", true) }
            }
            kotlinx.coroutines.delay(500)
        }

        return JsonObject().apply {
            addProperty("success", false)
            addProperty("error", "Timeout waiting for condition")
        }
    }

    // 默认滑动距离：视口高度的 40%，最少 200px
    private fun defaultSwipeDistance(pageBridge: WebViewBridge): Int {
        val vh = pageBridge.viewportHeight
        return if (vh > 0) (vh * 0.4f).toInt().coerceAtLeast(200) else 300
    }

    private suspend fun toolListFrames(args: JsonObject): JsonObject {
        val ch = try { cdp() } catch (_: Exception) { null }
            ?: return JsonObject().apply { addProperty("error", "CDP not available") }
        val frames = ch.getIframeFrames()
        val arr = com.google.gson.JsonArray()
        frames.forEach { f ->
            arr.add(JsonObject().apply {
                addProperty("frameId", f.frameId)
                addProperty("url", f.url)
            })
        }
        return JsonObject().apply { add("frames", arr) }
    }

    private suspend fun toolScrollUp(args: JsonObject): JsonObject {
        val pageId     = args.get("pageId")?.asInt ?: throw IllegalArgumentException("Missing pageId")
        val pageBridge = getBridgeForPage(pageId)

        val vw   = pageBridge.viewportWidth
        val vh   = pageBridge.viewportHeight
        val cx   = (if (vw > 0) vw else 400) / 2f
        val dist = defaultSwipeDistance(pageBridge)
        // 上滑：手指从屏幕中部往上移（fromY 在中心偏下，toY 在中心偏上）
        val fromY = (if (vh > 0) vh else 600) * 0.6f
        val toY   = fromY - dist

        val ok = pageBridge.dispatchSwipe(cx, fromY, cx, toY, durationMs = 250L)
        return JsonObject().apply {
            addProperty("success", ok)
            addProperty("direction", "up")
            addProperty("distance", dist)
            if (!ok) addProperty("error", "dispatchSwipe returned false")
        }
    }

    private suspend fun toolScrollDown(args: JsonObject): JsonObject {
        val pageId     = args.get("pageId")?.asInt ?: throw IllegalArgumentException("Missing pageId")
        val pageBridge = getBridgeForPage(pageId)

        val vw   = pageBridge.viewportWidth
        val vh   = pageBridge.viewportHeight
        val cx   = (if (vw > 0) vw else 400) / 2f
        val dist = defaultSwipeDistance(pageBridge)
        // 下滑：手指从屏幕中部往下移（fromY 在中心偏上，toY 在中心偏下）
        val fromY = (if (vh > 0) vh else 600) * 0.4f
        val toY   = fromY + dist

        val ok = pageBridge.dispatchSwipe(cx, fromY, cx, toY, durationMs = 250L)
        return JsonObject().apply {
            addProperty("success", ok)
            addProperty("direction", "down")
            addProperty("distance", dist)
            if (!ok) addProperty("error", "dispatchSwipe returned false")
        }
    }

    // ── Prop helpers ──────────────────────────────────────────────────────

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
}
