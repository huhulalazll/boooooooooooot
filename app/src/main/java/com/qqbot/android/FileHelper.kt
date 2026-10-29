package com.qqbot.android

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FileHelper {

    fun rootfsDir(c: Context) = File(c.filesDir, "rootfs")
    fun shmDir(c: Context) = File(c.filesDir, "shm").apply { mkdirs() }
    fun tmpDir(c: Context) = File(c.filesDir, "proot_tmp").apply { mkdirs() }
    fun binDir(c: Context) = File(c.filesDir, "prootbin")

    fun rootfsReady(c: Context): Boolean =
        File(rootfsDir(c), "opt/QQ/qq").exists()

    /** 从 assets 复制 proot 相关文件到私有目录 */
    fun prepareProot(c: Context): Boolean {
        return try {
            val dst = binDir(c)
            dst.mkdirs()
            copyAsset(c, "proot/proot", File(dst, "proot"))
            copyAsset(c, "proot/proot-userland", File(dst, "proot-userland"))
            val le = File(dst, "libexec/proot")
            le.mkdirs()
            copyAsset(c, "proot/libexec/proot/loader", File(le, "loader"))
            copyAsset(c, "proot/libexec/proot/loader32", File(le, "loader32"))

            listOf(File(dst, "proot"), File(dst, "proot-userland"),
                File(le, "loader"), File(le, "loader32")).forEach {
                it.setExecutable(true, false)
                it.setReadable(true, false)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun copyAsset(c: Context, name: String, out: File) {
        if (out.exists() && out.length() > 0) return
        c.assets.open(name).use { ins ->
            FileOutputStream(out).use { fos -> ins.copyTo(fos) }
        }
    }

    fun readLog(c: Context): String {
        val f = File(c.filesDir, "bot.log")
        return if (f.exists()) f.readText().takeLast(5000) else ""
    }

    fun clearDir(c: Context, relPath: String) {
        val d = File(c.filesDir, relPath)
        if (d.exists()) d.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun clearStickers(c: Context) {
        val d = File(c.filesDir, "rootfs/root/mybot/stickers")
        if (d.exists()) {
            d.listFiles()?.forEach { f ->
                if (f.isDirectory) f.listFiles()?.forEach { it.delete() }
            }
        }
    }

    fun countStickers(c: Context): Int {
        val d = File(c.filesDir, "rootfs/root/mybot/stickers")
        if (!d.exists()) return 0
        var n = 0
        d.walkTopDown().forEach { if (it.isFile && !it.name.startsWith("index")) n++ }
        return n
    }
}
