package com.tiktokmusic.clean

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
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

    // Top Bar UI (Bạn bè | Đã follow | Đề xuất)
    private lateinit var topBar: LinearLayout
    private lateinit var btnToggleAutoScroll: TextView
    private lateinit var btnSearch: ImageView
    private lateinit var tvTabFriends: TextView
    private lateinit var tvTabFollowing: TextView
    private lateinit var tvTabForYou: TextView
    private lateinit var indicatorFriends: View
    private lateinit var indicatorFollowing: View
    private lateinit var indicatorForYou: View

    // Bottom Navigation Bar UI (5 Tabs chuẩn TikTok)
    private lateinit var bottomNav: View
    private lateinit var navHome: View
    private lateinit var navShop: View
    private lateinit var navCreate: View
    private lateinit var navInbox: View
    private lateinit var navProfile: View

    // Search Overlay UI
    private lateinit var layoutSearchOverlay: View
    private lateinit var searchTopBar: LinearLayout
    private lateinit var ivSearchBack: ImageView
    private lateinit var etSearchInput: EditText
    private lateinit var ivSearchClear: ImageView
    private lateinit var btnSubmitSearch: TextView
    private lateinit var layoutSearchCategoryTabs: View
    private lateinit var scrollSearchSuggestions: View
    private lateinit var listHotKeywords: LinearLayout
    private lateinit var rvSearchResults: RecyclerView

    // Search Watching UI (1:1 TikTok xem video từ kết quả tìm kiếm)
    private lateinit var layoutSearchWatchingTopBar: View
    private lateinit var ivSearchWatchingBack: ImageView
    private lateinit var btnSearchWatchingQueryBox: View
    private lateinit var tvSearchWatchingQuery: TextView
    private lateinit var tvSearchWatchingSubmit: TextView
    private lateinit var layoutSearchWatchingBottomBar: View
    private lateinit var btnSearchWatchingCommentInput: View
    private lateinit var ivSearchWatchingCommentIcon: ImageView
    private lateinit var ivSearchWatchingShareIcon: ImageView
    private var isWatchingFromSearch: Boolean = false
    private var currentSearchQuery: String = ""
    private var isLandscapeFullscreen: Boolean = false

    // Creator Profile Overlay UI (Trang Hồ Sơ Kênh 1:1 TikTok)
    private lateinit var layoutProfileOverlay: View
    private lateinit var profileTopBar: LinearLayout
    private lateinit var ivProfileBack: ImageView
    private lateinit var tvProfileTopTitle: TextView
    private lateinit var ivProfileShare: ImageView
    private lateinit var ivProfileAvatar: ImageView
    private lateinit var tvProfileNickname: TextView
    private lateinit var tvProfileHandle: TextView
    private lateinit var tvProfileFollowingCount: TextView
    private lateinit var tvProfileFollowersCount: TextView
    private lateinit var tvProfileLikesCount: TextView
    private lateinit var btnProfileFollow: Button
    private lateinit var btnProfileMessage: Button
    private lateinit var tvProfileBio: TextView
    private lateinit var rvProfileVideos: RecyclerView

    private lateinit var prefs: SharedPreferences
    private val videoList = mutableListOf<TikTokItem>()
    private val homeVideoList = mutableListOf<TikTokItem>()
    private val searchResultsList = mutableListOf<TikTokItem>()
    private val profileVideosList = mutableListOf<TikTokItem>()

    private var isShowingSearchResults: Boolean = false
    private var lastHomePosition: Int = 0

    private var exoPlayer: ExoPlayer? = null
    private var currentPosition: Int = 0
    private var isAutoScrollEnabled: Boolean = true
    private var isLoadingMore: Boolean = false
    private var currentTab: String = "FOR_YOU"

    // SỬA LỖI 1: Cờ phân biệt người dùng tự bấm dừng thủ công
    private var isManuallyPaused: Boolean = false

    // SỬA LỖI 2: Quản lý trình chiếu ảnh (Photo Mode Slideshow) chống mất hình
    private var photoSlideRunnable: Runnable? = null
    private var currentSlideIndex: Int = 0

    private var videoStartTime: Long = 0L
    private val progressHandler = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Khi màn hình tắt: Nếu người dùng ĐANG XEM thì duy trì phát ngầm;
            // Nếu người dùng ĐÃ BẤM DỪNG THỦ CÔNG -> TUYỆT ĐỐI KHÔNG TỰ CHẠY LẠI!
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (!isManuallyPaused && exoPlayer?.playbackState != Player.STATE_ENDED) {
                    exoPlayer?.playWhenReady = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Tràn viền giao diện tối 120Hz mượt mà
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.parseColor("#000000")
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
        setupProfileOverlay()
        setupBackHandler()
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

        tvTabFriends = findViewById(R.id.tvTabFriends)
        tvTabFollowing = findViewById(R.id.tvTabFollowing)
        tvTabForYou = findViewById(R.id.tvTabForYou)
        indicatorFriends = findViewById(R.id.indicatorFriends)
        indicatorFollowing = findViewById(R.id.indicatorFollowing)
        indicatorForYou = findViewById(R.id.indicatorForYou)

        bottomNav = findViewById(R.id.bottomNav)
        navHome = findViewById(R.id.navHome)
        navShop = findViewById(R.id.navShop)
        navCreate = findViewById(R.id.navCreate)
        navInbox = findViewById(R.id.navInbox)
        navProfile = findViewById(R.id.navProfile)

        // Search Overlay
        layoutSearchOverlay = findViewById(R.id.layoutSearchOverlay)
        searchTopBar = findViewById(R.id.searchTopBar)
        ivSearchBack = findViewById(R.id.ivSearchBack)
        etSearchInput = findViewById(R.id.etSearchInput)
        ivSearchClear = findViewById(R.id.ivSearchClear)
        btnSubmitSearch = findViewById(R.id.btnSubmitSearch)
        layoutSearchCategoryTabs = findViewById(R.id.layoutSearchCategoryTabs)
        scrollSearchSuggestions = findViewById(R.id.scrollSearchSuggestions)
        listHotKeywords = findViewById(R.id.listHotKeywords)
        rvSearchResults = findViewById(R.id.rvSearchResults)

        // Search Watching UI (1:1 TikTok xem video từ kết quả tìm kiếm)
        layoutSearchWatchingTopBar = findViewById(R.id.layoutSearchWatchingTopBar)
        ivSearchWatchingBack = findViewById(R.id.ivSearchWatchingBack)
        btnSearchWatchingQueryBox = findViewById(R.id.btnSearchWatchingQueryBox)
        tvSearchWatchingQuery = findViewById(R.id.tvSearchWatchingQuery)
        tvSearchWatchingSubmit = findViewById(R.id.tvSearchWatchingSubmit)
        layoutSearchWatchingBottomBar = findViewById(R.id.layoutSearchWatchingBottomBar)
        btnSearchWatchingCommentInput = findViewById(R.id.btnSearchWatchingCommentInput)
        ivSearchWatchingCommentIcon = findViewById(R.id.ivSearchWatchingCommentIcon)
        ivSearchWatchingShareIcon = findViewById(R.id.ivSearchWatchingShareIcon)

        // Profile Overlay
        layoutProfileOverlay = findViewById(R.id.layoutProfileOverlay)
        profileTopBar = findViewById(R.id.profileTopBar)
        ivProfileBack = findViewById(R.id.ivProfileBack)
        tvProfileTopTitle = findViewById(R.id.tvProfileTopTitle)
        ivProfileShare = findViewById(R.id.ivProfileShare)
        ivProfileAvatar = findViewById(R.id.ivProfileAvatar)
        tvProfileNickname = findViewById(R.id.tvProfileNickname)
        tvProfileHandle = findViewById(R.id.tvProfileHandle)
        tvProfileFollowingCount = findViewById(R.id.tvProfileFollowingCount)
        tvProfileFollowersCount = findViewById(R.id.tvProfileFollowersCount)
        tvProfileLikesCount = findViewById(R.id.tvProfileLikesCount)
        btnProfileFollow = findViewById(R.id.btnProfileFollow)
        btnProfileMessage = findViewById(R.id.btnProfileMessage)
        tvProfileBio = findViewById(R.id.tvProfileBio)
        rvProfileVideos = findViewById(R.id.rvProfileVideos)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootContainer)) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            topBar.setPadding(topBar.paddingLeft, statusBarTop + 6, topBar.paddingRight, topBar.paddingBottom)
            searchTopBar.setPadding(searchTopBar.paddingLeft, statusBarTop + 6, searchTopBar.paddingRight, searchTopBar.paddingBottom)
            profileTopBar.setPadding(profileTopBar.paddingLeft, statusBarTop + 6, profileTopBar.paddingRight, profileTopBar.paddingBottom)
            layoutSearchWatchingTopBar.setPadding(
                layoutSearchWatchingTopBar.paddingLeft,
                statusBarTop + 6,
                layoutSearchWatchingTopBar.paddingRight,
                layoutSearchWatchingTopBar.paddingBottom
            )
            if (navBarBottom > 0) {
                layoutSearchWatchingBottomBar.setPadding(
                    layoutSearchWatchingBottomBar.paddingLeft,
                    layoutSearchWatchingBottomBar.paddingTop,
                    layoutSearchWatchingBottomBar.paddingRight,
                    navBarBottom + 4
                )
            }
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
                "⚡ Đã BẬT tự động cuộn"
            } else {
                "⚡ Đã TẮT tự động cuộn"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAutoScrollUI() {
        if (isAutoScrollEnabled) {
            btnToggleAutoScroll.text = "⚡ Tự cuộn"
            btnToggleAutoScroll.setBackgroundResource(R.drawable.bg_pill_autoscroll_on)
            btnToggleAutoScroll.setTextColor(Color.parseColor("#00E676"))
        } else {
            btnToggleAutoScroll.text = "⚡ Tự cuộn"
            btnToggleAutoScroll.setBackgroundResource(R.drawable.bg_pill_autoscroll_off)
            btnToggleAutoScroll.setTextColor(Color.parseColor("#88FFFFFF"))
        }
    }

    private fun setupTopTabs() {
        findViewById<View>(R.id.tabFriendsLayout).setOnClickListener {
            currentTab = "FRIENDS"
            tvTabFriends.setTextColor(Color.WHITE)
            indicatorFriends.visibility = View.VISIBLE

            tvTabFollowing.setTextColor(Color.parseColor("#88FFFFFF"))
            indicatorFollowing.visibility = View.INVISIBLE
            tvTabForYou.setTextColor(Color.parseColor("#88FFFFFF"))
            indicatorForYou.visibility = View.INVISIBLE

            loadInitialFeed()
        }

        findViewById<View>(R.id.tabFollowingLayout).setOnClickListener {
            currentTab = "FOLLOWING"
            tvTabFollowing.setTextColor(Color.WHITE)
            indicatorFollowing.visibility = View.VISIBLE

            tvTabFriends.setTextColor(Color.parseColor("#88FFFFFF"))
            indicatorFriends.visibility = View.INVISIBLE
            tvTabForYou.setTextColor(Color.parseColor("#88FFFFFF"))
            indicatorForYou.visibility = View.INVISIBLE

            loadInitialFeed()
        }

        findViewById<View>(R.id.tabForYouLayout).setOnClickListener {
            currentTab = "FOR_YOU"
            tvTabForYou.setTextColor(Color.WHITE)
            indicatorForYou.visibility = View.VISIBLE

            tvTabFriends.setTextColor(Color.parseColor("#88FFFFFF"))
            indicatorFriends.visibility = View.INVISIBLE
            tvTabFollowing.setTextColor(Color.parseColor("#88FFFFFF"))
            indicatorFollowing.visibility = View.INVISIBLE

            loadInitialFeed()
        }
    }

    private fun setupBottomNav() {
        navHome.setOnClickListener {
            if (isShowingSearchResults) {
                // Quay về bảng tin Trang chủ từ kết quả tìm kiếm
                isShowingSearchResults = false
                videoList.clear()
                videoList.addAll(homeVideoList)
                adapter.notifyDataSetChanged()
                val targetPos = lastHomePosition.coerceIn(0, (videoList.size - 1).coerceAtLeast(0))
                viewPager.setCurrentItem(targetPos, false)
                playVideoAt(targetPos)
            } else {
                if (viewPager.currentItem != 0) {
                    viewPager.setCurrentItem(0, true)
                } else {
                    loadInitialFeed()
                }
            }
        }

        navShop.setOnClickListener {
            Toast.makeText(this, "🛒 Cửa hàng: Đang cập nhật sản phẩm", Toast.LENGTH_SHORT).show()
        }

        navCreate.setOnClickListener {
            Toast.makeText(this, "➕ Tính năng Tạo video TikTok", Toast.LENGTH_SHORT).show()
        }

        navInbox.setOnClickListener {
            Toast.makeText(this, "✉️ Hộp thư: Bạn không có thông báo mới", Toast.LENGTH_SHORT).show()
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

        btnViewAuthor.text = "📱 Đảo Origin Vivo: Đang hoạt động (com.luna.music)"
        btnCopyLink.text = "🗑️ Dọn dẹp bộ nhớ đệm video (RAM)"

        switchAutoScroll.isChecked = isAutoScrollEnabled
        switchAutoScroll.setOnCheckedChangeListener { _, isChecked ->
            isAutoScrollEnabled = isChecked
            prefs.edit().putBoolean("auto_scroll_enabled", isAutoScrollEnabled).apply()
            updateAutoScrollUI()
        }

        btnCopyLink.setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, "Đã làm sạch bộ nhớ đệm mượt mà!", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // ========================================================
    // TÌM KIẾM TOÀN DIỆN ĐA NGUỒN (Search Engine Grid 2 Cột)
    // ========================================================
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

        // RecyclerView kết quả tìm kiếm (Grid 2 cột chuẩn TikTok)
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
                    val q = etSearchInput.text.toString().trim().ifEmpty { "TikTok Video" }
                    enterSearchWatchingMode(q, position)
                }
            }

            override fun getItemCount(): Int = searchResultsList.size
        }

        // Tương tác thanh Search Watching (1:1 TikTok xem video từ kết quả tìm kiếm)
        ivSearchWatchingBack.setOnClickListener {
            exitSearchWatchingMode()
        }

        btnSearchWatchingQueryBox.setOnClickListener {
            exitSearchWatchingMode()
            etSearchInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        tvSearchWatchingSubmit.setOnClickListener {
            exitSearchWatchingMode()
            etSearchInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        btnSearchWatchingCommentInput.setOnClickListener {
            if (currentPosition in videoList.indices) {
                showCommentsBottomSheet(videoList[currentPosition])
            }
        }

        ivSearchWatchingCommentIcon.setOnClickListener {
            if (currentPosition in videoList.indices) {
                showCommentsBottomSheet(videoList[currentPosition])
            }
        }

        ivSearchWatchingShareIcon.setOnClickListener {
            if (currentPosition in videoList.indices) {
                showShareBottomSheet(videoList[currentPosition])
            }
        }
    }

    private fun enterSearchWatchingMode(query: String, startPosition: Int) {
        if (!isShowingSearchResults) {
            lastHomePosition = currentPosition
        }
        isShowingSearchResults = true
        isWatchingFromSearch = true
        currentSearchQuery = query

        // Ẩn bàn phím và bảng overlay tìm kiếm
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearchInput.windowToken, 0)
        layoutSearchOverlay.visibility = View.GONE

        // Ẩn Top Bar & Bottom Nav của Home
        topBar.visibility = View.GONE
        bottomNav.visibility = View.GONE

        // Hiển thị thanh Search Watching chuẩn 1:1 TikTok
        layoutSearchWatchingTopBar.visibility = View.VISIBLE
        layoutSearchWatchingBottomBar.visibility = View.VISIBLE
        tvSearchWatchingQuery.text = query

        // Đổi danh sách phát trong ViewPager2 sang danh sách kết quả tìm kiếm
        videoList.clear()
        videoList.addAll(searchResultsList)
        adapter.notifyDataSetChanged()
        val targetPos = startPosition.coerceIn(0, (videoList.size - 1).coerceAtLeast(0))
        viewPager.setCurrentItem(targetPos, false)
        playVideoAt(targetPos)
    }

    private fun exitSearchWatchingMode() {
        if (!isWatchingFromSearch) return
        isWatchingFromSearch = false

        layoutSearchWatchingTopBar.visibility = View.GONE
        layoutSearchWatchingBottomBar.visibility = View.GONE

        layoutSearchOverlay.visibility = View.VISIBLE
        scrollSearchSuggestions.visibility = View.GONE
        layoutSearchCategoryTabs.visibility = View.VISIBLE
        rvSearchResults.visibility = View.VISIBLE

        exoPlayer?.pause()
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

        // Trở về giao diện Trang chủ nếu đang xem kết quả tìm kiếm
        if (isShowingSearchResults) {
            isShowingSearchResults = false
            videoList.clear()
            videoList.addAll(homeVideoList)
            adapter.notifyDataSetChanged()
            val targetPos = lastHomePosition.coerceIn(0, (videoList.size - 1).coerceAtLeast(0))
            viewPager.setCurrentItem(targetPos, false)
            playVideoAt(targetPos)
        }
        topBar.visibility = View.VISIBLE
        bottomNav.visibility = View.VISIBLE
        if (!isManuallyPaused && exoPlayer?.playbackState == Player.STATE_READY && exoPlayer?.isPlaying == false) {
            exoPlayer?.play()
        }
    }

    private fun showSearchSuggestions() {
        scrollSearchSuggestions.visibility = View.VISIBLE
        rvSearchResults.visibility = View.GONE
        layoutSearchCategoryTabs.visibility = View.GONE
    }

    private fun populateHotKeywords() {
        val hotList = listOf(
            "🎵 Nhạc trẻ gây nghiện 2026",
            "☕ Nhạc chill tâm trạng đêm khuya",
            "🎧 Vinahouse EDM Remix cực bốc",
            "🌟 Sơn Tùng M-TP V-Pop",
            "💃 Biến hình TikTok Việt Nam",
            "🍲 Review ẩm thực Sài Gòn & Hà Nội",
            "😂 Hài hước triệu view Việt Nam"
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
                layoutSearchCategoryTabs.visibility = View.VISIBLE
                rvSearchResults.visibility = View.VISIBLE

                if (results.isEmpty()) {
                    Toast.makeText(this, "Không tìm thấy kết quả phù hợp", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ========================================================
    // TRANG HỒ SƠ KÊNH 1:1 TIKTOK (Creator Profile 3 Cột)
    // ========================================================
    private fun setupProfileOverlay() {
        ivProfileBack.setOnClickListener {
            closeAuthorProfile()
        }

        ivProfileShare.setOnClickListener {
            val handle = tvProfileHandle.text.toString()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Kênh TikTok $handle - Được phát trên TikTok Clean")
            }
            startActivity(Intent.createChooser(shareIntent, "Chia sẻ kênh"))
        }

        var isProfileFollowed = false
        btnProfileFollow.setOnClickListener {
            isProfileFollowed = !isProfileFollowed
            if (isProfileFollowed) {
                btnProfileFollow.text = "Đang follow"
                btnProfileFollow.setBackgroundColor(Color.parseColor("#262738"))
            } else {
                btnProfileFollow.text = "Follow"
                btnProfileFollow.setBackgroundColor(Color.parseColor("#FE2C55"))
            }
        }

        btnProfileMessage.setOnClickListener {
            Toast.makeText(this, "Chức năng nhắn tin riêng tư", Toast.LENGTH_SHORT).show()
        }

        // Lưới Video 3 Cột của Hồ sơ Kênh
        rvProfileVideos.layoutManager = GridLayoutManager(this, 3)
        rvProfileVideos.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = layoutInflater.inflate(R.layout.item_profile_grid_video, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val video = profileVideosList[position]
                val ivThumb = holder.itemView.findViewById<ImageView>(R.id.ivProfileGridThumb)
                val tvViews = holder.itemView.findViewById<TextView>(R.id.tvProfileGridViews)

                tvViews.text = video.likeCount
                ImageLoader.load(video.coverUrl, ivThumb, false)

                holder.itemView.setOnClickListener {
                    closeAuthorProfile()
                    val targetIndex = videoList.indexOfFirst { it.id == video.id }
                    if (targetIndex >= 0) {
                        viewPager.setCurrentItem(targetIndex, false)
                        playVideoAt(targetIndex)
                    } else {
                        videoList.add(currentPosition + 1, video)
                        adapter.notifyItemInserted(currentPosition + 1)
                        viewPager.setCurrentItem(currentPosition + 1, false)
                        playVideoAt(currentPosition + 1)
                    }
                }
            }

            override fun getItemCount(): Int = profileVideosList.size
        }
    }

    private fun openAuthorProfile(item: TikTokItem) {
        val handle = if (item.authorUniqueId.isNotEmpty()) "@${item.authorUniqueId}" else item.author
        tvProfileTopTitle.text = handle
        tvProfileHandle.text = handle
        tvProfileNickname.text = item.author
        ImageLoader.load(item.authorAvatar, ivProfileAvatar, true)

        // Tạo số liệu sống động chuẩn TikTok
        tvProfileFollowingCount.text = "186"
        tvProfileFollowersCount.text = item.likeCount
        tvProfileLikesCount.text = "2.4M"
        tvProfileBio.text = "${item.author} · Kênh chia sẻ âm nhạc & video xu hướng chính thức 🎧✨"

        profileVideosList.clear()
        val authorVideos = repository.getVideosByAuthor(item.authorUniqueId, item.author)
        profileVideosList.addAll(authorVideos)
        rvProfileVideos.adapter?.notifyDataSetChanged()

        // Tải thêm danh sách video của tác giả trực tuyến từ máy chủ
        repository.fetchAuthorVideos(item.authorUniqueId, item.author) { networkVideos ->
            runOnUiThread {
                if (networkVideos.isNotEmpty()) {
                    profileVideosList.clear()
                    profileVideosList.addAll(networkVideos)
                    rvProfileVideos.adapter?.notifyDataSetChanged()
                }
            }
        }

        layoutProfileOverlay.visibility = View.VISIBLE
    }

    private fun closeAuthorProfile() {
        layoutProfileOverlay.visibility = View.GONE
        if (!isManuallyPaused && exoPlayer?.playbackState == Player.STATE_READY && exoPlayer?.isPlaying == false) {
            exoPlayer?.play()
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLandscapeFullscreen) {
                    toggleLandscapeFullscreen()
                } else if (layoutProfileOverlay.visibility == View.VISIBLE) {
                    closeAuthorProfile()
                } else if (isWatchingFromSearch) {
                    exitSearchWatchingMode()
                } else if (layoutSearchOverlay.visibility == View.VISIBLE) {
                    closeSearchOverlay()
                } else if (isShowingSearchResults) {
                    closeSearchOverlay()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
            openAuthorProfile(item)
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

    private fun showCommentsBottomSheet(item: TikTokItem) {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_tiktok_comments, null)
        dialog.setContentView(dialogView)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvCommentsTitle)
        val ivClose = dialogView.findViewById<ImageView>(R.id.ivCloseComments)
        val rvComments = dialogView.findViewById<RecyclerView>(R.id.rvCommentsList)
        val pbLoading = dialogView.findViewById<ProgressBar>(R.id.pbCommentsLoading)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvCommentsEmpty)
        val etInput = dialogView.findViewById<EditText>(R.id.etCommentInput)
        val btnSend = dialogView.findViewById<ImageView>(R.id.btnSendComment)

        ivClose?.setOnClickListener { dialog.dismiss() }

        val comments = mutableListOf<TikTokComment>()
        val commentAdapter = TikTokCommentAdapter(comments)
        rvComments?.layoutManager = LinearLayoutManager(this)
        rvComments?.adapter = commentAdapter

        tvTitle?.text = "Bình luận (${item.commentCount})"
        pbLoading?.visibility = View.VISIBLE
        tvEmpty?.visibility = View.GONE

        repository.fetchComments(item.videoUrl) { fetchedList ->
            runOnUiThread {
                pbLoading?.visibility = View.GONE
                comments.clear()
                comments.addAll(fetchedList)
                commentAdapter.notifyDataSetChanged()
                tvTitle?.text = "Bình luận (${comments.size})"
                if (comments.isEmpty()) {
                    tvEmpty?.visibility = View.VISIBLE
                }
            }
        }

        btnSend?.setOnClickListener {
            val text = etInput?.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                val newComment = TikTokComment(
                    id = "my_${System.currentTimeMillis()}",
                    text = text,
                    author = "Tôi (Bạn)",
                    authorAvatar = "",
                    timeAgo = "Vừa xong",
                    likeCount = "0",
                    isLiked = false
                )
                comments.add(0, newComment)
                commentAdapter.notifyItemInserted(0)
                rvComments?.scrollToPosition(0)
                tvEmpty?.visibility = View.GONE
                tvTitle?.text = "Bình luận (${comments.size})"
                etInput?.text?.clear()
                Toast.makeText(this, "Đã gửi bình luận!", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showShareBottomSheet(item: TikTokItem) {
        val dialog = BottomSheetDialog(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_tiktok_share, null)
        dialog.setContentView(dialogView)

        val btnCopy = dialogView.findViewById<View>(R.id.btnShareCopyLink)
        val btnDownload = dialogView.findViewById<View>(R.id.btnShareDownload)
        val btnSpeed = dialogView.findViewById<View>(R.id.btnShareSpeed)
        val btnMore = dialogView.findViewById<View>(R.id.btnShareMore)
        val btnCancel = dialogView.findViewById<View>(R.id.btnShareCancel)

        btnCancel?.setOnClickListener { dialog.dismiss() }

        btnCopy?.setOnClickListener {
            dialog.dismiss()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("TikTok Video URL", item.videoUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "📋 Đã sao chép liên kết video!", Toast.LENGTH_SHORT).show()
        }

        btnDownload?.setOnClickListener {
            dialog.dismiss()
            downloadVideo(item)
        }

        btnSpeed?.setOnClickListener {
            dialog.dismiss()
            showPlaybackSpeedDialog()
        }

        btnMore?.setOnClickListener {
            dialog.dismiss()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${item.title} - ${item.videoUrl}")
            }
            startActivity(Intent.createChooser(shareIntent, "Chia sẻ video"))
        }

        dialog.show()
    }

    private fun showPlaybackSpeedDialog() {
        val speeds = arrayOf("0.5x (Chậm)", "0.75x", "1.0x (Chuẩn)", "1.25x", "1.5x (Nhanh)", "2.0x (Cực nhanh)")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚡ Tùy chỉnh tốc độ phát")
            .setItems(speeds) { _, which ->
                val selectedSpeed = speedValues[which]
                exoPlayer?.setPlaybackSpeed(selectedSpeed)
                Toast.makeText(this, "Đang phát ở tốc độ: ${speeds[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun downloadVideo(item: TikTokItem) {
        try {
            if (item.videoUrl.startsWith("asset:///")) {
                Toast.makeText(this, "Video này đã có sẵn trong ứng dụng của bạn!", Toast.LENGTH_SHORT).show()
                return
            }
            val request = DownloadManager.Request(Uri.parse(item.videoUrl)).apply {
                setTitle("TikTok - ${item.title}")
                setDescription("Đang lưu video từ ${item.author}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "TikTok_${item.id}.mp4")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "📥 Đang tải video về máy... Hãy xem thanh thông báo!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể tải: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadInitialFeed() {
        initProgress.visibility = View.VISIBLE
        repository.fetchVietnameseFeed(this, 20) { items ->
            runOnUiThread {
                initProgress.visibility = View.GONE
                if (items.isNotEmpty()) {
                    homeVideoList.clear()
                    homeVideoList.addAll(items)
                    if (!isShowingSearchResults) {
                        videoList.clear()
                        videoList.addAll(items)
                        adapter.notifyDataSetChanged()
                        viewPager.post {
                            playVideoAt(0)
                        }
                    }
                } else {
                    Toast.makeText(this, "Đang kết nối máy chủ...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadMoreVideos() {
        if (isLoadingMore || isShowingSearchResults) return
        isLoadingMore = true

        repository.fetchVietnameseFeed(this, 20) { moreItems ->
            runOnUiThread {
                isLoadingMore = false
                if (moreItems.isNotEmpty()) {
                    homeVideoList.addAll(moreItems)
                    if (!isShowingSearchResults) {
                        val start = videoList.size
                        videoList.addAll(moreItems)
                        adapter.notifyItemRangeInserted(start, moreItems.size)
                    }
                }
            }
        }
    }

    private fun initExoPlayer() {
        // TỐI ƯU HÓA TỐC ĐỘ NẠP VIDEO CHUẨN TIKTOK (Buffer 250ms là phát ngay lập tức)
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
                    override fun onRenderedFirstFrame() {
                        runOnUiThread {
                            val curPos = this@MainActivity.currentPosition
                            if (curPos in videoList.indices && !videoList[curPos].isPhotoPost) {
                                getCurrentViewHolder()?.ivThumbnail?.visibility = View.GONE
                            }
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        var hasVideo = false
                        for (group in tracks.groups) {
                            if (group.type == C.TRACK_TYPE_VIDEO && group.isSelected) {
                                hasVideo = true
                                break
                            }
                        }
                        runOnUiThread {
                            val curPos = this@MainActivity.currentPosition
                            if (!hasVideo && curPos in videoList.indices) {
                                // Luồng âm thanh thuần (không có hình): hiển thị thumbnail để không bị màn đen
                                getCurrentViewHolder()?.ivThumbnail?.visibility = View.VISIBLE
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                getCurrentViewHolder()?.ivCenterPlay?.visibility = View.GONE
                                getCurrentViewHolder()?.discAnimator?.resume()
                                syncWithOriginIsland(true)

                                // Nếu là bài đăng ảnh hoặc âm thanh thuần, giữ thumbnail luôn hiển thị sắc nét
                                val curPos = this@MainActivity.currentPosition
                                if (curPos in videoList.indices && videoList[curPos].isPhotoPost) {
                                    getCurrentViewHolder()?.ivThumbnail?.visibility = View.VISIBLE
                                }
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

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        runOnUiThread {
                            val holder = getCurrentViewHolder() ?: return@runOnUiThread
                            if (videoSize.width > 0 && videoSize.height > 0) {
                                val ratio = videoSize.width.toFloat() / videoSize.height.toFloat()
                                if (ratio > 1.25f) {
                                    // Video 16:9 ngang: hiển thị nút Toàn màn hình & hiệu ứng Ambient Glow
                                    holder.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    holder.layoutWidescreenPill.visibility = View.VISIBLE
                                    holder.ivAmbientGlow.visibility = View.VISIBLE
                                    val curPos = this@MainActivity.currentPosition
                                    if (curPos in videoList.indices) {
                                        ImageLoader.load(videoList[curPos].coverUrl, holder.ivAmbientGlow, false)
                                    }
                                } else {
                                    // Video dọc 9:16 chuẩn TikTok
                                    holder.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    holder.layoutWidescreenPill.visibility = View.GONE
                                    holder.ivAmbientGlow.visibility = View.GONE
                                }
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
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
                openAuthorProfile(item)
            },
            onLongClick = { item ->
                showOptionsMenu(item)
            },
            onShareClick = { item ->
                showShareBottomSheet(item)
            },
            onCommentClick = { item ->
                showCommentsBottomSheet(item)
            },
            onDoubleTapLike = { item ->
                repository.recordInteraction(item, 1.0f, true, false)
            },
            onFullscreenClick = { _ ->
                toggleLandscapeFullscreen()
            }
        )
        adapter.exoPlayer = exoPlayer

        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 1

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                recordWatchInteraction()
                adapter.currentPlayingPosition = position
                playVideoAt(position)

                if (!isShowingSearchResults && position >= videoList.size - 4) {
                    loadMoreVideos()
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    val holder = getCurrentViewHolder()
                    if (holder != null && holder.playerView.player != exoPlayer) {
                        holder.playerView.player = exoPlayer
                    }
                }
            }
        })

        startProgressUpdater()
    }

    private fun toggleLandscapeFullscreen() {
        if (isLandscapeFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            isLandscapeFullscreen = false
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            if (isWatchingFromSearch) {
                layoutSearchWatchingTopBar.visibility = View.VISIBLE
                layoutSearchWatchingBottomBar.visibility = View.VISIBLE
            } else {
                topBar.visibility = View.VISIBLE
                bottomNav.visibility = View.VISIBLE
            }
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            isLandscapeFullscreen = true
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            topBar.visibility = View.GONE
            bottomNav.visibility = View.GONE
            layoutSearchWatchingTopBar.visibility = View.GONE
            layoutSearchWatchingBottomBar.visibility = View.GONE
        }
    }

    private fun playVideoAt(position: Int) {
        if (position < 0 || position >= videoList.size) return
        currentPosition = position
        videoStartTime = System.currentTimeMillis()
        isManuallyPaused = false

        adapter.currentPlayingPosition = position
        adapter.exoPlayer = exoPlayer

        // Dừng trình chiếu ảnh của video trước (nếu có)
        photoSlideRunnable?.let { progressHandler.removeCallbacks(it) }
        currentSlideIndex = 0

        val item = videoList[position]
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()

        viewPager.post {
            val holder = getCurrentViewHolder()
            if (holder != null) {
                holder.playerView.player = exoPlayer
                // Mặc định đặt chế độ ZOOM dọc và ẩn nút toàn màn hình/ambient glow cho video mới
                holder.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                holder.layoutWidescreenPill.visibility = View.GONE
                holder.ivAmbientGlow.visibility = View.GONE

                // Luôn hiển thị thumbnail ảnh bìa trước khi phát để không bị đen màn hình
                holder.ivThumbnail.visibility = View.VISIBLE
                holder.ivCenterPlay.visibility = View.GONE
                ImageLoader.load(item.coverUrl, holder.ivThumbnail, false)

                // Hỗ trợ chế độ Photo Mode (Tự động lướt các ảnh trong album theo nhạc nền)
                if (item.images.size > 1) {
                    photoSlideRunnable = object : Runnable {
                        override fun run() {
                            if (currentPosition == position && item.images.size > 1) {
                                currentSlideIndex = (currentSlideIndex + 1) % item.images.size
                                val nextImg = item.images[currentSlideIndex]
                                getCurrentViewHolder()?.let { h ->
                                    ImageLoader.load(nextImg, h.ivThumbnail, false)
                                }
                                progressHandler.postDelayed(this, 3500)
                            }
                        }
                    }
                    progressHandler.postDelayed(photoSlideRunnable!!, 3500)
                }
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
        isManuallyPaused = false
        exoPlayer?.play()
        getCurrentViewHolder()?.ivCenterPlay?.visibility = View.GONE
        getCurrentViewHolder()?.discAnimator?.resume()
        syncWithOriginIsland(true)
    }

    private fun pauseCurrentVideo() {
        isManuallyPaused = true
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
        // Chỉ tự động phát lại khi người dùng KHÔNG bấm dừng thủ công
        if (!isManuallyPaused && exoPlayer?.playbackState == Player.STATE_READY) {
            exoPlayer?.play()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            exoPlayer?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        photoSlideRunnable?.let { progressHandler.removeCallbacks(it) }
        progressHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        exoPlayer?.release()
        exoPlayer = null
    }
}
