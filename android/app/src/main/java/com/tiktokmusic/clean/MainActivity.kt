package com.tiktokmusic.clean

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity() {

    companion object {
        private var instance: MainActivity? = null

        fun dispatchMediaAction(action: String, value: String = "0") {
            val act = instance ?: return
            act.runOnUiThread {
                when (action) {
                    "play" -> act.resumeCurrentVideo()
                    "pause" -> act.pauseCurrentVideo()
                    "toggle" -> act.togglePlayPause()
                    "next" -> act.playNextVideo()
                    "prev" -> act.playPreviousVideo()
                }
            }
        }
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var adapter: TikTokVideoAdapter
    private lateinit var repository: TikTokRepository
    private lateinit var initProgress: ProgressBar
    private val videoList = mutableListOf<TikTokItem>()

    private var exoPlayer: ExoPlayer? = null
    private var currentPosition: Int = 0
    private val progressHandler = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Khi tắt màn hình khóa máy: TUYỆT ĐỐI KHÔNG PAUSE ExoPlayer, giữ âm thanh tiếp tục phát 100%!
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                exoPlayer?.playWhenReady = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Tràn viền 100% toàn màn hình siêu mượt
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()
        initScreenReceiver()
        initExoPlayer()

        viewPager = findViewById(R.id.viewPager)
        initProgress = findViewById(R.id.initProgress)
        repository = TikTokRepository(this)

        adapter = TikTokVideoAdapter(videoList) { pos, _ ->
            togglePlayPause()
        }
        viewPager.adapter = adapter
        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.offscreenPageLimit = 2

        // Lắng nghe sự kiện chuyển trang vuốt dọc
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentPosition = position
                playVideoAt(position)

                // Khi lướt gần hết danh sách, tự động cào thêm video mới ngầm
                if (position >= videoList.size - 4) {
                    loadMoreOnlineVideos()
                }
            }
        })

        // Bắt đầu nạp video
        loadInitialFeed()
    }

    private fun hideSystemUI() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun initExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            // Hết video: tự động chuyển sang video tiếp theo mượt mà
                            playNextVideo()
                        } else if (state == Player.STATE_READY) {
                            hideThumbnailForCurrent()
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateOriginIslandState(isPlaying)
                    }
                })
            }

        startProgressLoop()
    }

    private fun loadInitialFeed() {
        initProgress.visibility = View.VISIBLE
        val localVideos = repository.loadInitialVideos()
        if (localVideos.isNotEmpty()) {
            videoList.addAll(localVideos)
            adapter.notifyDataSetChanged()
            initProgress.visibility = View.GONE
            viewPager.post {
                playVideoAt(0)
            }
        }

        // Cào thêm video tươi mới từ TikTok ngầm
        loadMoreOnlineVideos()
    }

    private fun loadMoreOnlineVideos() {
        val keywords = listOf("nhacchill", "nhaytrend", "lofi", "remix")
        val randomKw = keywords.random()
        repository.fetchOnlineBatchAsync(randomKw) { freshItems ->
            if (freshItems.isNotEmpty()) {
                runOnUiThread {
                    adapter.addItems(freshItems)
                    initProgress.visibility = View.GONE
                }
            }
        }
    }

    private fun playVideoAt(position: Int) {
        val player = exoPlayer ?: return
        if (position !in videoList.indices) return

        val item = videoList[position]

        // Gắn ExoPlayer vào PlayerView của trang hiện tại
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? TikTokVideoAdapter.VideoViewHolder
        if (holder != null) {
            holder.playerView.player = player
            holder.ivThumbnail.visibility = View.VISIBLE
            holder.ivCenterPlay.visibility = View.GONE
        }

        val mediaItem = MediaItem.fromUri(item.videoUrl)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        // Gửi thông tin sang Đảo Origin của Vivo OriginOS 6
        updateOriginIslandState(true)
    }

    private fun hideThumbnailForCurrent() {
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView
        val holder = recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? TikTokVideoAdapter.VideoViewHolder
        holder?.ivThumbnail?.visibility = View.GONE
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        val willPlay = !player.isPlaying
        player.playWhenReady = willPlay

        val recyclerView = viewPager.getChildAt(0) as? RecyclerView
        val holder = recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? TikTokVideoAdapter.VideoViewHolder
        if (holder != null) {
            holder.ivCenterPlay.visibility = if (willPlay) View.GONE else View.VISIBLE
        }
        updateOriginIslandState(willPlay)
    }

    private fun resumeCurrentVideo() {
        exoPlayer?.playWhenReady = true
        updateOriginIslandState(true)
    }

    private fun pauseCurrentVideo() {
        exoPlayer?.playWhenReady = false
        updateOriginIslandState(false)
    }

    private fun playNextVideo() {
        if (currentPosition < videoList.size - 1) {
            viewPager.setCurrentItem(currentPosition + 1, true)
        }
    }

    private fun playPreviousVideo() {
        if (currentPosition > 0) {
            viewPager.setCurrentItem(currentPosition - 1, true)
        }
    }

    private fun updateOriginIslandState(isPlaying: Boolean) {
        if (currentPosition !in videoList.indices) return
        val item = videoList[currentPosition]
        val curMs = exoPlayer?.currentPosition ?: 0L
        val durMs = exoPlayer?.duration?.takeIf { it > 0 } ?: (item.durationSec * 1000L)

        val intent = Intent(this, OriginMediaService::class.java).apply {
            action = OriginMediaService.ACTION_UPDATE
            putExtra("title", item.title.ifEmpty { "TikTok Music Clean" })
            putExtra("artist", if (item.authorNickname.isNotEmpty()) "@${item.authorUsername} · ${item.authorNickname}" else "@${item.authorUsername}")
            putExtra("coverUrl", item.coverUrl)
            putExtra("isPlaying", isPlaying)
            putExtra("posMs", curMs)
            putExtra("durMs", durMs)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
    }

    private fun startProgressLoop() {
        progressHandler.post(object : Runnable {
            override fun run() {
                val player = exoPlayer
                if (player != null && player.isPlaying) {
                    val pos = player.currentPosition
                    val dur = player.duration
                    if (dur > 0) {
                        val progress = ((pos * 100) / dur).toInt()
                        val recyclerView = viewPager.getChildAt(0) as? RecyclerView
                        val holder = recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? TikTokVideoAdapter.VideoViewHolder
                        holder?.pbTimeline?.progress = progress
                    }
                }
                progressHandler.postDelayed(this, 300)
            }
        })
    }

    private fun initScreenReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(screenReceiver, filter)
        } catch (_: Exception) {}
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        exoPlayer?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        // TUYỆT ĐỐI KHÔNG PAUSE PLAYER Ở ĐÂY: Giữ cho ExoPlayer tiếp tục giải mã âm thanh khi người dùng tắt màn hình!
    }

    override fun onDestroy() {
        instance = null
        progressHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        exoPlayer?.release()
        exoPlayer = null
        try {
            stopService(Intent(this, OriginMediaService::class.java))
        } catch (_: Exception) {}
        super.onDestroy()
    }
}