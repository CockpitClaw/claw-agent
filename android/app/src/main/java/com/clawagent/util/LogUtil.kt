package com.clawagent.util

import android.util.Log

/**
 * 精简通用日志工具（从车机集成中剥离）。
 * 保留分层打印、tag 前缀、长消息分片能力，去掉车机 mesh/TPU 相关方法。
 */
object LogUtil {

    private const val TAG_PREFIX = "ClawAgent."
    private const val MAX_LENGTH = 4000

    fun v(tag: String, msg: String) = printLog(Log.VERBOSE, wrap(tag), msg)

    fun d(tag: String, msg: String) = printLog(Log.DEBUG, wrap(tag), msg)

    fun i(tag: String, msg: String) = printLog(Log.INFO, wrap(tag), msg)

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        val m = if (tr == null) msg else "$msg\n${tr.stackTraceToString()}"
        printLog(Log.WARN, wrap(tag), m)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val m = if (tr == null) msg else "$msg\n${tr.stackTraceToString()}"
        printLog(Log.ERROR, wrap(tag), m)
    }

    private fun wrap(tag: String): String =
        if (tag.startsWith(TAG_PREFIX)) tag else "$TAG_PREFIX$tag"

    private fun printLog(priority: Int, tag: String, msg: String) {
        if (msg.length <= MAX_LENGTH) {
            Log.println(priority, tag, msg)
            return
        }
        var start = 0
        while (start < msg.length) {
            val end = minOf(start + MAX_LENGTH, msg.length)
            Log.println(priority, tag, msg.substring(start, end))
            start = end
        }
    }
}