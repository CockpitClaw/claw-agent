package com.clawagent.mcp.browser

import android.net.LocalSocket
import android.net.LocalSocketAddress

/**
 * 被 app_process 以 root 身份执行的 socket 桥接辅助程序。
 * 以 root UID 运行时 Chromium DevTools 允许连接。
 * 通过 stdin/stdout 与父进程（LocalSocketProxy）双向 pipe。
 *
 * 用法：app_process -Djava.class.path=<apk> /system/bin
 *        com.clawagent.mcp.browser.SocketBridgeHelper <socketName>
 */
object SocketBridgeHelper {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: SocketBridgeHelper <abstractSocketName>")
            System.exit(1)
        }

        val socketName = args[0]
        val localSocket = LocalSocket()

        try {
            localSocket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: Exception) {
            System.err.println("Failed to connect to @$socketName: ${e.message}")
            System.exit(2)
        }

        val stdin = System.`in`
        val stdout = System.out
        val socketIn = localSocket.inputStream
        val socketOut = localSocket.outputStream

        // stdin → socket (父进程发来的 TCP 数据转发到 DevTools)
        val t1 = Thread {
            val buf = ByteArray(16384)
            try {
                while (true) {
                    val n = stdin.read(buf)
                    if (n == -1) break
                    socketOut.write(buf, 0, n)
                    socketOut.flush()
                }
            } catch (_: Exception) {}
            finally {
                try { localSocket.close() } catch (_: Exception) {}
            }
        }

        // socket → stdout (DevTools 返回的数据转发给父进程)
        val t2 = Thread {
            val buf = ByteArray(16384)
            try {
                while (true) {
                    val n = socketIn.read(buf)
                    if (n == -1) break
                    stdout.write(buf, 0, n)
                    stdout.flush()
                }
            } catch (_: Exception) {}
            finally {
                try { localSocket.close() } catch (_: Exception) {}
            }
        }

        t1.isDaemon = true
        t2.isDaemon = true
        t1.start()
        t2.start()

        // 等待任一线程结束
        try { t1.join() } catch (_: Exception) {}
        try { t2.join() } catch (_: Exception) {}

        try { localSocket.close() } catch (_: Exception) {}
    }
}
