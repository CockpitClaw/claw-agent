package com.clawagent.mcp.browser

/**
 * 已废弃。原用于 TCP↔Abstract Unix Socket 代理。
 * 现改用 TcpForwarder 以 root 身份运行独立进程来绕过 Chromium UID 鉴权。
 * 保留此文件避免编译错误（如果 McpTools 还有引用）。
 */
@Deprecated("Use TcpForwarder instead")
class LocalSocketProxy(private val abstractSocketName: String) {
    var localPort: Int = 0
        private set
    fun start(): Int = localPort
    fun stop() {}
}
