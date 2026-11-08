package com.tiktokmusic.clean

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvIslandStatus: TextView
    private lateinit var switchIsland: SwitchCompat
    private lateinit var btnOpenTikTok: Button
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tràn viền giao diện Dark hiện đại
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.parseColor("#0A0A0C")

        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("origin_island_prefs", Context.MODE_PRIVATE)

        tvIslandStatus = findViewById(R.id.tvIslandStatus)
        switchIsland = findViewById(R.id.switchIsland)
        btnOpenTikTok = findViewById(R.id.btnOpenTikTok)

        checkNotificationPermission()

        val isEnabled = prefs.getBoolean("island_enabled", false)
        switchIsland.isChecked = isEnabled
        updateStatusUI(isEnabled)

        switchIsland.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("island_enabled", isChecked).apply()
            updateStatusUI(isChecked)
            if (isChecked) {
                startOriginIsland()
            } else {
                stopOriginIsland()
            }
        }

        btnOpenTikTok.setOnClickListener {
            launchTikTokApp()
        }

        // Tự động kích hoạt nếu trước đó đã bật
        if (isEnabled) {
            startOriginIsland()
        }
    }

    private fun updateStatusUI(isActive: Boolean) {
        if (isActive) {
            tvIslandStatus.text = "Đảo Origin: ĐANG HOẠT ĐỘNG"
            tvIslandStatus.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvIslandStatus.text = "Đảo Origin: ĐANG TẮT"
            tvIslandStatus.setTextColor(Color.parseColor("#FF5252"))
        }
    }

    private fun startOriginIsland() {
        OriginMediaService.updateTrack(
            this,
            "TikTok Music",
            "Đang phát trên Vivo OriginOS 6",
            "",
            true
        )
        Toast.makeText(this, "Đã kích hoạt Đảo Origin! Hãy mở TikTok và trải nghiệm", Toast.LENGTH_SHORT).show()
    }

    private fun stopOriginIsland() {
        val intent = Intent(this, OriginMediaService::class.java)
        stopService(intent)
        Toast.makeText(this, "Đã tắt Đảo Origin", Toast.LENGTH_SHORT).show()
    }

    private fun launchTikTokApp() {
        // Tìm và mở app TikTok đang cài trên máy (bản mod hoặc bản chính)
        val packageCandidates = listOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.tiktok"
        )

        for (pkg in packageCandidates) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                startActivity(intent)
                return
            }
        }

        // Nếu không tìm thấy theo package mặc định, thử tìm app có tên chứa tiktok
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = packageManager.queryIntentActivities(mainIntent, 0)
            for (app in apps) {
                val appPkg = app.activityInfo.packageName
                if (appPkg.contains("tiktok") || appPkg.contains("musically")) {
                    val intent = packageManager.getLaunchIntentForPackage(appPkg)
                    if (intent != null) {
                        startActivity(intent)
                        return
                    }
                }
            }
        } catch (_: Exception) {}

        Toast.makeText(this, "Không tìm thấy app TikTok trên máy. Hãy cài bản TikTok Mod 424MB để sử dụng!", Toast.LENGTH_LONG).show()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }
}
