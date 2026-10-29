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

    /** proot 从 nativeLibraryDir 取（Android 只允许这里执行二进制） */
    fun prootPath(c: Context): String = File(c.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    fun prootLoaderPath(c: Context): String = File(c.applicationInfo.nativeLibraryDir, "libproot_loader.so").absolutePath

    /** 把 loader 复制到指定目录（proot 需要 loader 在 -L 指定位置） */
    fun prepareProot(c: Context): Boolean {
        return try {
            val loaderSrc = File(prootLoaderPath(c))
            if (!loaderSrc.exists()) return false
            // loader 需要在 proot 可执行位置附近，复制到 tmp 目录
            val dst = File(c.filesDir, "proot_loader")
            dst.parentFile?.mkdirs()
            loaderSrc.copyTo(dst, overwrite = true)
            dst.setExecutable(true, false)
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
