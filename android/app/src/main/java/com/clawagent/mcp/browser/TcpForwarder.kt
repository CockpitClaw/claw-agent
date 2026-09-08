package com.clawagent.mcp.browser

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP → Abstract Unix Socket 端口转发服务。
 * 通过 adb shell 以 root/shell 身份启动，绕过 Chromium DevTools 的 UID 鉴权。
 *
 * 启动方式：
 *   adb shell "CLASSPATH=<apk> nohup app_process /system/bin \
 *     com.clawagent.mcp.browser.TcpForwarder \
 *     <abstractSocketName> <tcpPort> &"
 *
 * 示例：
 *   adb shell "CLASSPATH=/data/app/.../base.apk nohup app_process /system/bin \
 *     com.clawagent.mcp.browser.TcpForwarder \
 *     webview_devtools_remote_19573 19222 &"
 */
object TcpForwarder {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("Usage: TcpForwarder <abstractSocketName> <tcpPort>")
            System.exit(1)
        }

        val socketName = args[0]
        val port = args[1].toIntOrNull() ?: run {
            System.err.println("Invalid port: ${args[1]}")
            System.exit(1)
            return
        }

        // 先验证能否连接 abstract socket
        try {
            val test = LocalSocket()
            test.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            test.close()
            System.err.println("TcpForwarder: verified connection to @$socketName")
        } catch (e: Exception) {
            System.err.println("TcpForwarder: cannot connect to @$socketName: ${e.message}")
            System.exit(2)
        }

        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(java.net.InetSocketAddress(java.net.Inet4Address.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 4)
        System.err.println("TcpForwarder: listening on 127.0.0.1:$port -> @$socketName")

        while (true) {
            try {
                val tcp = server.accept()
                Thread { bridge(tcp, socketName) }.start()
            } catch (e: Exception) {
                System.err.println("TcpForwarder: accept error: ${e.message}")
                break
            }
        }
    }

    private fun bridge(tcp: Socket, socketName: String) {
        val local = LocalSocket()
        try {
            local.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: Exception) {
            System.err.println("TcpForwarder: bridge connect failed: ${e.message}")
            tcp.close()
            return
        }

        val t1 = Thread {
            val buf = ByteArray(16384)
            try {
                val input = tcp.getInputStream()
                val output = local.outputStream
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {}
            try { tcp.close() } catch (_: Exception) {}
            try { local.close() } catch (_: Exception) {}
        }

        val t2 = Thread {
            val buf = ByteArray(16384)
            try {
                val input = local.inputStream
                val output = tcp.getOutputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {}
            try { tcp.close() } catch (_: Exception) {}
            try { local.close() } catch (_: Exception) {}
        }

        t1.start()
        t2.start()
    }
}
