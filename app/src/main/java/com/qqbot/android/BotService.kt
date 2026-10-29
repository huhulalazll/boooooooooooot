package com.qqbot.android

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

class BotService : Service() {

    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification("准备中..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null) {
            worker = Thread { runBot() }.also { it.start() }
        }
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val chId = "qqbot"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "QQBot", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, chId)
            .setContentTitle("QQBot 运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateStatus(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification(text))
    }

    private fun runBot() {
        try {
            val rootfs = File(filesDir, "rootfs")

            // 1. 首次解压 rootfs
            if (!File(rootfs, "opt/QQ/qq").exists()) {
                updateStatus("首次解压系统（约2-3分钟）...")
                unzipRootfs(rootfs)
            }

            // 2. 固定 machine-id
            updateStatus("初始化环境...")
            initMachineId(rootfs)

            // 3. 写入配置
            writeConfig(rootfs)

            // 4. 启动 proot + NapCat
            updateStatus("启动 NapCat...")
            startNapCat(rootfs)

        } catch (e: Exception) {
            updateStatus("启动失败: ${e.message}")
        }
    }

    private fun unzipRootfs(target: File) {
        target.mkdirs()
        val asset = assets.open("rootfs.tar.gz")
        // 使用 tar 解压需 native 支持，这里简化为调用系统 tar
        val p = ProcessBuilder("tar", "-xzf", "-", "-C", target.absolutePath)
            .redirectErrorStream(true)
            .start()
        GZIPInputStream(asset).copyTo(p.outputStream)
        p.outputStream.close()
        p.waitFor()
    }

    private fun initMachineId(rootfs: File) {
        val f = File(rootfs, "etc/machine-id")
        if (!f.exists() || f.readText().trim().length != 32) {
            val id = java.util.UUID.randomUUID().toString().replace("-", "")
            f.parentFile?.mkdirs()
            f.writeText(id + "\n")
            val d = File(rootfs, "var/lib/dbus/machine-id")
            d.parentFile?.mkdirs()
            d.writeText(id + "\n")
        }
    }

    private fun writeConfig(rootfs: File) {
        val cfg = File(filesDir, "botcfg")
        val botDir = File(rootfs, "root/mybot")
        botDir.mkdirs()

        File(cfg, "persona.txt").takeIf { it.exists() }
            ?.copyTo(File(botDir, "persona.txt"), overwrite = true)

        val apiKey = File(cfg, "api_key.txt").takeIf { it.exists() }?.readText() ?: ""
        File(botDir, "api_key.txt").writeText(apiKey)

        val qq = File(cfg, "qq.txt").takeIf { it.exists() }?.readText()?.trim() ?: ""
        File(botDir, "qq.txt").writeText(qq)
    }

    private fun startNapCat(rootfs: File) {
        val shm = File(filesDir, "shm").apply { mkdirs() }

        val cmd = listOf(
            "proot", "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${shm.absolutePath}:/dev/shm",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "/bin/bash", "-c",
            "cd /root/napcat_env && bash launcher.sh"
        )

        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().forEachLine { line ->
            if (line.contains("Server Started") || line.contains("Adapter")) {
                updateStatus(line.take(60))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
