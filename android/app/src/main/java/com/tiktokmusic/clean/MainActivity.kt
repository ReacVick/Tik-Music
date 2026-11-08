package com.tiktokmusic.clean

import android.Manifest
import android.annotation.SuppressLint
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
import android.view.View
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
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    companion object {
        private var instance: MainActivity? = null

        fun dispatchMediaAction(action: String) {
            val act = instance ?: return
            act.runOnUiThread {
                when (action) {
                    "play" -> act.evalJs("window.tiktokPlay?.()")
                    "pause" -> act.evalJs("window.tiktokPause?.()")
                    "toggle" -> act.evalJs("window.tiktokToggle?.()")
                    "next" -> act.evalJs("window.tiktokScrollNext?.()")
                    "prev" -> act.evalJs("window.tiktokScrollPrev?.()")
                }
            }
        }

        // User Agent Chrome Mobile Android chuẩn
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.88 Mobile Safari/537.36"

        private const val PURGE_CSS = """
            /* ============================================================
               TIKTOK CLEAN: LOẠI BỎ 100% TIKTOK SHOP, GIỎ HÀNG, BANNER & QUẢNG CÁO
               ============================================================ */
            /* 1. Xóa toàn bộ banner 'Mở trong ứng dụng / Tải app' */
            [data-e2e="download-app-banner"],
            .download-app-banner,
            [class*="DivBannerWrapper"],
            [class*="DivBannerContainer"],
            [class*="OpenApp"],
            [class*="open-app"],
            [class*="get-app"],
            [class*="GetApp"],
            [class*="DownloadApp"],
            [class*="InstallApp"],
            [class*="install-app"],
            [class*="app-banner"],
            [class*="AppBanner"],
            .tiktok-14j1t75-DivBannerContainer,
            .css-1sdyv6b-DivBannerWrapper,
            [data-e2e="header-login-button"],

            /* 2. Xóa 100% TikTok Shop, giỏ hàng vàng, túi hàng & thẻ sản phẩm */
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
            [class*="AnchorCart"],
            [class*="anchor-cart"],
            [class*="product-anchor"],
            [class*="ProductAnchor"],

            /* 3. Xóa các nút mua hàng, nạp xu & thanh toán */
            [class*="recharge"],
            [class*="Recharge"],
            [class*="coin"],
            [class*="Coin"],
            [class*="wallet"],
            [class*="Wallet"],
            [class*="payment"],
            [class*="Payment"],
            [class*="checkout"],
            [class*="Checkout"],

            /* 4. Giữ lại trải nghiệm xem và nghe thuần túy toàn màn hình */
            video {
                width: 100vw !important;
                height: 100vh !important;
                object-fit: cover !important;
            }
        """

        private const val INJECT_JS = """
            (function() {
                if (window.__tiktokCleanInjected) return;
                window.__tiktokCleanInjected = true;

                // Chặn triệt để mọi hành vi chuyển hướng sang app TikTok (snssdk://, intent://)
                const blockSchemes = ['snssdk', 'intent:', 'market:'];
                const origOpen = window.open;
                window.open = function(url, target, features) {
                    if (typeof url === 'string' && blockSchemes.some(s => url.startsWith(s))) {
                        return null;
                    }
                    return origOpen.apply(this, arguments);
                };

                document.addEventListener('click', function(e) {
                    const link = e.target.closest('a');
                    if (link && link.href) {
                        if (blockSchemes.some(s => link.href.startsWith(s)) || link.href.includes('tiktok.com/download')) {
                            e.preventDefault();
                            e.stopPropagation();
                        }
                    }
                }, true);

                // Thêm CSS loại bỏ TikTok Shop & Banner
                function injectCleanStyles() {
                    if (document.getElementById('tiktok-clean-style')) return;
                    const style = document.createElement('style');
                    style.id = 'tiktok-clean-style';
                    style.textContent = `$PURGE_CSS`;
                    (document.head || document.documentElement).appendChild(style);
                }
                injectCleanStyles();

                // Observer liên tục dọn sạch các popup rác & nút quảng cáo/shop xuất hiện động
                const observer = new MutationObserver(() => {
                    injectCleanStyles();
                    // Đóng tự động các popup 'Mở app'
                    const closeBtns = document.querySelectorAll('[aria-label="Close"], [class*="close-button"], [class*="CloseButton"], [class*="btn-close"]');
                    closeBtns.forEach(b => {
                        const parentText = b.closest('div')?.innerText || '';
                        if (parentText.includes('app') || parentText.includes('Tải') || parentText.includes('Shop') || parentText.includes('Mở')) {
                            try { b.click(); } catch(e) {}
                        }
                    });
                });
                observer.observe(document.documentElement, { childList: true, subtree: true });

                // Các hàm điều khiển Player từ tầng Native (Đảo Origin & Màn hình khóa)
                window.tiktokPlay = function() {
                    const v = document.querySelector('video');
                    if (v && v.paused) v.play().catch(()=>{});
                };

                window.tiktokPause = function() {
                    const v = document.querySelector('video');
                    if (v && !v.paused) v.pause();
                };

                window.tiktokToggle = function() {
                    const v = document.querySelector('video');
                    if (v) {
                        if (v.paused) v.play().catch(()=>{});
                        else v.pause();
                    }
                };

                window.tiktokScrollNext = function() {
                    window.scrollBy({ top: window.innerHeight, behavior: 'smooth' });
                    setTimeout(() => {
                        const nextVideo = document.querySelector('video');
                        if (nextVideo && nextVideo.paused) nextVideo.play().catch(()=>{});
                    }, 400);
                };

                window.tiktokScrollPrev = function() {
                    window.scrollBy({ top: -window.innerHeight, behavior: 'smooth' });
                    setTimeout(() => {
                        const prevVideo = document.querySelector('video');
                        if (prevVideo && prevVideo.paused) prevVideo.play().catch(()=>{});
                    }, 400);
                };

                // Lắng nghe sự kiện phát video để đẩy siêu dữ liệu lên Đảo Origin
                function hookVideos() {
                    const videos = document.querySelectorAll('video');
                    videos.forEach(v => {
                        if (v.__hooked) return;
                        v.__hooked = true;

                        v.addEventListener('play', () => {
                            reportVideoState(v, true);
                        });

                        v.addEventListener('pause', () => {
                            reportVideoState(v, false);
                        });

                        v.addEventListener('ended', () => {
                            window.tiktokScrollNext();
                        });
                    });
                }

                function reportVideoState(v, isPlaying) {
                    try {
                        let author = '@tiktok';
                        let title = 'TikTok Clean';
                        let coverUrl = '';

                        const container = v.closest('[data-e2e*="item"]') || v.parentElement;
                        if (container) {
                            const authorEl = container.querySelector('[data-e2e="video-author-uniqueid"], [data-e2e="browse-username"], h3, h4');
                            if (authorEl && authorEl.innerText) {
                                author = authorEl.innerText.trim();
                            }
                            const descEl = container.querySelector('[data-e2e="video-desc"], [class*="DivTextWrapper"], [class*="desc"]');
                            if (descEl && descEl.innerText) {
                                title = descEl.innerText.trim();
                            }
                            const avatarEl = container.querySelector('img[src*="tiktok"], img[src*="avatar"], img[class*="Avatar"]');
                            if (avatarEl && avatarEl.src) {
                                coverUrl = avatarEl.src;
                            }
                        }

                        if (!coverUrl) {
                            coverUrl = v.poster || '';
                        }

                        if (window.AndroidBridge) {
                            window.AndroidBridge.onVideoUpdate(title, author, coverUrl, isPlaying);
                        }
                    } catch(e) {}
                }

                setInterval(hookVideos, 1000);
                hookVideos();
            })();
        """
    }

    private lateinit var webView: WebView
    private lateinit var initProgress: ProgressBar

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                runOnUiThread {
                    webView.resumeTimers()
                    evalJs("window.tiktokPlay?.()")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Tràn viền toàn màn hình
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

        // Tải trang web chính thức TikTok
        webView.loadUrl("https://www.tiktok.com/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
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

        // Lưu cookie vĩnh viễn
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 80) {
                    initProgress.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectCleanScript()
            }

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

                // Nếu là URL web thông thường (http / https):
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    // Chặn các link bắt tải app hoặc chuyển sang Google Play Store
                    if (url.contains("play.google.com") || url.contains("tiktok.com/download")) {
                        return true
                    }
                    return false // Tiếp tục xem video trong WebView
                }

                // Nếu là custom scheme của app TikTok (snssdk1180://, snssdk1233://, intent://)
                // Đây chính là nguyên nhân gây ra lỗi net::ERR_UNKNOWN_URL_SCHEME!
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

                // Nuốt (consume) lệnh điều hướng scheme lạ, TUYỆT ĐỐI không để WebView báo lỗi màn hình trắng!
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val url = request?.url?.toString() ?: ""
                // Bỏ qua lỗi của scheme snssdk / intent
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
        val jsCode = INJECT_JS
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
