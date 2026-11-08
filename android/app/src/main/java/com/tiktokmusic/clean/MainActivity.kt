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
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog

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

    // Top Bar UI
    private lateinit var topBar: LinearLayout
    private lateinit var btnToggleAutoScroll: TextView
    private lateinit var btnSearch: ImageView
    private lateinit var tvTabFollowing: TextView
    private lateinit var tvTabForYou: TextView
    private lateinit var indicatorFollowing: View
    private lateinit var indicatorForYou: View

    // Bottom Navigation Bar UI
    private lateinit var navHome: View
    private lateinit var navFriends: View
    private lateinit var navCreate: View
    private lateinit var navInbox: View
    private lateinit var navProfile: View

    // Search Overlay UI
    private lateinit var layoutSearchOverlay: View
    private lateinit var ivSearchBack: ImageView
    private lateinit var etSearchInput: EditText
    private lateinit var ivSearchClear: ImageView
    private lateinit var btnSubmitSearch: TextView
    private lateinit var scrollSearchSuggestions: View
    private lateinit var listHotKeywords: LinearLayout
    private lateinit var rvSearchResults: RecyclerView

    private lateinit var prefs: SharedPreferences
    private val videoList = mutableListOf<TikTokItem>()
    private val searchResultsList = mutableListOf<TikTokItem>()

    private var exoPlayer: ExoPlayer? = null
    private var currentPosition: Int = 0
    private var isAutoScrollEnabled: Boolean = true
    private var isLoadingMore: Boolean = false
    private var currentTab: String = "FOR_YOU"

    private var videoStartTime: Long = 0L
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
        window.navigationBarColor = Color.parseColor("#0A0A0E")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("tiktok_clean_prefs", Context.MODE_PRIVATE)
        isAutoScrollEnabled = prefs.getBoolean("auto_scroll_enabled", true)

        initViews()
        setupWindowInsets()
        setupAutoScrollButton()
        setupTopTabs()
        setupBottomNav()
        setupSearchOverlay()
        checkNotificationPermission()

        repository = TikTokRepository()
        initExoPlayer()
        setupViewPager()

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenReceiver, filter)

        // Nạp nguồn video Việt Nam thịnh hành ngay khi mở app
        loadInitialFeed()
    }

    private fun initViews() {
        viewPager = findViewById(R.id.viewPager)
        initProgress = findViewById(R.id.initProgress)
        topBar = findViewById(R.id.topBar)
        btnToggleAutoScroll = findViewById(R.id.btnToggleAutoScroll)
        btnSearch = findViewById(R.id.btnSearch)
        tvTabFollowing = findViewById(R.id.tvTabFollowing)
        tvTabForYou = findViewById(R.id.tvTabForYou)
        indicatorFollowing = findViewById(R.id.indicatorFollowing)
        indicatorForYou = findViewById(R.id.indicatorForYou)

        navHome = findViewById(R.id.navHome)
        navFriends = findViewById(R.id.navFriends)
        navCreate = findViewById(R.id.navCreate)
        navInbox = findViewById(R.id.navInbox)
        navProfile = findViewById(R.id.navProfile)

        layoutSearchOverlay = findViewById(R.id.layoutSearchOverlay)
        ivSearchBack = findViewById(R.id.ivSearchBack)
        etSearchInput = findViewById(R.id.etSearchInput)
        ivSearchClear = findViewById(R.id.ivSearchClear)
        btnSubmitSearch = findViewById(R.id.btnSubmitSearch)
        scrollSearchSuggestions = findViewById(R.id.scrollSearchSuggestions)
        listHotKeywords = findViewById(R.id.listHotKeywords)
        rvSearchResults = findViewById(R.id.rvSearchResults)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            // Tự động căn lề theo tai thỏ/nốt ruồi Vivo OriginOS 6 mà không bị đè
            topBar.setPadding(
                topBar.paddingLeft,
                statusBarTop + 8,
                topBar.paddingRight,
                topBar.paddingBottom
            )
            val searchTopBar = findViewById<LinearLayout>(R.id.searchTopBar)
            searchTopBar.setPadding(
                searchTopBar.paddingLeft,
                statusBarTop + 8,
                searchTopBar.paddingRight,
                searchTopBar.paddingBottom
            )
            insets
        }
    }

    private fun setupAutoScrollButton() {
        updateAutoScrollUI()
        btnToggleAutoScroll.setOnClickListener {
            isAutoScrollEnabled = !isAutoScrollEnabled
            prefs.edit().putBoolean("auto_scroll_enabled", isAutoScrollEnabled).apply()
            updateAutoScrollUI()
            val msg = if (isAutoScrollEnabled) {
                "⚡ Đã BẬT tự động cuộn (hết video tự chuyển)"
            } else {
                "⚡ Đã TẮT tự động cuộn (lặp lại video)"
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
        findViewById<View>(R.id.tabFollowingLayout).setOnClickListener {
            currentTab = "FOLLOWING"
            tvTabFollowing.setTextColor(Color.WHITE)
            indicatorFollowing.visibility = View.VISIBLE
            tvTabForYou.setTextColor(Color.parseColor("#88FFFFFF"))
            indicatorForYou.visibility = View.INVISIBLE
            loadInitialFeed()
        }

        findViewById<View>(R.id.tabForYouLayout).setOnClickListener {
            currentTab = "FOR_YOU"
            tvTabForYou.setTextColor(Color.WHITE)
            indicatorForYou.visibility = View.VISIBLE
            tvTabFollowing.setTextColor(Color.parseColor("#88FFFFFF"))
            indicatorFollowing.visibility = View.INVISIBLE
            loadInitialFeed()
        }
    }

    private fun setupBottomNav() {
        navHome.setOnClickListener {
            viewPager.setCurrentItem(0, true)
            loadInitialFeed()
        }

        navFriends.setOnClickListener {
            Toast.makeText(this, "Bạn bè: Đang đồng bộ danh bạ", Toast.LENGTH_SHORT).show()
        }

        navCreate.setOnClickListener {
            Toast.makeText(this, "Tính năng tạo video TikTok", Toast.LENGTH_SHORT).show()
        }

        navInbox.setOnClickListener {
            Toast.makeText(this, "Hộp thư: Không có tin nhắn mới", Toast.LENGTH_SHORT).show()
        }

        navProfile.setOnClickListener {
            showProfileBottomSheet()
        }
    }

    private fun showProfileBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_video_options, null)
        dialog.setContentView(dialogView)

        val switchAutoScroll = dialogView.findViewById<SwitchCompat>(R.id.switchAutoScrollOption)
        val btnViewAuthor = dialogView.findViewById<TextView>(R.id.btnOptionViewAuthor)
        val btnCopyLink = dialogView.findViewById<TextView>(R.id.btnOptionCopyLink)

        dialogView.findViewById<TextView>(android.R.id.text1)?.text = "Cài đặt TikTok Clean"
        btnViewAuthor.text = "📱 Trạng thái Đảo Origin: Đang liên kết com.luna.music"
        btnCopyLink.text = "🗑️ Xóa bộ nhớ đệm video (Giải phóng RAM)"

        switchAutoScroll.isChecked = isAutoScrollEnabled
        switchAutoScroll.setOnCheckedChangeListener { _, isChecked ->
            isAutoScrollEnabled = isChecked
            prefs.edit().putBoolean("auto_scroll_enabled", isAutoScrollEnabled).apply()
            updateAutoScrollUI()
        }

        btnCopyLink.setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, "Đã dọn dẹp bộ nhớ đệm mượt mà!", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun setupSearchOverlay() {
        btnSearch.setOnClickListener {
            openSearchOverlay()
        }

        ivSearchBack.setOnClickListener {
            closeSearchOverlay()
        }

        ivSearchClear.setOnClickListener {
            etSearchInput.text.clear()
            showSearchSuggestions()
        }

        btnSubmitSearch.setOnClickListener {
            val q = etSearchInput.text.toString().trim()
            if (q.isNotEmpty()) performSearch(q)
        }

        etSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = etSearchInput.text.toString().trim()
                if (q.isNotEmpty()) performSearch(q)
                true
            } else false
        }

        etSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                ivSearchClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        populateHotKeywords()

        // Cài đặt RecyclerView kết quả tìm kiếm (Grid 2 cột chuẩn TikTok)
        rvSearchResults.layoutManager = GridLayoutManager(this, 2)
        rvSearchResults.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = layoutInflater.inflate(R.layout.item_search_video_card, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = searchResultsList[position]
                val ivThumb = holder.itemView.findViewById<ImageView>(R.id.ivSearchCardThumb)
                val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvSearchCardTitle)
                val ivAvatar = holder.itemView.findViewById<ImageView>(R.id.ivSearchCardAvatar)
                val tvAuthor = holder.itemView.findViewById<TextView>(R.id.tvSearchCardAuthor)
                val tvLikes = holder.itemView.findViewById<TextView>(R.id.tvSearchCardLikes)

                tvTitle.text = item.title
                tvAuthor.text = item.author
                tvLikes.text = item.likeCount
                ImageLoader.load(item.coverUrl, ivThumb, false)
                ImageLoader.load(item.authorAvatar, ivAvatar, true)

                holder.itemView.setOnClickListener {
                    closeSearchOverlay()
                    videoList.clear()
                    videoList.addAll(searchResultsList)
                    adapter.notifyDataSetChanged()
                    viewPager.setCurrentItem(position, false)
                    playVideoAt(position)
                }
            }

            override fun getItemCount(): Int = searchResultsList.size
        }
    }

    private fun openSearchOverlay() {
        layoutSearchOverlay.visibility = View.VISIBLE
        etSearchInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etSearchInput, InputMethodManager.SHOW_IMPLICIT)
        showSearchSuggestions()
    }

    private fun closeSearchOverlay() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearchInput.windowToken, 0)
        layoutSearchOverlay.visibility = View.GONE
    }

    private fun showSearchSuggestions() {
        scrollSearchSuggestions.visibility = View.VISIBLE
        rvSearchResults.visibility = View.GONE
    }

    private fun populateHotKeywords() {
        val hotList = listOf(
            "🎵 Nhạc trẻ gây nghiện 2026",
            "☕ Nhạc chill tâm trạng đêm khuya",
            "🎧 Vinahouse EDM Remix cực bốc",
            "🌟 Sơn Tùng M-TP V-Pop",
            "💃 Biến hình TikTok Việt Nam",
            "🍲 Review ẩm thực Sài Gòn & Hà Nội",
            "😂 Hài hước giải trí triệu view"
        )

        listHotKeywords.removeAllViews()
        hotList.forEachIndexed { idx, kw ->
            val row = layoutInflater.inflate(R.layout.item_hot_keyword, listHotKeywords, false)
            val tvRank = row.findViewById<TextView>(R.id.tvKeywordRank)
            val tvText = row.findViewById<TextView>(R.id.tvKeywordText)
            val tvTag = row.findViewById<TextView>(R.id.tvKeywordTag)

            tvRank.text = (idx + 1).toString()
            tvText.text = kw
            if (idx >= 3) {
                tvRank.setTextColor(Color.parseColor("#88FFFFFF"))
                tvTag.visibility = View.GONE
            }

            row.setOnClickListener {
                val cleanQuery = kw.substring(2).trim()
                etSearchInput.setText(cleanQuery)
                etSearchInput.setSelection(cleanQuery.length)
                performSearch(cleanQuery)
            }
            listHotKeywords.addView(row)
        }
    }

    private fun performSearch(query: String) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearchInput.windowToken, 0)

        initProgress.visibility = View.VISIBLE
        repository.searchVideos(query) { results ->
            runOnUiThread {
                initProgress.visibility = View.GONE
                searchResultsList.clear()
                searchResultsList.addAll(results)
                rvSearchResults.adapter?.notifyDataSetChanged()

                scrollSearchSuggestions.visibility = View.GONE
                rvSearchResults.visibility = View.VISIBLE

                if (results.isEmpty()) {
                    Toast.makeText(this, "Không tìm thấy kết quả phù hợp", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
        repository.fetchVietnameseFeed(20) { items ->
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
                    Toast.makeText(this, "Đang kết nối máy chủ...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadMoreVideos() {
        if (isLoadingMore) return
        isLoadingMore = true

        repository.fetchVietnameseFeed(20) { moreItems ->
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
        // TỐI ƯU HÓA TỐC ĐỘ NẠP VIDEO CHUẨN TIKTOK (LoadControl cực nhanh: 250ms là phát ngay!)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1000, // minBufferMs: 1.0s
                8000, // maxBufferMs: 8.0s
                250,  // bufferForPlaybackMs: 0.25s (Bắt đầu phát tức thì 0.25s!)
                500   // bufferForPlaybackAfterRebufferMs: 0.5s
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().apply {
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
                                recordWatchInteraction()
                                if (isAutoScrollEnabled) {
                                    playNextVideo()
                                } else {
                                    exoPlayer?.seekTo(0)
                                    exoPlayer?.play()
                                }
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // Nếu gặp video lỗi link CDN, tự động chuyển ngay sang bài tiếp theo sau 500ms
                        viewPager.postDelayed({
                            playNextVideo()
                        }, 500)
                    }
                })
            }
    }

    private fun recordWatchInteraction() {
        if (currentPosition in videoList.indices && videoStartTime > 0L) {
            val watchedMs = System.currentTimeMillis() - videoStartTime
            val duration = exoPlayer?.duration?.coerceAtLeast(1L) ?: 1000L
            val ratio = (watchedMs.toFloat() / duration).coerceIn(0f, 1f)
            val holder = getCurrentViewHolder()
            val isLiked = holder?.isLiked == true
            val isSaved = holder?.isSaved == true

            repository.recordInteraction(videoList[currentPosition], ratio, isLiked, isSaved)
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
                recordWatchInteraction()
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
        videoStartTime = System.currentTimeMillis()

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
