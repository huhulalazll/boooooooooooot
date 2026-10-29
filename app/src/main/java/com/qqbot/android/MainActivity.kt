package com.qqbot.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var content: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private var currentTab = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.content)
        bottomNav = findViewById(R.id.bottomNav)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { showPage(0); true }
                R.id.nav_persona -> { showPage(1); true }
                R.id.nav_start -> { showPage(2); true }
                R.id.nav_plugin -> { showPage(3); true }
                R.id.nav_settings -> { showPage(4); true }
                else -> false
            }
        }

        askPermissions()
        showPage(0)
    }

    private fun showPage(index: Int) {
        if (currentTab == index) return
        currentTab = index

        val layoutId = when (index) {
            0 -> R.layout.page_home
            1 -> R.layout.page_persona
            2 -> R.layout.page_start
            3 -> R.layout.page_plugin
            else -> R.layout.page_settings
        }

        content.removeAllViews()
        LayoutInflater.from(this).inflate(layoutId, content, true)

        when (index) {
            0 -> bindHome(content)
            1 -> bindPersona(content)
            2 -> bindStart(content)
            3 -> bindPlugin(content)
            4 -> bindSettings(content)
        }
    }

    private fun bindHome(root: View) {
        val btnRefresh = root.findViewById<android.widget.Button>(R.id.btnRefresh)
        btnRefresh?.setOnClickListener {
            StatusHelper.refresh(this, root)
        }
        StatusHelper.refresh(this, root)
    }

    private fun bindPersona(root: View) {
        val etPersona = root.findViewById<android.widget.EditText>(R.id.etPersona)
        val tvLen = root.findViewById<android.widget.TextView>(R.id.tvPersonaLen)
        val presetRow = root.findViewById<android.widget.LinearLayout>(R.id.presetRow)
        val btnSave = root.findViewById<android.widget.Button>(R.id.btnSavePersona)

        val personas = PresetPersonas.all
        personas.forEach { (name, text) ->
            val btn = android.widget.Button(this)
            btn.text = name
            btn.textSize = 12f
            btn.setOnClickListener {
                etPersona.setText(text)
                tvLen.text = "${text.length} 字符"
            }
            presetRow.addView(btn)
        }

        val saved = ConfigStore.loadPersona(this)
        if (saved.isNotBlank()) {
            etPersona.setText(saved)
            tvLen.text = "${saved.length} 字符"
        }

        etPersona?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                tvLen?.text = "${s?.length ?: 0} 字符"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnSave?.setOnClickListener {
            val txt = etPersona?.text?.toString() ?: ""
            if (txt.isBlank()) {
                android.widget.Toast.makeText(this, "人格不能为空", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ConfigStore.savePersona(this, txt)
            ConfigStore.applyToBot(this, "persona.txt", txt)
            android.widget.Toast.makeText(this, "已保存，重启 bot 后生效", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val REQ_PICK_ROOTFS = 1001

    private fun bindStart(root: View) {
        val etKey = root.findViewById<android.widget.EditText>(R.id.etApiKey)
        val etQq = root.findViewById<android.widget.EditText>(R.id.etQq)
        val btnStart = root.findViewById<android.widget.Button>(R.id.btnStart)
        val btnStop = root.findViewById<android.widget.Button>(R.id.btnStop)
        val btnPick = root.findViewById<android.widget.Button>(R.id.btnPickRootfs)
        val tvRootfs = root.findViewById<android.widget.TextView>(R.id.tvRootfsStatus)

        etKey?.setText(ConfigStore.loadApiKey(this))
        etQq?.setText(ConfigStore.loadQq(this))

        updateRootfsStatus(tvRootfs)

        btnPick?.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_PICK_ROOTFS)
        }

        btnStart?.setOnClickListener {
            val k = etKey?.text?.toString()?.trim() ?: ""
            val q = etQq?.text?.toString()?.trim() ?: ""
            if (k.isBlank()) {
                android.widget.Toast.makeText(this, "请填写 API Key", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val savedPath = ConfigStore.loadRootfsPath(this)
            if (savedPath.isBlank() && !FileHelper.rootfsReady(this)) {
                android.widget.Toast.makeText(this, "请先选择 rootfs 文件", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ConfigStore.saveApiKey(this, k)
            ConfigStore.saveQq(this, q)

            showProgress(true)
            val i = Intent(this, BotService::class.java)
            i.action = BotService.ACTION_START
            ContextCompat.startForegroundService(this, i)
            android.widget.Toast.makeText(this, "启动中…请稍候", android.widget.Toast.LENGTH_SHORT).show()
        }

        btnStop?.setOnClickListener {
            val i = Intent(this, BotService::class.java)
            i.action = BotService.ACTION_STOP
            startService(i)
            showProgress(false)
            android.widget.Toast.makeText(this, "已停止", android.widget.Toast.LENGTH_SHORT).show()
        }

        // 轮询更新进度
        startProgressPolling(root)
    }

    private fun updateRootfsStatus(tv: android.widget.TextView?) {
        val p = ConfigStore.loadRootfsPath(this)
        val ready = FileHelper.rootfsReady(this)
        tv?.text = when {
            ready -> "✓ 已解压可用"
            p.isNotBlank() -> "已选择：${java.io.File(p).name}"
            else -> "未选择（需要 rootfs.tar.zst，约 808MB）"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_ROOTFS && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            ConfigStore.saveRootfsUri(this, uri.toString())
            ConfigStore.saveRootfsPath(this, uri.toString())
            android.widget.Toast.makeText(this, "已选择：${uri.lastPathSegment}", android.widget.Toast.LENGTH_SHORT).show()

            // 刷新界面
            currentTab = -1
            showPage(2)
        }
    }

    private fun showProgress(visible: Boolean) {
        val lp = content.findViewById<android.widget.LinearLayout>(R.id.layoutProgress)
        lp?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private var polling = false
    private fun startProgressPolling(root: View) {
        if (polling) return
        polling = true
        val pb = root.findViewById<android.widget.ProgressBar>(R.id.pbProgress)
        val tvText = root.findViewById<android.widget.TextView>(R.id.tvProgressText)
        val lp = root.findViewById<android.widget.LinearLayout>(R.id.layoutProgress)

        Thread {
            while (polling && !isFinishing) {
                val pct = BotService.progressPct
                val msg = BotService.progressMsg
                runOnUiThread {
                    if (pct >= 0) {
                        lp?.visibility = View.VISIBLE
                        pb?.progress = pct
                        tvText?.text = msg
                    }
                }
                Thread.sleep(800)
            }
        }.start()
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }

    private fun bindPlugin(root: View) {
        PluginHelper.bind(this, root)
    }

    private fun bindSettings(root: View) {
        val sbTemp = root.findViewById<android.widget.SeekBar>(R.id.sbTemp)
        val tvLabel = root.findViewById<android.widget.TextView>(R.id.tvTempLabel)

        val savedTemp = ConfigStore.loadTemp(this)
        sbTemp?.progress = (savedTemp * 10).toInt()
        tvLabel?.text = "Temperature: $savedTemp"

        sbTemp?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val t = p / 10f
                tvLabel?.text = "Temperature: $t"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                val t = (sb?.progress ?: 11) / 10f
                ConfigStore.saveTemp(this@MainActivity, t)
            }
        })

        root.findViewById<android.widget.Button>(R.id.btnLog)?.setOnClickListener {
            val log = FileHelper.readLog(this)
            android.app.AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setMessage(if (log.isBlank()) "暂无日志" else log.takeLast(3000))
                .setPositiveButton("关闭", null)
                .show()
        }

        root.findViewById<android.widget.Button>(R.id.btnClearMemory)?.setOnClickListener {
            FileHelper.clearDir(this, "rootfs/root/mybot/memory")
            android.widget.Toast.makeText(this, "已清除", android.widget.Toast.LENGTH_SHORT).show()
        }

        root.findViewById<android.widget.Button>(R.id.btnClearSticker)?.setOnClickListener {
            FileHelper.clearStickers(this)
            android.widget.Toast.makeText(this, "已清空图库", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun askPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        }
    }
}
