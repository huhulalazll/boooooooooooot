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

        @Volatile var progressPct: Int = -1
        @Volatile var progressMsg: String = ""
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
        progressMsg = text
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTI_ID, buildNoti(text))
        log("[STATUS] $text")
    }

    private fun setProgress(pct: Int, text: String) {
        progressPct = pct
        updateNoti(text)
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

            val uriStr = ConfigStore.loadRootfsUri(this)
            if (uriStr.isBlank()) {
                setProgress(-1, "未选择 rootfs 文件")
                return false
            }

            val uri = android.net.Uri.parse(uriStr)
            val ins0 = contentResolver.openInputStream(uri) ?: run {
                setProgress(-1, "无法读取文件")
                return false
            }

            val totalSize: Long = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (_: Exception) { -1L }

            setProgress(1, "正在解压系统文件…")

            val counting = object : java.io.FilterInputStream(ins0) {
                var read = 0L
                var lastPct = -1
                override fun read(): Int {
                    val b = super.read()
                    if (b >= 0) {
                        read++
                        maybeReport()
                    }
                    return b
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        read += n
                        maybeReport()
                    }
                    return n
                }
                fun maybeReport() {
                    if (totalSize <= 0) return
                    val pct = ((read * 100) / totalSize).toInt()
                    if (pct != lastPct && pct % 2 == 0) {
                        lastPct = pct
                        val mb = read / 1024 / 1024
                        val totMb = totalSize / 1024 / 1024
                        setProgress(pct, "解压中 $pct% ($mb/$totMb MB)")
                    }
                }
            }

            val zs = com.github.luben.zstd.ZstdInputStream(counting)
            val bz = java.io.BufferedInputStream(zs, 1 shl 16)
            curStream = bz
            extractTar(bz, dst)
            bz.close()

            setProgress(100, "解压完成")
            val ok = FileHelper.rootfsReady(this)
            if (!ok) setProgress(-1, "解压后验证失败")
            ok
        } catch (e: Exception) {
            log("[UNPACK_ERR] ${e.stackTraceToString()}")
            setProgress(-1, "解压失败: ${e.message}")
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
        var count = 0L
        var lastOffset = 0L

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
            val mode = octal(100, 8)
            val size = octal(124, 12)
            val type = buf[156].toInt().toChar()
            val prefix = str(345, 155)

            var name = if (prefix.isNotEmpty()) "$prefix/$name0" else name0
            if (longName != null) { name = longName!!; longName = null }

            count++
            // 诊断：异常路径或每 2000 条打印
            if (name.contains(":") || name.length > 250 || name.isEmpty() ||
                !name.startsWith(".") && !name.startsWith("/") ||
                name.contains("\u0000")) {
                log("[TAR_ANOMALY] #$count type=$type size=$size name=${name.take(120)}")
            } else if (count % 2000L == 0L) {
                log("[TAR] #$count ${name.take(80)}")
            }

            val out = File(dstDir, name)

            when (type) {
                'L' -> { // GNU long name
                    val sb = ByteArray(size.toInt())
                    var r = 0
                    while (r < sb.size) { val n = ins.read(sb, r, sb.size - r); if (n <= 0) break; r += n }
                    longName = String(sb, Charsets.UTF_8).trimEnd('\u0000')
                    val lpad = (512 - (size % 512)) % 512
                    if (lpad > 0) skipData(lpad)
                    lastOffset += 512 + size + lpad
                    continue
                }
                '5' -> { out.mkdirs(); skipData(size) }
                '2' -> {
                    // 符号链接：用 os.symlink（android.system.Os）
                    out.parentFile?.mkdirs()
                    val target = str(157, 100)
                    try {
                        if (out.exists()) out.delete()
                        android.system.Os.symlink(target, out.absolutePath)
                    } catch (e: Exception) {
                        // 失败则创建普通文件占位，保证后续数据流不错位
                        try { out.writeText(target) } catch (_: Exception) {}
                    }
                    skipData(size)
                }
                '0', '\u0000', '7' -> {
                    // 先判断目标是否是已存在的目录
                    if (out.exists() && out.isDirectory) {
                        skipData(size)
                    } else if (name.endsWith("/")) {
                        out.mkdirs()
                        skipData(size)
                    } else {
                        out.parentFile?.mkdirs()
                        try {
                            FileOutputStream(out).use { fos ->
                                var left = size
                                while (left > 0) {
                                    val n = ins.read(buf, 0, minOf(512L, left).toInt())
                                    if (n <= 0) break
                                    fos.write(buf, 0, n)
                                    left -= n
                                }
                            }
                        } catch (e: Exception) {
                            // 写入失败，仍需跳过剩余数据
                            log("[TAR] skip $name: ${e.message}")
                            var left = size
                            while (left > 0) {
                                val n = ins.read(buf, 0, minOf(512L, left).toInt())
                                if (n <= 0) break
                                left -= n
                            }
                        }
                        // 恢复文件权限
                        try {
                            val m = mode.toInt()
                            if (m and 0b001001001 != 0) out.setExecutable(true, false)
                            if (m and 0b100100100 != 0) out.setReadable(true, false)
                            if (m and 0b010010010 != 0) out.setWritable(true, false)
                        } catch (_: Exception) {}
                    }
                }
                else -> skipData(size)
            }

            // 对齐到 512
            val pad = (512 - (size % 512)) % 512
            if (pad > 0) skipData(pad)
            lastOffset += 512 + size + pad
        }
        log("[TAR_DONE] 共 $count 条，数据流偏移 $lastOffset")
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

    private fun dumpDiagnostics(tag: String, proot: String, loader: String, rootfs: String, tmp: String, shm: String) {
        log("[$tag] ===== DIAG =====")
        log("[$tag] SELinux=${try { File("/proc/self/attr/current").readText().trim() } catch (e: Exception) { "?" }}")
        log("[$tag] LD_LIBRARY_PATH=${System.getenv("LD_LIBRARY_PATH") ?: "null"}")
        log("[$tag] TMPDIR=${System.getenv("TMPDIR") ?: "null"}")
        log("[$tag] proot=${proot}")
        log("[$tag] proot.exists=${File(proot).exists()} proot.canExec=${File(proot).canExecute()}")
        log("[$tag] loader=${loader}")
        log("[$tag] loader.exists=${File(loader).exists()}")
        log("[$tag] rootfs=${rootfs} isDir=${File(rootfs).isDirectory}")
        log("[$tag] tmp=${tmp} exists=${File(tmp).exists()}")
        log("[$tag] shm=${shm} exists=${File(shm).exists()}")
        log("[$tag] rootfs/bin/bash exists=${File("$rootfs/bin/bash").exists()}")
        log("[$tag] rootfs/opt/QQ/qq exists=${File("$rootfs/opt/QQ/qq").exists()}")
        log("[$tag] rootfs/root/napcat_env/launcher.sh exists=${File("$rootfs/root/napcat_env/launcher.sh").exists()}")
        log("[$tag] =================")
    }

    private fun launchProot(cmdInside: String, tag: String): Process? {
        val proot = FileHelper.prootPath(this)
        val loader = FileHelper.prootLoaderPath(this)
        val rootfs = FileHelper.rootfsDir(this).absolutePath
        val shm = FileHelper.shmDir(this).absolutePath
        val tmp = FileHelper.tmpDir(this).absolutePath

        val args = listOf(
            proot, "-0",
            "-r", rootfs,
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

        dumpDiagnostics(tag, proot, loader, rootfs, tmp, shm)

        // 写启动脚本（引号处理简化：直接拼接，命令内部不含空格参数）
        val script = File(filesDir, "run_$tag.sh")
        val cmdLine = args.joinToString(" ")
        script.writeText(
            "#!/system/bin/sh\n" +
            "export PROOT_TMP_DIR='" + tmp + "'\n" +
            "export PROOT_LOADER='" + loader + "'\n" +
            "export HOME='/root'\n" +
            "exec " + cmdLine + " 2>&1\n"
        )
        script.setExecutable(true, false)

        log("[$tag] SCRIPT_CONTENT:")
        log(script.readText().take(500))

        val pb = ProcessBuilder("/system/bin/sh", script.absolutePath)
        pb.directory(filesDir)
        // 只补充关键变量，不清空
        pb.environment()["PROOT_TMP_DIR"] = tmp
        pb.environment()["PROOT_LOADER"] = loader
        pb.environment()["TMPDIR"] = tmp
        pb.redirectErrorStream(true)

        val p = try {
            pb.start()
        } catch (e: Exception) {
            log("[$tag] START_FAIL: ${e.message}")
            return null
        }
        if (tag == "napcat") prootProc = p

        Thread {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                    lines.forEach { log("[$tag] $it") }
                }
                val code = p.waitFor()
                log("[$tag] EXIT_CODE=$code")
                if (code != 0) updateNoti("$tag 退出码 $code")
            } catch (e: Exception) {
                log("[$tag] READ_ERR: ${e.message}")
            }
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
