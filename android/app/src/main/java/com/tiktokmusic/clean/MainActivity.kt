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
import android.widget.FrameLayout
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
                    "play" -> act.evalJs("document.querySelectorAll('video').forEach(v=>{v.play().catch(()=>{})});")
                    "pause" -> act.evalJs("document.querySelectorAll('video').forEach(v=>{v.pause()});")
                    "toggle" -> act.evalJs("const v=document.querySelector('video');if(v){if(v.paused)v.play().catch(()=>{});else v.pause();}")
                    "next" -> act.evalJs("window.scrollBy({top:window.innerHeight,behavior:'smooth'});setTimeout(()=>{document.querySelector('video')?.play().catch(()=>{})},500);")
                    "prev" -> act.evalJs("window.scrollBy({top:-window.innerHeight,behavior:'smooth'});setTimeout(()=>{document.querySelector('video')?.play().catch(()=>{})},500);")
                }
            }
        }

        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        // CSS sạch loại bỏ 100% rác, Shop và Banner mà KHÔNG gây nháy màn hình
        private const val PURGE_CSS = """
            [data-e2e="download-app-banner"],
            .download-app-banner,
            [class*="DivBannerWrapper"],
            [class*="DivBannerContainer"],
            [class*="OpenApp"],
            [class*="open-app"],
            [class*="get-app"],
            [class*="GetApp"],
            [class*="DownloadApp"],
            [class*="app-banner"],
            [class*="AppBanner"],
            .tiktok-14j1t75-DivBannerContainer,
            .css-1sdyv6b-DivBannerWrapper,

            /* Xóa triệt để giỏ hàng, thẻ sản phẩm TikTok Shop */
            [data-e2e*="shop"],
            [data-e2e*="Shop"],
            [data-e2e*="cart"],
            [data-e2e*="Cart"],
            [class*="tiktok-shop"],
            [class*="TikTokShop"],
            [class*="shop-cart"],
            [class*="ShopCart"],
            [class*="e-commerce"],
            [class*="ecommerce"],
            [class*="ECommerce"],
            [class*="yellow-cart"],
            [class*="product-card"],
            [class*="ProductCard"],
            [class*="showcase"],
            [class*="Showcase"],
            [class*="anchor-shop"],
            [class*="AnchorShop"],
            [class*="product-anchor"],
            [class*="recharge"],
            [class*="Recharge"],
            [class*="coin"],
            [class*="Coin"],
            [class*="wallet"],
            [class*="Wallet"],
            [class*="payment"],
            [class*="Payment"] {
                display: none !important;
                visibility: hidden !important;
                height: 0 !important;
                max-height: 0 !important;
                opacity: 0 !important;
                pointer-events: none !important;
            }
        """

        // Script hook nhẹ nhàng: Tự động bật âm thanh khi bấm video & đẩy siêu dữ liệu lên Đảo Origin
        private const val CLEAN_JS = """
            (function() {
                if (window.__tiktokBridgeReady) return;
                window.__tiktokBridgeReady = true;

                // Thêm CSS sạch một lần duy nhất (không loop gây nháy)
                const style = document.createElement('style');
                style.id = 'tiktok-clean-static-css';
                style.textContent = `$PURGE_CSS`;
                (document.head || document.documentElement).appendChild(style);

                // Lắng nghe sự kiện click vào link deep-link snssdk để chặn lỗi
                document.addEventListener('click', function(e) {
                    const link = e.target.closest('a');
                    if (link && link.href) {
                        if (link.href.startsWith('snssdk') || link.href.startsWith('intent') || link.href.includes('tiktok.com/download')) {
                            e.preventDefault();
                            e.stopPropagation();
                        }
                    }
                }, true);

                // Lắng nghe phát video để bật tiếng và đồng bộ với Đảo Origin
                document.addEventListener('play', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        const video = e.target;
                        // Tự động bật âm thanh nếu đang bị tắt tiếng
                        if (video.muted) {
                            video.muted = false;
                        }

                        // Lấy thông tin bài hát / video
                        try {
                            let title = document.title || 'TikTok Clean';
                            let author = '@tiktok';
                            let coverUrl = video.poster || '';

                            const container = video.closest('article') || video.parentElement;
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
                    }
                }, true);

                document.addEventListener('pause', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        if (window.AndroidBridge) {
                            window.AndroidBridge.onVideoUpdate('TikTok Clean', '@tiktok', '', false);
                        }
                    }
                }, true);

                document.addEventListener('ended', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        // Tự động cuộn sang video kế tiếp khi xem xong
                        window.scrollBy({ top: window.innerHeight, behavior: 'smooth' });
                    }
                }, true);
            })();
        """
    }

    private lateinit var webView: WebView
    private lateinit var initProgress: ProgressBar
    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                runOnUiThread {
                    webView.resumeTimers()
                    evalJs("document.querySelectorAll('video').forEach(v=>{v.play().catch(()=>{})});")
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
        // Tăng tốc phần cứng GPU để video chạy mượt mà 60fps/120fps không bị giật lag
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

            // Hỗ trợ hiển thị video toàn màn hình mượt mà không bị đen màn hình
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    onHideCustomView()
                    return
                }
                customVideoView = view
                customVideoCallback = callback
                val decor = window.decorView as FrameLayout
                decor.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                val decor = window.decorView as FrameLayout
                decor.removeView(customVideoView)
                customVideoView = null
                customVideoCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
            }

            // Hỗ trợ cửa sổ đăng nhập Google / Facebook popup
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

                // Nếu là URL web của TikTok hoặc trang web hợp lệ:
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (url.contains("play.google.com") || url.contains("tiktok.com/download")) {
                        return true // Chặn link ép tải app
                    }
                    return false // Cho phép mở video và trang bình thường trong WebView
                }

                // Nếu là deep-link snssdk:// cố ý ép mở app gốc:
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
        if (customVideoView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }
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
