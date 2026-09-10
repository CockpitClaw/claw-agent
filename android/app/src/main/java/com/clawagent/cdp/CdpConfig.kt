package com.clawagent.cdp

import java.util.UUID

/**
 * CDP Server 运行时配置。
 *
 * 所有与端口、target ID、协议版本相关的常量集中在此处。
 * 端口说明：
 *   18789 = ClawAgent Gateway 主端口
 *   18790 = Bridge Server
 *   18791 = ClawAgent Browser Control Server（内部已占用）
 *   18792 = Extension Relay Server（v5.18 废弃）
 *   18793 = Canvas Host
 *   18800-18899 = CDP 端口范围（ClawAgent 管理 Chrome 时使用）
 *   19001 = WebView CDP Server（本 Activity 默认端口）
 */
data class CdpConfig(
    /** WebSocket/HTTP 监听端口，默认 19001 */
    val port: Int = DEFAULT_CDP_PORT,
    /** Bearer / Basic 认证令牌；为空时不鉴权 */
    val authToken: String = "",
) {
    // ── Target ID（每次进程启动重新生成，保持稳定直到重启）────────────
    val browserTargetId: String = "browser-0"
    val pageTargetId: String = "webview-page-${UUID.randomUUID().toString().substring(0, 8)}"
    val browserContextId: String = "context-0"
    val pageSessionId: String get() = "session-$pageTargetId"

    companion object {
        const val DEFAULT_CDP_PORT = 19001

        // ── Intent extra 键名 ──────────────────────────────────────
        /** 传入 auth token；为 null 或空字符串时不做认证 */
        const val EXTRA_AUTH_TOKEN = "auth_token"
        /** 传入监听端口；不传时使用默认端口 */
        const val EXTRA_PORT = "cdp_port"

        // ── CDP 协议版本信息 ────────────────────────────────────────
        const val PROTOCOL_VERSION = "1.3"
        const val BROWSER_NAME = "AndroidWebView/CDP"
        const val USER_AGENT = "Mozilla/5.0 Android WebView"
        const val V8_VERSION = "11.0"
        const val WEBKIT_VERSION = "537.36"

        /** 默认首页 URL */
        const val DEFAULT_LOAD_URL = "https://www.baidu.com"
    }
}
