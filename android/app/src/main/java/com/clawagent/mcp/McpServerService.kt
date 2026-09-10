package com.clawagent.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 常驻 MCP Server 的宿主 Service。
 *
 * 背景：McpServer 原来直接绑在 WebViewRemoteControlActivity 的 onCreate/onDestroy 上——
 * Activity 一旦被系统 finish（切 Home、被别的 Activity 顶掉等），19003 端口整体下线，
 * 连不依赖 WebView 的 native_* 工具（截图/点击/打开小程序等）也一起失效。
 *
 * 现在 [McpServer] 实例的生命周期完全由本 Service 管理：只要进程存活就一直运行，
 * 与 Activity 是否在前台/是否被销毁无关。WebView 相关工具通过 [WebViewBridgeProxy]
 * 间接引用当前存活的 Activity；Activity 不在时这些工具会返回明确的错误信息，
 * 但服务本身、以及所有 native_* 工具不受影响。
 */
class McpServerService : Service() {

    private var mcpServer: McpServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (mcpServer == null) {
            val apkPath = applicationInfo.sourceDir ?: ""
            mcpServer = McpServer(McpConfig(), WebViewBridgeProxy, apkPath).also { it.start() }
            Log.i(TAG, "MCP Server started inside McpServerService (decoupled from Activity lifecycle)")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 幂等：McpServer 只在 onCreate 里创建一次，重复 startService 不会重建/重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mcpServer?.stop()
        mcpServer = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MCP 服务", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "MCP Server 常驻后台服务（端口 ${McpConfig.DEFAULT_MCP_PORT}）" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MCP 服务运行中")
            .setContentText("端口 ${McpConfig.DEFAULT_MCP_PORT}")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "McpServerService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "mcp_server"

        /** 幂等启动：Service 已在运行时重复调用无副作用（不会重建 McpServer）。 */
        fun ensureStarted(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, McpServerService::class.java))
        }
    }
}
