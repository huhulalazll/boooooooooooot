package com.qqbot.android

import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

object PluginHelper {

    fun bind(c: Context, root: View) {
        val swSticker = root.findViewById<Switch>(R.id.swSticker)
        val swTts = root.findViewById<Switch>(R.id.swTts)
        val swMusic = root.findViewById<Switch>(R.id.swMusic)
        val swMemory = root.findViewById<Switch>(R.id.swMemory)
        val swBreak = root.findViewById<Switch>(R.id.swBreaklimit)
        val swCustom = root.findViewById<Switch>(R.id.swCustom)
        val layoutCustom = root.findViewById<LinearLayout>(R.id.layoutCustom)
        val etCustom = root.findViewById<EditText>(R.id.etCustom)
        val btnSaveCustom = root.findViewById<android.widget.Button>(R.id.btnSaveCustom)
        val cbAgree = root.findViewById<CheckBox>(R.id.cbAgree)
        val tvStat = root.findViewById<TextView>(R.id.tvStickerStat)

        // 初始化状态
        swSticker.isChecked = ConfigStore.loadFlag(c, "sticker")
        swTts.isChecked = ConfigStore.loadFlag(c, "tts")
        swMusic.isChecked = ConfigStore.loadFlag(c, "music")
        swMemory.isChecked = ConfigStore.loadFlag(c, "memory")
        swBreak.isChecked = ConfigStore.loadFlag(c, "breaklimit")
        swCustom.isChecked = ConfigStore.loadFlag(c, "custom_bl")

        val cnt = FileHelper.countStickers(c)
        tvStat?.text = "图库：$cnt 张"

        // 自定义区默认隐藏（未开启深度角色扮演时）
        layoutCustom?.visibility = if (swBreak.isChecked) View.VISIBLE else View.GONE

        val customFile = File(c.filesDir, "botcfg/custom_bl.txt")
        if (customFile.exists()) etCustom?.setText(customFile.readText())

        fun toggle(sw: Switch, name: String) {
            sw.setOnCheckedChangeListener { _, on ->
                ConfigStore.saveFlag(c, name, on)
                ConfigStore.applyFlag(c, name, on)
                Toast.makeText(c, "${name}: ${if (on) "已开启" else "已关闭"}（重启 bot 生效）",
                    Toast.LENGTH_SHORT).show()
            }
        }

        toggle(swSticker, "sticker")
        toggle(swTts, "tts")
        toggle(swMusic, "music")
        toggle(swMemory, "memory")

        // 破限需要同意条款
        swBreak.setOnCheckedChangeListener { _, on ->
            if (on && cbAgree?.isChecked != true) {
                swBreak.isChecked = false
                Toast.makeText(c, "请先勾选同意条款", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            ConfigStore.saveFlag(c, "breaklimit", on)
            ConfigStore.applyFlag(c, "breaklimit", on)
            layoutCustom?.visibility = if (on) View.VISIBLE else View.GONE
        }

        cbAgree?.setOnCheckedChangeListener { _, checked ->
            if (!checked && swBreak.isChecked) {
                swBreak.isChecked = false
            }
        }

        swCustom?.setOnCheckedChangeListener { _, on ->
            ConfigStore.saveFlag(c, "custom_bl", on)
            ConfigStore.applyFlag(c, "custom_bl", on)
        }

        btnSaveCustom?.setOnClickListener {
            val txt = etCustom?.text?.toString() ?: ""
            if (txt.isBlank()) {
                Toast.makeText(c, "留空则使用内置破限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            customFile.parentFile?.mkdirs()
            customFile.writeText(txt)
            ConfigStore.applyToBot(c, "custom_breaklimit.txt", txt)
            Toast.makeText(c, "已保存自定义破限", Toast.LENGTH_SHORT).show()
        }
    }
}
