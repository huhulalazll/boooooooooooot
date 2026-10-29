package com.qqbot.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var etQqNumber: EditText
    private lateinit var etPersona: EditText
    private lateinit var btnStart: Button
    private lateinit var ivQr: ImageView
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        etQqNumber = findViewById(R.id.etQqNumber)
        etPersona = findViewById(R.id.etPersona)
        btnStart = findViewById(R.id.btnStart)
        ivQr = findViewById(R.id.ivQr)
        tvStatus = findViewById(R.id.tvStatus)

        loadConfig()
        askPermissions()

        btnStart.setOnClickListener {
            saveConfig()
            tvStatus.text = "正在启动..."
            val intent = Intent(this, BotService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun configDir(): File = File(filesDir, "botcfg").apply { mkdirs() }

    private fun loadConfig() {
        val d = configDir()
        val keyF = File(d, "api_key.txt")
        if (keyF.exists()) etApiKey.setText(keyF.readText())
        val qqF = File(d, "qq.txt")
        if (qqF.exists()) etQqNumber.setText(qqF.readText())
        val pF = File(d, "persona.txt")
        if (pF.exists()) etPersona.setText(pF.readText())
    }

    private fun saveConfig() {
        val d = configDir()
        File(d, "api_key.txt").writeText(etApiKey.text.toString())
        File(d, "qq.txt").writeText(etQqNumber.text.toString())
        File(d, "persona.txt").writeText(etPersona.text.toString())
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
