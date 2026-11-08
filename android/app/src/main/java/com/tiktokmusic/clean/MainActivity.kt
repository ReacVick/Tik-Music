package com.tiktokmusic.clean

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    companion object {
        private var instance: MainActivity? = null

        fun dispatchMediaAction(action: String) {
            val act = instance ?: return
            act.runOnUiThread {
                when (action) {
                    "play" -> act.evalJs("const v=document.querySelector('video');if(v){v.muted=false;v.play().catch(()=>{});}")
                    "pause" -> act.evalJs("document.querySelector('video')?.pause();")
                    "toggle" -> act.evalJs("const v=document.querySelector('video');if(v){if(v.paused){v.muted=false;v.play().catch(()=>{});}else{v.pause();}}")
                    "next" -> act.evalJs("window.scrollBy({top:window.innerHeight,behavior:'smooth'});setTimeout(()=>{const v=document.querySelector('video');if(v){v.muted=false;v.play().catch(()=>{});}},400);")
                    "prev" -> act.evalJs("window.scrollBy({top:-window.innerHeight,behavior:'smooth'});setTimeout(()=>{const v=document.querySelector('video');if(v){v.muted=false;v.play().catch(()=>{});}},400);")
                }
            }
        }

        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        // CSS loại bỏ 100% TikTok Shop & Banner tải app mà KHÔNG can thiệp vào Video Player
        private const val PURGE_CSS = """
            [data-e2e="download-app-banner"],
            .download-app-banner,
            [class*="DivBannerWrapper"],
            [class*="DivBannerContainer"],
            [class*="open-app"],
            [class*="OpenApp"],
            [class*="get-app"],
            [class*="GetApp"],
            .tiktok-14j1t75-DivBannerContainer,
            .css-1sdyv6b-DivBannerWrapper,

            /* Xóa triệt để giỏ hàng và thẻ sản phẩm TikTok Shop */
            [data-e2e*="shop"],
            [data-e2e*="cart"],
            [class*="tiktok-shop"],
            [class*="TikTokShop"],
            [class*="shop-cart"],
            [class*="ShopCart"],
            [class*="yellow-cart"],
            [class*="product-card"],
            [class*="ProductCard"],
            [class*="anchor-shop"],
            [class*="AnchorShop"],
            [class*="product-anchor"],
            [class*="ProductAnchor"] {
                display: none !important;
                visibility: hidden !important;
                height: 0 !important;
                pointer-events: none !important;
            }
        """

        // Script tối ưu phát video: Tự động chạy mượt mà, bật tiếng tự nhiên và đồng bộ Đảo Origin
        private const val CLEAN_JS = """
            (function() {
                if (window.__tiktokBridgeReady) return;
                window.__tiktokBridgeReady = true;

                // 1. Thêm CSS sạch
                const style = document.createElement('style');
                style.id = 'tiktok-clean-static-css';
                style.textContent = `$PURGE_CSS`;
                (document.head || document.documentElement).appendChild(style);

                // 2. Chặn các link bắt tải app và deep link snssdk
                document.addEventListener('click', function(e) {
                    const link = e.target.closest('a');
                    if (link && link.href) {
                        if (link.href.startsWith('snssdk') || link.href.startsWith('intent') || link.href.includes('tiktok.com/download')) {
                            e.preventDefault();
                            e.stopPropagation();
                        }
                    }
                }, true);

                // 3. Tự động bật âm thanh khi người dùng chạm màn hình lần đầu
                document.addEventListener('touchstart', function() {
                    const video = document.querySelector('video');
                    if (video && video.muted) {
                        video.muted = false;
                    }
                }, { once: true, passive: true });

                // 4. Đảm bảo video tự động play khi chuyển tới
                function ensureVideoPlays() {
                    const videos = document.querySelectorAll('video');
                    videos.forEach(v => {
                        v.playsInline = true;
                        v.setAttribute('playsinline', '');
                        v.setAttribute('webkit-playsinline', '');

                        if (v.__ready) return;
                        v.__ready = true;

                        v.addEventListener('play', function() {
                            try {
                                let title = document.title || 'TikTok Clean';
                                let author = '@tiktok';
                                let coverUrl = v.poster || '';

                                const container = v.closest('article') || v.parentElement;
                                if (container) {
                                    const authorEl = container.querySelector('h3, h4, [data-e2e*="author"]');
                                    if (authorEl) author = authorEl.innerText.trim();
                                    const descEl = container.querySelector('[data-e2e*="desc"], p');
                                    if (descEl) title = descEl.innerText.trim();
                                }

                                if (window.AndroidBridge) {
                                    window.AndroidBridge.onVideoUpdate(title, author, coverUrl, true);
                                }
                            } catch(err) {}
                        });

                        v.addEventListener('pause', function() {
                            if (window.AndroidBridge) {
                                window.AndroidBridge.onVideoUpdate('TikTok Clean', '@tiktok', '', false);
                            }
                        });

                        v.addEventListener('ended', function() {
                            // Tự động cuộn sang video kế tiếp khi xem xong
                            window.scrollBy({ top: window.innerHeight, behavior: 'smooth' });
                            setTimeout(() => {
                                const nextV = document.querySelector('video');
                                if (nextV) {
                                    nextV.muted = false;
                                    nextV.play().catch(()=>{});
                                }
                            }, 500);
                        });
                    });
                }

                setInterval(ensureVideoPlays, 1500);
                ensureVideoPlays();
            })();
        """
    }

    private lateinit var webView: WebView
    private lateinit var initProgress: ProgressBar

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Khi tắt màn hình khóa máy: Giữ video tiếp tục phát âm thanh liên tục
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                runOnUiThread {
                    webView.resumeTimers()
                    evalJs("const v=document.querySelector('video');if(v&&v.paused){v.play().catch(()=>{});}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        initProgress = findViewById(R.id.initProgress)

        checkNotificationPermission()
        setupWebView()

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenReceiver, filter)

        webView.loadUrl("https://www.tiktok.com/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyCommonSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // Cho phép video tự động phát không cần cử chỉ ép buộc
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = CHROME_USER_AGENT
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Tăng tốc phần cứng GPU để video render mượt mà 60fps/120fps
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        applyCommonSettings(webView.settings)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 70) {
                    initProgress.visibility = View.GONE
                }
            }

            // Hỗ trợ popup đăng nhập Google OAuth / Facebook an toàn
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val dialog = Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val popupWebView = WebView(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                applyCommonSettings(popupWebView.settings)

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        dialog.dismiss()
                    }
                }

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return !url.startsWith("http://") && !url.startsWith("https://")
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        if (url != null && (url.contains("tiktok.com") && !url.contains("accounts.google.com"))) {
                            CookieManager.getInstance().flush()
                            dialog.dismiss()
                            webView.reload()
                        }
                    }
                }

                dialog.setContentView(popupWebView)
                dialog.show()

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                initProgress.visibility = View.GONE
                injectCleanScript()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return true

                // Cho phép mở tất cả các trang video, hồ sơ của TikTok trong WebView
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (url.contains("play.google.com") || url.contains("tiktok.com/download")) {
                        return true // Chặn link tải app
                    }
                    return false // Tiếp tục xem video trong WebView
                }

                // Chặn triệt để và giải mã deep-link snssdk://
                try {
                    val uri = Uri.parse(url)
                    val paramsUrl = uri.getQueryParameter("params_url")
                    if (!paramsUrl.isNullOrBlank()) {
                        val decoded = URLDecoder.decode(paramsUrl, "UTF-8")
                        if (decoded.startsWith("http")) {
                            view?.loadUrl(decoded)
                            return true
                        }
                    }
                } catch (_: Exception) {}

                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val url = request?.url?.toString() ?: ""
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return
                }
                super.onReceivedError(view, request, error)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: ""
                if (url.contains("tiktokcdn.com/obj/e-commerce") ||
                    url.contains("byteoversea.com/api/ad/") ||
                    url.contains("/api/v1/inapp/banner")
                ) {
                    return WebResourceResponse("text/plain", "UTF-8", null)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun injectCleanScript() {
        val jsCode = CLEAN_JS
            .replace("\$PURGE_CSS", PURGE_CSS.replace("\n", " ").replace("\"", "\\\""))
        evalJs(jsCode)
    }

    fun evalJs(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onVideoUpdate(title: String, author: String, coverUrl: String, isPlaying: Boolean) {
            runOnUiThread {
                OriginMediaService.updateTrack(
                    this@MainActivity,
                    title,
                    author,
                    coverUrl,
                    isPlaying
                )
            }
        }
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        webView.destroy()
        super.onDestroy()
    }
}
