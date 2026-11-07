package com.tiktokmusic.clean

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * KeepAliveWebView: Giữ luồng phát nhạc/video HTML5 & bộ xử lý âm thanh Web Audio Dolby Atmos
 * chạy ngầm 100% liên tục kể cả khi người dùng thoát ra màn hình chính hoặc tắt màn hình khóa máy.
 */
class KeepAliveWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        // Luôn báo trạng thái VISIBLE cho nhân Chromium để không bao giờ tự ngắt nhạc khi chạy ngầm
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

class MainActivity : AppCompatActivity() {

    companion object {
        private var activeWebViewRef: WeakReference<WebView>? = null

        fun dispatchMediaAction(action: String, value: String = "0") {
            val wv = activeWebViewRef?.get() ?: return
            wv.post {
                val safeAction = action.replace("'", "")
                val safeVal = value.replace("'", "")
                wv.evaluateJavascript(
                    "window.__apkMediaControl && window.__apkMediaControl('$safeAction', '$safeVal');",
                    null
                )
            }
        }
    }

    private lateinit var webView: KeepAliveWebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout
    private var isLandscapeLocked: Boolean = false

    // Ưu tiên nạp trực tiếp từ CDN tốc độ cao của Hugging Face Space, tự động fallback về bộ nhớ trong của APK nếu mất mạng
    private val ONLINE_URL = "https://binh007-tik-music.static.hf.space/?v=20261108v2"
    private val OFFLINE_URL = "file:///android_asset/index.html"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        window.statusBarColor = Color.parseColor("#07080d")
        window.navigationBarColor = Color.parseColor("#07080d")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestNotificationPermissionIfNeeded()
        startOriginMediaService()

        val rootLayout = FrameLayout(this)
        rootLayout.setBackgroundColor(Color.parseColor("#07080d"))

        fullscreenContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        webView = KeepAliveWebView(this).apply {
            setBackgroundColor(Color.parseColor("#07080d"))
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        activeWebViewRef = WeakReference(webView)

        rootLayout.addView(webView)
        rootLayout.addView(fullscreenContainer)
        setContentView(rootLayout)

        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null || isLandscapeLocked) {
                    if (customView != null) {
                        hideCustomView()
                    }
                    setLandscapeMode(false)
                    webView.evaluateJavascript("window.exitLandscapeFullscreen && window.exitLandscapeFullscreen();", null)
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Khi bấm Back ở màn hình đầu tiên: thu nhỏ ứng dụng xuống chạy ngầm (giống trình phát nhạc thực thụ) thay vì đóng hẳn!
                    moveTaskToBack(true)
                }
            }
        })

        webView.loadUrl(ONLINE_URL)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    private fun startOriginMediaService() {
        try {
            val serviceIntent = Intent(this, OriginMediaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }

    private fun setLandscapeMode(landscape: Boolean) {
        runOnUiThread {
            isLandscapeLocked = landscape
            if (landscape) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                hideSystemUI()
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                showSystemUI()
            }
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun setLandscape(landscape: Boolean) {
            setLandscapeMode(landscape)
        }

        @JavascriptInterface
        fun updateMediaState(
            title: String?,
            artist: String?,
            coverUrl: String?,
            isPlaying: Boolean,
            posMs: Long,
            durMs: Long
        ) {
            try {
                val intent = Intent(this@MainActivity, OriginMediaService::class.java).apply {
                    action = OriginMediaService.ACTION_UPDATE
                    putExtra("title", title ?: "TikTok Music Clean")
                    putExtra("artist", artist ?: "@tiktok")
                    putExtra("coverUrl", coverUrl ?: "")
                    putExtra("isPlaying", isPlaying)
                    putExtra("posMs", posMs)
                    putExtra("durMs", durMs)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file:///")) {
                    view?.loadUrl(url)
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    view?.loadUrl(OFFLINE_URL)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                setLandscapeMode(true)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }
    }

    private fun hideCustomView() {
        if (customView == null) return
        fullscreenContainer.visibility = View.GONE
        fullscreenContainer.removeView(customView)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
        setLandscapeMode(false)
        webView.evaluateJavascript("window.exitLandscapeFullscreen && window.exitLandscapeFullscreen();", null)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        // TUYỆT ĐỐI KHÔNG gọi webView.onPause() để nhạc & video vẫn tiếp tục phát ngầm và hiển thị trên Đảo Origin (OriginOS 6) khi thoát ra ngoài!
        webView.resumeTimers()
    }

    override fun onStop() {
        super.onStop()
        webView.resumeTimers()
    }

    override fun onDestroy() {
        try {
            stopService(Intent(this, OriginMediaService::class.java))
        } catch (_: Exception) {}
        webView.destroy()
        super.onDestroy()
    }
}
