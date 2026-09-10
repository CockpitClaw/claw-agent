package com.clawagent.accessibility

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 辅助工具：编程方式启用 / 检查 NativeAccessibilityService。
 *
 * 需要 `android.permission.WRITE_SECURE_SETTINGS` 权限（平台签名 App 可获得）。
 * 若权限未被授予，本方法会静默失败并打印警告日志，不会抛出异常。
 */
object AccessibilityServiceHelper {

    private const val TAG = "A11yServiceHelper"

    /**
     * 确保 [NativeAccessibilityService] 已在系统无障碍服务列表中注册。
     *
     * 逻辑：
     * 1. 读取当前 `enabled_accessibility_services` 设置值
     * 2. 若组件名已存在，直接返回（幂等）
     * 3. 否则将其追加到列表末尾后写回
     *
     * @return true  表示本次写入成功（或服务已存在无需写入）
     *         false 表示写入失败（权限缺失或其他异常）
     */
    fun ensureEnabled(context: Context): Boolean {
        val componentName = NativeAccessibilityService.COMPONENT_NAME
        return try {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: ""

            // 检查是否已启用（精确匹配组件名，避免误判子串）
            val services = current.split(":").filter { it.isNotBlank() }
            if (services.any { it.equals(componentName, ignoreCase = true) }) {
                Log.i(TAG, "NativeAccessibilityService already enabled, skip")
                return true
            }

            // 追加组件名
            val updated = if (current.isBlank()) componentName else "$current:$componentName"
            val ok = Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updated
            )
            if (ok) {
                Log.i(TAG, "NativeAccessibilityService enabled successfully")
            } else {
                Log.w(TAG, "putString returned false — WRITE_SECURE_SETTINGS may not be granted")
            }
            ok
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot enable accessibility service: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error enabling accessibility service: ${e.message}")
            false
        }
    }

    /**
     * 检查 [NativeAccessibilityService] 是否已在系统无障碍服务列表中。
     */
    fun isEnabled(context: Context): Boolean {
        return try {
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            current.split(":").any {
                it.equals(NativeAccessibilityService.COMPONENT_NAME, ignoreCase = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "isEnabled check failed: ${e.message}")
            false
        }
    }
}
