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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity() {

    companion object {
        private var instance: MainActivity? = null

        fun dispatchMediaAction(action: String) {
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

        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.viewPager)
        initProgress = findViewById(R.id.initProgress)

        val tvTabFollowing: TextView = findViewById(R.id.tvTabFollowing)
        val tvTabForYou: TextView = findViewById(R.id.tvTabForYou)

        tvTabFollowing.setOnClickListener {
            tvTabFollowing.setTextColor(Color.WHITE)
            tvTabForYou.setTextColor(Color.parseColor("#B3FFFFFF"))
        }
        tvTabForYou.setOnClickListener {
            tvTabForYou.setTextColor(Color.WHITE)
            tvTabFollowing.setTextColor(Color.parseColor("#B3FFFFFF"))
        }

        checkNotificationPermission()
        initExoPlayer()
        setupViewPager()

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenReceiver, filter)

        // Khởi động phát danh sách video tức thì 0.01s
        repository = TikTokRepository()
        val initialItems = repository.getInitialVideos()
        videoList.addAll(initialItems)
        adapter.notifyDataSetChanged()
        initProgress.visibility = View.GONE

        viewPager.post {
            playVideoAt(0)
        }

        // Tải thêm video thịnh hành liên tục ngầm từ máy chủ TikTok
        repository.fetchOnlineTrending { onlineItems ->
            runOnUiThread {
                if (onlineItems.isNotEmpty()) {
                    val startIdx = videoList.size
                    videoList.addAll(onlineItems)
                    adapter.notifyItemRangeInserted(startIdx, onlineItems.size)
                }
            }
        }
    }

    private fun initExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            getCurrentViewHolder()?.ivThumbnail?.visibility = View.GONE
                            getCurrentViewHolder()?.ivCenterPlay?.visibility = View.GONE
                            syncWithOriginIsland(true)
                        }
                        Player.STATE_ENDED -> {
                            // TỰ ĐỘNG CUỘN SANG VIDEO KẾ TIẾP KHI XEM XONG
                            playNextVideo()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    syncWithOriginIsland(isPlaying)
                }

                override fun onRenderedFirstFrame() {
                    getCurrentViewHolder()?.ivThumbnail?.visibility = View.GONE
                }
            })
        }
        startProgressUpdater()
    }

    private fun setupViewPager() {
        adapter = TikTokVideoAdapter(videoList) { position, shouldPlay ->
            if (position == currentPosition) {
                if (shouldPlay) {
                    exoPlayer?.play()
                } else {
                    exoPlayer?.pause()
                }
            } else {
                viewPager.setCurrentItem(position, true)
            }
        }

        viewPager.adapter = adapter
        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.offscreenPageLimit = 1

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                playVideoAt(position)
            }
        })
    }

    private fun playVideoAt(position: Int) {
        if (position < 0 || position >= videoList.size) return
        currentPosition = position

        val item = videoList[position]
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()

        // Gắn Player vào ViewHolder hiện tại
        viewPager.post {
            val holder = getCurrentViewHolder()
            if (holder != null) {
                PlayerView.switchTargetView(exoPlayer!!, null, holder.playerView)
                holder.ivThumbnail.visibility = View.VISIBLE
                holder.ivCenterPlay.visibility = View.GONE
            }

            val mediaItem = MediaItem.fromUri(item.videoUrl)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true

            // ĐỒNG BỘ ĐẢO ORIGIN TRÊN VIVO ORIGINOS 6
            syncWithOriginIsland(true)
        }
    }

    private fun getCurrentViewHolder(): TikTokVideoAdapter.VideoViewHolder? {
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return null
        return recyclerView.findViewHolderForAdapterPosition(currentPosition) as? TikTokVideoAdapter.VideoViewHolder
    }

    private fun syncWithOriginIsland(isPlaying: Boolean) {
        if (currentPosition in videoList.indices) {
            val item = videoList[currentPosition]
            OriginMediaService.updateTrack(
                this,
                item.title,
                item.author,
                item.coverUrl,
                isPlaying
            )
        }
    }

    private fun resumeCurrentVideo() {
        exoPlayer?.play()
        getCurrentViewHolder()?.ivCenterPlay?.visibility = View.GONE
        getCurrentViewHolder()?.discAnimator?.resume()
    }

    private fun pauseCurrentVideo() {
        exoPlayer?.pause()
        getCurrentViewHolder()?.ivCenterPlay?.visibility = View.VISIBLE
        getCurrentViewHolder()?.discAnimator?.pause()
    }

    private fun togglePlayPause() {
        if (exoPlayer?.isPlaying == true) {
            pauseCurrentVideo()
        } else {
            resumeCurrentVideo()
        }
    }

    private fun playNextVideo() {
        if (currentPosition + 1 < videoList.size) {
            viewPager.setCurrentItem(currentPosition + 1, true)
        } else {
            // Tải thêm video nếu đến cuối danh sách
            repository.fetchOnlineTrending { moreItems ->
                runOnUiThread {
                    if (moreItems.isNotEmpty()) {
                        val start = videoList.size
                        videoList.addAll(moreItems)
                        adapter.notifyItemRangeInserted(start, moreItems.size)
                        viewPager.setCurrentItem(currentPosition + 1, true)
                    }
                }
            }
        }
    }

    private fun playPreviousVideo() {
        if (currentPosition > 0) {
            viewPager.setCurrentItem(currentPosition - 1, true)
        }
    }

    private fun startProgressUpdater() {
        progressHandler.post(object : Runnable {
            override fun run() {
                val player = exoPlayer
                if (player != null && player.isPlaying) {
                    val duration = player.duration
                    val position = player.currentPosition
                    if (duration > 0) {
                        val progress = ((position * 100) / duration).toInt()
                        getCurrentViewHolder()?.pbTimeline?.progress = progress
                    }
                }
                progressHandler.postDelayed(this, 250)
            }
        })
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

    override fun onResume() {
        super.onResume()
        if (exoPlayer?.playbackState == Player.STATE_READY) {
            exoPlayer?.play()
        }
    }

    override fun onStop() {
        super.onStop()
        // Khi màn hình tắt: VẪN TIẾP TỤC PHÁT (OriginMediaService bảo vệ âm thanh)
        // Chỉ dừng khi người dùng tắt hẳn app
        if (isFinishing) {
            exoPlayer?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        exoPlayer?.release()
        exoPlayer = null
    }
}
