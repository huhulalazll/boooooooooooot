package com.qqbot.android

import android.content.Context
import android.view.View
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object StatusHelper {

    @Volatile
    var botRunning = false
    @Volatile
    var startTime = 0L

    fun refresh(c: Context, root: View) {
        val tvStatus = root.findViewById<TextView>(R.id.tvStatus)
        val tvNapcat = root.findViewById<TextView>(R.id.tvNapcat)
        val tvQQ = root.findViewById<TextView>(R.id.tvQQ)
        val tvBot = root.findViewById<TextView>(R.id.tvBot)
        val tvMem = root.findViewById<TextView>(R.id.tvMem)
        val tvUptime = root.findViewById<TextView>(R.id.tvUptime)
        val tvPersona = root.findViewById<TextView>(R.id.tvPersonaInfo)

        Thread {
            val rootfsOk = FileHelper.rootfsReady(c)
            val napcatOk = httpOk("http://127.0.0.1:3000/get_status", 2500)
            val botOk = httpOk("http://127.0.0.1:3001/", 2000) || napcatOk

            val memUsed = readMemUsage()
            val up = if (startTime > 0) formatUptime(System.currentTimeMillis() - startTime) else "—"

            val persona = ConfigStore.loadPersona(c)
            val pv = if (persona.isBlank()) "未设置" else "${persona.take(10)}…"

            (c as? MainActivity)?.runOnUiThread {
                tvStatus?.text = when {
                    !rootfsOk -> "● 未初始化（请到「启动」页启动）"
                    botRunning && napcatOk -> "● 运行中"
                    botRunning -> "● 启动中…"
                    else -> "● 已停止"
                }
                tvNapcat?.text = "NapCat： ${if (napcatOk) "✓ 正常" else "✗ 未响应"}"
                tvQQ?.text = "QQ 登录： ${if (napcatOk) "✓ 已登录" else "未知"}"
                tvBot?.text = "Bot： ${if (botOk) "✓ 已连接" else "✗ 未连接"}"
                tvMem?.text = "内存： $memUsed"
                tvUptime?.text = "运行时长： $up"
                tvPersona?.text = "当前人格： $pv"
            }
        }.start()
    }

    private fun httpOk(url: String, timeout: Int): Boolean {
        return try {
            val u = URL(url)
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (e: Exception) {
            false
        }
    }

    private fun readMemUsage(): String {
        return try {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
            "$used MB (JVM)"
        } catch (e: Exception) {
            "—"
        }
    }

    private fun formatUptime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s % 60}秒"
            else -> "${s}秒"
        }
    }
}
