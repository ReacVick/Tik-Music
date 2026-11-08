package com.tiktokmusic.clean

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip

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
    private lateinit var btnToggleAutoScroll: TextView
    private lateinit var btnSearch: ImageView
    private lateinit var tvTabFollowing: TextView
    private lateinit var tvTabForYou: TextView

    private lateinit var prefs: SharedPreferences
    private val videoList = mutableListOf<TikTokItem>()

    private var exoPlayer: ExoPlayer? = null
    private var currentPosition: Int = 0
    private var isAutoScrollEnabled: Boolean = true
    private var isLoadingMore: Boolean = false
    private var currentRegion: String = "VN"

    private val progressHandler = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Khi màn hình tắt khóa máy: Giữ âm thanh tiếp tục phát 100%
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                exoPlayer?.playWhenReady = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Tràn viền giao diện tối 120Hz mượt mà
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("tiktok_clean_prefs", Context.MODE_PRIVATE)
        isAutoScrollEnabled = prefs.getBoolean("auto_scroll_enabled", true)

        viewPager = findViewById(R.id.viewPager)
        initProgress = findViewById(R.id.initProgress)
        btnToggleAutoScroll = findViewById(R.id.btnToggleAutoScroll)
        btnSearch = findViewById(R.id.btnSearch)
        tvTabFollowing = findViewById(R.id.tvTabFollowing)
        tvTabForYou = findViewById(R.id.tvTabForYou)

        repository = TikTokRepository()

        setupAutoScrollButton()
        setupTopTabs()
        setupSearchButton()
        checkNotificationPermission()
        initExoPlayer()
        setupViewPager()

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenReceiver, filter)

        // Nạp mẻ video trực tuyến đầu tiên ngay khi mở app
        loadInitialFeed()
    }

    private fun setupAutoScrollButton() {
        updateAutoScrollUI()
        btnToggleAutoScroll.setOnClickListener {
            isAutoScrollEnabled = !isAutoScrollEnabled
            prefs.edit().putBoolean("auto_scroll_enabled", isAutoScrollEnabled).apply()
            updateAutoScrollUI()
            val msg = if (isAutoScrollEnabled) {
                "Đã BẬT tự động cuộn (hết video tự chuyển)"
            } else {
                "Đã TẮT tự động cuộn (lặp lại video)"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAutoScrollUI() {
        if (isAutoScrollEnabled) {
            btnToggleAutoScroll.text = "⚡ Tự cuộn: BẬT"
            btnToggleAutoScroll.setBackgroundResource(R.drawable.bg_pill_autoscroll_on)
            btnToggleAutoScroll.setTextColor(Color.parseColor("#00E676"))
        } else {
            btnToggleAutoScroll.text = "⚡ Tự cuộn: TẮT"
            btnToggleAutoScroll.setBackgroundResource(R.drawable.bg_pill_autoscroll_off)
            btnToggleAutoScroll.setTextColor(Color.parseColor("#88FFFFFF"))
        }
    }

    private fun setupTopTabs() {
        tvTabFollowing.setOnClickListener {
            tvTabFollowing.setTextColor(Color.WHITE)
            tvTabForYou.setTextColor(Color.parseColor("#B3FFFFFF"))
            currentRegion = "US"
            refreshFeedWithRegion(currentRegion)
        }
        tvTabForYou.setOnClickListener {
            tvTabForYou.setTextColor(Color.WHITE)
            tvTabFollowing.setTextColor(Color.parseColor("#B3FFFFFF"))
            currentRegion = "VN"
            refreshFeedWithRegion(currentRegion)
        }
    }

    private fun setupSearchButton() {
        btnSearch.setOnClickListener {
            showSearchDialog()
        }
    }

    private fun showSearchDialog() {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_search, null)
        dialog.setContentView(dialogView)

        val etSearchQuery = dialogView.findViewById<EditText>(R.id.etSearchQuery)
        val btnDoSearch = dialogView.findViewById<TextView>(R.id.btnDoSearch)
        val chipTrend = dialogView.findViewById<Chip>(R.id.chipTrend)
        val chipChill = dialogView.findViewById<Chip>(R.id.chipChill)
        val chipRemix = dialogView.findViewById<Chip>(R.id.chipRemix)
        val chipUS = dialogView.findViewById<Chip>(R.id.chipUS)
        val chipKpop = dialogView.findViewById<Chip>(R.id.chipKpop)

        val executeSearch: (String) -> Unit = { query ->
            dialog.dismiss()
            initProgress.visibility = View.VISIBLE
            repository.searchVideos(query) { results ->
                runOnUiThread {
                    initProgress.visibility = View.GONE
                    if (results.isNotEmpty()) {
                        videoList.clear()
                        videoList.addAll(results)
                        adapter.notifyDataSetChanged()
                        viewPager.setCurrentItem(0, false)
                        playVideoAt(0)
                        Toast.makeText(this, "Tìm thấy ${results.size} video cho: $query", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Không tìm thấy video nào phù hợp", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnDoSearch.setOnClickListener {
            val q = etSearchQuery.text.toString().trim()
            if (q.isNotEmpty()) executeSearch(q)
        }

        etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = etSearchQuery.text.toString().trim()
                if (q.isNotEmpty()) executeSearch(q)
                true
            } else false
        }

        chipTrend.setOnClickListener {
            dialog.dismiss()
            refreshFeedWithRegion("VN")
        }
        chipChill.setOnClickListener {
            executeSearch("chill")
        }
        chipRemix.setOnClickListener {
            executeSearch("remix")
        }
        chipUS.setOnClickListener {
            dialog.dismiss()
            refreshFeedWithRegion("US")
        }
        chipKpop.setOnClickListener {
            dialog.dismiss()
            refreshFeedWithRegion("KR")
        }

        dialog.show()
    }

    private fun showAuthorProfileDialog(item: TikTokItem) {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_author_profile, null)
        dialog.setContentView(dialogView)

        val ivAvatar = dialogView.findViewById<ImageView>(R.id.ivDialogAuthorAvatar)
        val tvNickname = dialogView.findViewById<TextView>(R.id.tvDialogAuthorNickname)
        val tvHandle = dialogView.findViewById<TextView>(R.id.tvDialogAuthorHandle)
        val btnFollow = dialogView.findViewById<Button>(R.id.btnDialogFollow)
        val rvAuthorVideos = dialogView.findViewById<RecyclerView>(R.id.rvAuthorVideos)
        val tvCount = dialogView.findViewById<TextView>(R.id.tvAuthorVideoCount)

        tvNickname.text = item.author
        tvHandle.text = if (item.authorUniqueId.isNotEmpty()) "@${item.authorUniqueId}" else item.author
        ImageLoader.load(item.authorAvatar, ivAvatar, true)

        var isFollowing = false
        btnFollow.setOnClickListener {
            isFollowing = !isFollowing
            if (isFollowing) {
                btnFollow.text = "Đang theo dõi"
                btnFollow.setBackgroundColor(Color.parseColor("#33FFFFFF"))
            } else {
                btnFollow.text = "+ Theo dõi"
                btnFollow.setBackgroundColor(Color.parseColor("#FE2C55"))
            }
        }

        val authorVideos = repository.getVideosByAuthor(item.authorUniqueId, item.author).ifEmpty {
            listOf(item)
        }
        tvCount.text = "Danh sách video của kênh (${authorVideos.size} video):"

        rvAuthorVideos.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvAuthorVideos.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = layoutInflater.inflate(R.layout.item_author_video_card, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val video = authorVideos[position]
                val ivThumb = holder.itemView.findViewById<ImageView>(R.id.ivAuthorThumb)
                val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvAuthorVideoTitle)

                tvTitle.text = video.title
                ImageLoader.load(video.coverUrl, ivThumb, false)

                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    val targetIndex = videoList.indexOfFirst { it.id == video.id }
                    if (targetIndex >= 0) {
                        viewPager.setCurrentItem(targetIndex, true)
                    } else {
                        videoList.add(currentPosition + 1, video)
                        adapter.notifyItemInserted(currentPosition + 1)
                        viewPager.setCurrentItem(currentPosition + 1, true)
                    }
                }
            }

            override fun getItemCount(): Int = authorVideos.size
        }

        dialog.show()
    }

    private fun showOptionsMenu(item: TikTokItem) {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_video_options, null)
        dialog.setContentView(dialogView)

        val switchAutoScroll = dialogView.findViewById<SwitchCompat>(R.id.switchAutoScrollOption)
        val btnViewAuthor = dialogView.findViewById<TextView>(R.id.btnOptionViewAuthor)
        val btnCopyLink = dialogView.findViewById<TextView>(R.id.btnOptionCopyLink)

        switchAutoScroll.isChecked = isAutoScrollEnabled
        switchAutoScroll.setOnCheckedChangeListener { _, isChecked ->
            isAutoScrollEnabled = isChecked
            prefs.edit().putBoolean("auto_scroll_enabled", isAutoScrollEnabled).apply()
            updateAutoScrollUI()
        }

        btnViewAuthor.setOnClickListener {
            dialog.dismiss()
            showAuthorProfileDialog(item)
        }

        btnCopyLink.setOnClickListener {
            dialog.dismiss()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("TikTok Video URL", item.videoUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép liên kết video trực tiếp!", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun loadInitialFeed() {
        initProgress.visibility = View.VISIBLE
        repository.fetchOnlineTrending(currentRegion, 20) { items ->
            runOnUiThread {
                initProgress.visibility = View.GONE
                if (items.isNotEmpty()) {
                    videoList.clear()
                    videoList.addAll(items)
                    adapter.notifyDataSetChanged()
                    viewPager.post {
                        playVideoAt(0)
                    }
                } else {
                    Toast.makeText(this, "Đang kết nối lại máy chủ...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshFeedWithRegion(region: String) {
        initProgress.visibility = View.VISIBLE
        repository.fetchOnlineTrending(region, 20) { items ->
            runOnUiThread {
                initProgress.visibility = View.GONE
                if (items.isNotEmpty()) {
                    videoList.clear()
                    videoList.addAll(items)
                    adapter.notifyDataSetChanged()
                    viewPager.setCurrentItem(0, false)
                    playVideoAt(0)
                }
            }
        }
    }

    private fun loadMoreVideos() {
        if (isLoadingMore) return
        isLoadingMore = true

        repository.fetchOnlineTrending(currentRegion, 20) { moreItems ->
            runOnUiThread {
                isLoadingMore = false
                if (moreItems.isNotEmpty()) {
                    val start = videoList.size
                    videoList.addAll(moreItems)
                    adapter.notifyItemRangeInserted(start, moreItems.size)
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
                            getCurrentViewHolder()?.discAnimator?.resume()
                            syncWithOriginIsland(true)
                        }
                        Player.STATE_ENDED -> {
                            if (isAutoScrollEnabled) {
                                // TỰ ĐỘNG CUỘN SANG VIDEO TIẾP THEO
                                playNextVideo()
                            } else {
                                // LẶP LẠI VIDEO HIỆN TẠI
                                exoPlayer?.seekTo(0)
                                exoPlayer?.play()
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Nếu gặp video lỗi link CDN, tự động chuyển ngay sang bài tiếp theo sau 600ms
                    viewPager.postDelayed({
                        playNextVideo()
                    }, 600)
                }
            })
        }
    }

    private fun setupViewPager() {
        adapter = TikTokVideoAdapter(
            items = videoList,
            onVideoClick = { _, isPlaying ->
                if (isPlaying) {
                    resumeCurrentVideo()
                } else {
                    pauseCurrentVideo()
                }
            },
            onAuthorClick = { item ->
                showAuthorProfileDialog(item)
            },
            onLongClick = { item ->
                showOptionsMenu(item)
            },
            onShareClick = { item ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${item.title} - ${item.videoUrl}")
                }
                startActivity(Intent.createChooser(shareIntent, "Chia sẻ video"))
            }
        )

        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 1

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                playVideoAt(position)

                // CUỘN VÔ TẬN: Khi còn 4 video là đến đáy, tự động nạp tiếp 20 video mới
                if (position >= videoList.size - 4) {
                    loadMoreVideos()
                }
            }
        })

        startProgressUpdater()
    }

    private fun playVideoAt(position: Int) {
        if (position < 0 || position >= videoList.size) return
        currentPosition = position

        val item = videoList[position]
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()

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
        syncWithOriginIsland(true)
    }

    private fun pauseCurrentVideo() {
        exoPlayer?.pause()
        getCurrentViewHolder()?.ivCenterPlay?.visibility = View.VISIBLE
        getCurrentViewHolder()?.discAnimator?.pause()
        syncWithOriginIsland(false)
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
            loadMoreVideos()
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
