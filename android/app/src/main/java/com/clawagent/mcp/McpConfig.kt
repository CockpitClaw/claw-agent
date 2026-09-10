package com.clawagent.mcp

/**
 * MCP Server 配置
 */
data class McpConfig(
    /** TCP 监听端口，默认 19003（与 mac browser-mcp 的 19002 错开，避免 adb forward 串台）*/
    val port: Int = DEFAULT_MCP_PORT,
    /** Bearer 认证令牌；为空时不鉴权 */
    val authToken: String = "",
) {
    companion object {
        const val DEFAULT_MCP_PORT = 19003

        // Intent extra 键名
        const val EXTRA_AUTH_TOKEN = "mcp_auth_token"
        const val EXTRA_PORT = "mcp_port"

        // MCP 协议版本
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_NAME = "android-mcp"
        const val SERVER_VERSION = "1.0.0"
    }
}
