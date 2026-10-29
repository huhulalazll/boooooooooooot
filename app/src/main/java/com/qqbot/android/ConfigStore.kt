package com.qqbot.android

import android.content.Context
import java.io.File

object ConfigStore {
    private fun dir(c: Context) = File(c.filesDir, "botcfg").apply { mkdirs() }
    private fun f(c: Context, n: String) = File(dir(c), n)

    fun loadApiKey(c: Context): String = f(c, "api_key.txt").takeIf { it.exists() }?.readText() ?: ""
    fun saveApiKey(c: Context, v: String) = f(c, "api_key.txt").writeText(v)

    fun loadQq(c: Context): String = f(c, "qq.txt").takeIf { it.exists() }?.readText() ?: ""
    fun saveQq(c: Context, v: String) = f(c, "qq.txt").writeText(v)

    fun loadPersona(c: Context): String = f(c, "persona.txt").takeIf { it.exists() }?.readText() ?: ""
    fun savePersona(c: Context, v: String) = f(c, "persona.txt").writeText(v)

    fun loadTemp(c: Context): Float {
        val s = f(c, "temp.txt").takeIf { it.exists() }?.readText() ?: "1.1"
        return s.toFloatOrNull() ?: 1.1f
    }
    fun saveTemp(c: Context, v: Float) = f(c, "temp.txt").writeText(v.toString())

    fun loadRootfsUri(c: Context): String =
        f(c, "rootfs_uri.txt").takeIf { it.exists() }?.readText() ?: ""
    fun saveRootfsUri(c: Context, v: String) = f(c, "rootfs_uri.txt").writeText(v)

    fun loadRootfsPath(c: Context): String =
        f(c, "rootfs_path.txt").takeIf { it.exists() }?.readText() ?: ""
    fun saveRootfsPath(c: Context, v: String) = f(c, "rootfs_path.txt").writeText(v)


    fun loadFlag(c: Context, name: String): Boolean =
        f(c, "flag_$name.txt").takeIf { it.exists() }?.readText()?.trim() == "1"

    fun saveFlag(c: Context, name: String, on: Boolean) =
        f(c, "flag_$name.txt").writeText(if (on) "1" else "0")

    /** 把配置写入 rootfs 内的文件 */
    fun applyToBot(c: Context, fileName: String, content: String) {
        val root = File(c.filesDir, "rootfs/root/mybot")
        if (!root.exists()) return
        File(root, fileName).writeText(content)
    }

    fun applyFlag(c: Context, name: String, on: Boolean) {
        val root = File(c.filesDir, "rootfs/root/mybot/flags")
        if (!root.exists()) return
        File(root, "$name.flag").writeText(if (on) "1" else "0")
    }

    /** 同步所有配置到 rootfs */
    fun syncAll(c: Context) {
        applyToBot(c, "persona.txt", loadPersona(c))
        applyToBot(c, "api_key.txt", loadApiKey(c))
        applyToBot(c, "qq.txt", loadQq(c))
        applyToBot(c, "custom_breaklimit.txt",
            f(c, "custom_bl.txt").takeIf { it.exists() }?.readText() ?: "")

        listOf("sticker", "tts", "music", "memory", "breaklimit", "custom_bl").forEach {
            applyFlag(c, it, loadFlag(c, it))
        }
    }
}
