package com.qqbot.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

class BotService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val CH_ID = "qqbot"
        private const val NOTI_ID = 1
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var prootProc: Process? = null
    private var curStream: java.io.InputStream? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTI_ID, buildNoti("准备中…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!running) {
                    running = true
                    StatusHelper.botRunning = true
                    StatusHelper.startTime = System.currentTimeMillis()
                    worker = Thread { doStart() }.also { it.start() }
                }
            }
        }
        return START_STICKY
    }

    private fun buildNoti(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ID, "QQBot", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("QQBot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNoti(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTI_ID, buildNoti(text))
        log("[STATUS] $text")
    }

    private fun log(s: String) {
        try {
            FileOutputStream(File(filesDir, "bot.log"), true).use {
                it.write("${System.currentTimeMillis()} $s\n".toByteArray())
            }
        } catch (_: Exception) {}
    }

    private fun doStart() {
        try {
            // 1. proot 准备
            updateNoti("准备 proot…")
            if (!FileHelper.prepareProot(this)) {
                updateNoti("proot 准备失败")
                return
            }

            // 2. rootfs 检查
            if (!FileHelper.rootfsReady(this)) {
                updateNoti("首次启动：正在解压 rootfs（需几分钟）…")
                if (!unpackRootfs()) {
                    updateNoti("rootfs 解压失败")
                    return
                }
            }

            // 3. machine-id
            updateNoti("初始化环境…")
            initMachineId()

            // 4. 同步配置
            updateNoti("写入配置…")
            ConfigStore.syncAll(this)

            // 5. 启动 NapCat
            updateNoti("启动 NapCat…")
            launchProot("cd /root/napcat_env && bash launcher.sh -q \$(cat /root/mybot/qq.txt 2>/dev/null || echo '')", "napcat")

            // 等待端口就绪
            waitPort(3000, 90000)

            // 6. 启动 bot
            updateNoti("启动 Bot…")
            launchProot("cd /root/mybot && python3 bot.py", "bot")

            updateNoti("运行中")

        } catch (e: Exception) {
            updateNoti("启动异常: ${e.message}")
            log("[ERR] ${e.stackTraceToString()}")
        }
    }

    private fun unpackRootfs(): Boolean {
        return try {
            val dst = FileHelper.rootfsDir(this)
            dst.mkdirs()

            // assets 里的 rootfs.tar.zst 需要用 zstd 解压
            // 但 Android 没有 zstd 命令，所以采用：assets 存 gz 版本
            val assetName = if (assetExists("rootfs.tar.gz")) "rootfs.tar.gz"
                           else "rootfs.tar.zst"

            log("[UNPACK] 使用 $assetName")

            if (assetName.endsWith(".gz")) {
                assets.open(assetName).use { ins ->
                    GZIPInputStream(ins).use { gz ->
                        curStream = gz
                        extractTar(gz, dst)
                    }
                }
            } else {
                // zst：用 zstd-jni 流式解压
                assets.open(assetName).use { ins ->
                    com.github.luben.zstd.ZstdInputStream(ins).use { zs ->
                        java.io.BufferedInputStream(zs, 1 shl 16).use { bz ->
                            curStream = bz
                            extractTar(bz, dst)
                        }
                    }
                }
            }
            return FileHelper.rootfsReady(this)
        } catch (e: Exception) {
            log("[UNPACK_ERR] ${e.message}")
            false
        }
    }

    private fun assetExists(name: String): Boolean = try {
        assets.open(name).close(); true
    } catch (_: Exception) { false }

    /** 极简 tar 解压（支持常规文件+目录+符号链接） */
    private fun extractTar(ins: java.io.InputStream, dstDir: File) {
        val buf = ByteArray(512)
        var longName: String? = null

        fun readBlock(): Boolean {
            var read = 0
            while (read < 512) {
                val n = ins.read(buf, read, 512 - read)
                if (n <= 0) return false
                read += n
            }
            return buf.any { it.toInt() != 0 }
        }

        fun str(off: Int, len: Int): String {
            var end = off
            while (end < off + len && buf[end].toInt() != 0) end++
            return String(buf, off, end - off, Charsets.UTF_8)
        }

        fun octal(off: Int, len: Int): Long {
            val s = str(off, len).trim()
            return if (s.isEmpty()) 0 else s.toLongOrNull(8) ?: 0
        }

        while (readBlock()) {
            val name0 = str(0, 100)
            val size = octal(124, 12)
            val type = buf[156].toInt().toChar()
            val prefix = str(345, 155)

            var name = if (prefix.isNotEmpty()) "$prefix/$name0" else name0
            if (longName != null) { name = longName!!; longName = null }

            val out = File(dstDir, name)

            when (type) {
                'L' -> { // GNU long name
                    val sb = ByteArray(size.toInt())
                    var r = 0
                    while (r < sb.size) { val n = ins.read(sb, r, sb.size - r); if (n <= 0) break; r += n }
                    longName = String(sb, Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                '5' -> { out.mkdirs(); skipData(size) }
                '2' -> {
                    out.parentFile?.mkdirs()
                    val target = str(157, 100)
                    try { File.createTempFile("lnk", "", out.parentFile).delete() } catch (_: Exception) {}
                    Runtime.getRuntime().exec(arrayOf("ln", "-sf", target, out.absolutePath)).waitFor()
                    skipData(size)
                }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        var left = size
                        while (left > 0) {
                            val n = ins.read(buf, 0, minOf(512L, left).toInt())
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            left -= n
                        }
                    }
                    out.setLastModified(out.lastModified())
                }
                else -> skipData(size)
            }

            // 对齐到 512
            val pad = (512 - (size % 512)) % 512
            if (pad > 0) skipData(pad)
        }
    }

    private fun skipData(size: Long) {
        var left = size
        val tmp = ByteArray(8192)
        val s = curStream ?: return
        while (left > 0) {
            val n = s.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (n <= 0) break
            left -= n
        }
    }

    private fun initMachineId() {
        val f = File(FileHelper.rootfsDir(this), "etc/machine-id")
        if (!f.exists() || f.readText().trim().length != 32) {
            val id = java.util.UUID.randomUUID().toString().replace("-", "")
            f.parentFile?.mkdirs()
            f.writeText("$id\n")
            val d = File(FileHelper.rootfsDir(this), "var/lib/dbus/machine-id")
            d.parentFile?.mkdirs()
            d.writeText("$id\n")
            log("[MID] 生成 $id")
        }
    }

    private fun launchProot(cmdInside: String, tag: String): Process? {
        val bin = FileHelper.binDir(this)
        val proot = File(bin, "proot").absolutePath
        val rootfs = FileHelper.rootfsDir(this).absolutePath
        val shm = FileHelper.shmDir(this).absolutePath
        val tmp = FileHelper.tmpDir(this).absolutePath

        val args = listOf(
            proot, "-0", "-r", rootfs,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "$shm:/dev/shm",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PROOT_TMP_DIR=$tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "/bin/bash", "-c", cmdInside
        )

        val pb = ProcessBuilder(args)
        pb.environment()["PROOT_TMP_DIR"] = tmp
        pb.environment()["HOME"] = filesDir.absolutePath

        val p = pb.start()
        if (tag == "napcat") prootProc = p

        Thread {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                    lines.forEach { log("[$tag] $it") }
                }
            } catch (_: Exception) {}
        }.start()

        return p
    }

    private fun waitPort(port: Int, timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                java.net.Socket("127.0.0.1", port).use { return }
            } catch (_: Exception) {
                Thread.sleep(1500)
            }
        }
    }

    private fun stopAll() {
        running = false
        StatusHelper.botRunning = false
        try { prootProc?.destroy() } catch (_: Exception) {}
        try {
            Runtime.getRuntime().exec(arrayOf("pkill", "-f", "proot")).waitFor()
        } catch (_: Exception) {}
        log("[STOP] 已停止")
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
