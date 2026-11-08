package com.tiktokmusic.clean

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class TikTokRepository {

    private val executor = Executors.newFixedThreadPool(4)
    private val seenVideoIds = Collections.synchronizedSet(mutableSetOf<String>())
    val allLoadedVideos = Collections.synchronizedList(mutableListOf<TikTokItem>())

    // Thuật toán đề xuất: Lưu trữ điểm yêu thích của người dùng đối với các chủ đề
    private val userTagAffinities = ConcurrentHashMap<String, Float>().apply {
        put("chill", 1.0f)
        put("remix", 1.0f)
        put("vpop", 1.0f)
        put("tamtrang", 1.0f)
        put("xuhuong", 1.0f)
    }

    /**
     * Nạp nguồn cấp dữ liệu video Việt Nam chuẩn theo thuật toán đề xuất
     */
    fun fetchVietnameseFeed(
        context: Context? = null,
        targetCount: Int = 20,
        onResult: (List<TikTokItem>) -> Unit
    ) {
        executor.execute {
            val collectedVnItems = mutableListOf<TikTokItem>()

            // Ưu tiên nạp ngay các video chất lượng cao tích hợp sẵn (Offline Cache 0ms)
            if (allLoadedVideos.isEmpty() && context != null) {
                val offlineItems = loadOfflineVideos(context)
                for (item in offlineItems) {
                    if (!seenVideoIds.contains(item.id)) {
                        seenVideoIds.add(item.id)
                        collectedVnItems.add(item)
                        allLoadedVideos.add(item)
                    }
                }
            }

            var attempts = 0

            // Lặp để thu thập đủ số lượng video Việt Nam chất lượng cao
            while (collectedVnItems.size < targetCount && attempts < 4) {
                attempts++
                try {
                    val apiUrl = "https://www.tikwm.com/api/feed/list?region=VN&count=30"
                    val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6000
                        readTimeout = 6000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; V2318A) Mobile Safari/537.36")
                        setRequestProperty("Referer", "https://www.tikwm.com/")
                        setRequestProperty("Accept", "application/json")
                    }

                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(jsonStr)

                    if (root.optInt("code") == 0) {
                        val data = root.optJSONArray("data")
                        if (data != null) {
                            for (i in 0 until data.length()) {
                                val item = data.getJSONObject(i)
                                val playUrl = item.optString("play")
                                val id = item.optString("video_id", "vid_${System.currentTimeMillis()}_$i")

                                if (playUrl.isNotBlank() && !seenVideoIds.contains(id)) {
                                    val title = item.optString("title", "").trim()
                                    val authorObj = item.optJSONObject("author")
                                    val author = authorObj?.optString("nickname") ?: "@tiktok"
                                    val uniqueId = authorObj?.optString("unique_id") ?: ""
                                    val avatar = authorObj?.optString("avatar") ?: ""

                                    val musicObj = item.optJSONObject("music_info")
                                    val soundTitle = musicObj?.optString("title") ?: "♫ Âm thanh gốc"
                                    var cover = item.optString("cover")

                                    // Trích xuất danh sách ảnh (nếu là bài đăng Photo Mode/Album ảnh)
                                    val imagesArray = item.optJSONArray("images")
                                    val imagesList = mutableListOf<String>()
                                    if (imagesArray != null) {
                                        for (j in 0 until imagesArray.length()) {
                                            val img = imagesArray.optString(j)
                                            if (img.isNotBlank()) imagesList.add(img)
                                        }
                                    }
                                    if (cover.isBlank() && imagesList.isNotEmpty()) {
                                        cover = imagesList[0]
                                    }
                                    val isPhoto = imagesList.isNotEmpty() || playUrl.contains("audio_mpeg") || playUrl.contains("music")

                                    // LỌC THUẬT TOÁN: Ưu tiên tuyệt đối video Việt Nam
                                    if (isVietnameseContent(item, title, author, soundTitle)) {
                                        seenVideoIds.add(id)

                                        val diggCount = formatCount(item.optInt("digg_count", 15200))
                                        val commentCount = formatCount(item.optInt("comment_count", 890))
                                        val shareCount = formatCount(item.optInt("share_count", 4500))

                                        val tiktokItem = TikTokItem(
                                            id = id,
                                            videoUrl = playUrl,
                                            title = if (title.isNotEmpty()) title else "Bản nhạc thịnh hành TikTok Việt Nam",
                                            author = if (author.startsWith("@")) author else "@$author",
                                            authorUniqueId = uniqueId,
                                            authorAvatar = avatar,
                                            soundTitle = soundTitle,
                                            coverUrl = cover,
                                            likeCount = diggCount,
                                            commentCount = commentCount,
                                            shareCount = shareCount,
                                            isPhotoPost = isPhoto,
                                            images = imagesList
                                        )

                                        collectedVnItems.add(tiktokItem)
                                        allLoadedVideos.add(tiktokItem)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Nếu mạng chậm hoặc ít video mới, sắp xếp lại theo điểm đề xuất sở thích của người dùng
            val rankedList = rankVideosByRecommendation(collectedVnItems)
            onResult(rankedList.ifEmpty { allLoadedVideos.take(20) })
        }
    }

    /**
     * Thuật toán tìm kiếm thông minh đa nguồn chuẩn TikTok:
     * - Tìm kiếm theo ca sĩ, bài hát, lời, hashtag trực tiếp từ máy chủ TikTok
     * - Tự động truy vấn Challenge/Hashtag API của TikTok theo từ khóa (Ví dụ: "nói buông là buông")
     * - Trích xuất chính xác danh sách video tương ứng, không đưa video rác/lạc đề
     */
    fun searchVideos(
        query: String,
        onResult: (List<TikTokItem>) -> Unit
    ) {
        val qNorm = normalizeVietnamese(query)
        if (qNorm.isEmpty()) {
            onResult(allLoadedVideos.toList())
            return
        }

        executor.execute {
            val searchResults = mutableListOf<TikTokItem>()
            val seenIds = mutableSetOf<String>()

            // 1. Tìm kiếm hashtag/thử thách trên TikWM Challenge Search
            val challengeIds = mutableListOf<String>()
            try {
                val encodedQuery = URLEncoder.encode(qNorm, "UTF-8")
                val searchApiUrl = "https://www.tikwm.com/api/challenge/search?keywords=$encodedQuery"
                val conn = (URL(searchApiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; V2318A) Mobile Safari/537.36")
                    setRequestProperty("Referer", "https://www.tikwm.com/")
                    setRequestProperty("Accept", "application/json")
                }
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                if (root.optInt("code") == 0) {
                    val dataObj = root.optJSONObject("data")
                    val challengeList = dataObj?.optJSONArray("challenge_list")
                    if (challengeList != null) {
                        for (i in 0 until challengeList.length().coerceAtMost(3)) {
                            val cha = challengeList.getJSONObject(i)
                            val id = cha.optString("id")
                            if (id.isNotEmpty()) {
                                challengeIds.add(id)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Dự phòng: Nếu chưa tìm thấy challenge_id từ search, thử với challenge_info bằng tag viết liền
            if (challengeIds.isEmpty()) {
                val cleanTag = qNorm.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
                if (cleanTag.isNotEmpty()) {
                    try {
                        val infoUrl = "https://www.tikwm.com/api/challenge/info?challenge_name=$cleanTag"
                        val conn = (URL(infoUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4000
                            readTimeout = 4000
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; V2318A) Mobile Safari/537.36")
                            setRequestProperty("Referer", "https://www.tikwm.com/")
                            setRequestProperty("Accept", "application/json")
                        }
                        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                        val root = JSONObject(jsonStr)
                        if (root.optInt("code") == 0) {
                            val dataObj = root.optJSONObject("data")
                            val id = dataObj?.optString("id") ?: ""
                            if (id.isNotEmpty()) {
                                challengeIds.add(id)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // 3. Nạp danh sách video từ các challenge_id tìm được (Trả về các video chuẩn xác nhất)
            for (chaId in challengeIds) {
                try {
                    val postsUrl = "https://www.tikwm.com/api/challenge/posts?challenge_id=$chaId&count=30"
                    val conn = (URL(postsUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6000
                        readTimeout = 6000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; V2318A) Mobile Safari/537.36")
                        setRequestProperty("Referer", "https://www.tikwm.com/")
                        setRequestProperty("Accept", "application/json")
                    }
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(jsonStr)
                    if (root.optInt("code") == 0) {
                        val dataObj = root.optJSONObject("data")
                        val videosArr = dataObj?.optJSONArray("videos")
                        if (videosArr != null) {
                            for (i in 0 until videosArr.length()) {
                                val videoObj = videosArr.getJSONObject(i)
                                val item = parseItemJsonObject(videoObj)
                                if (item != null && !seenIds.contains(item.id)) {
                                    seenIds.add(item.id)
                                    searchResults.add(item)
                                    allLoadedVideos.add(item)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 4. Kết hợp với các video trong bộ nhớ khớp từ khóa tìm kiếm (theo tên bài, tác giả, lời bài hát)
            val queryWords = qNorm.split(" ").filter { it.length >= 2 }
            val localMatches = allLoadedVideos.filter { item ->
                if (seenIds.contains(item.id)) return@filter false
                val titleNorm = normalizeVietnamese(item.title)
                val soundNorm = normalizeVietnamese(item.soundTitle)
                val authorNorm = normalizeVietnamese(item.author)
                val authorIdNorm = normalizeVietnamese(item.authorUniqueId)

                titleNorm.contains(qNorm) || soundNorm.contains(qNorm) ||
                    authorNorm.contains(qNorm) || authorIdNorm.contains(qNorm) ||
                    queryWords.any { titleNorm.contains(it) || soundNorm.contains(it) }
            }
            for (item in localMatches) {
                if (!seenIds.contains(item.id)) {
                    seenIds.add(item.id)
                    searchResults.add(item)
                }
            }

            onResult(searchResults)
        }
    }

    private fun parseItemJsonObject(item: JSONObject): TikTokItem? {
        val playUrl = item.optString("play").ifEmpty { item.optString("wmplay") }
        val id = item.optString("video_id", "vid_${System.currentTimeMillis()}")
        if (playUrl.isBlank()) return null

        val title = item.optString("title", "").trim()
        val authorObj = item.optJSONObject("author")
        val author = authorObj?.optString("nickname") ?: "@tiktok"
        val uniqueId = authorObj?.optString("unique_id") ?: ""
        val avatar = authorObj?.optString("avatar") ?: ""

        val musicObj = item.optJSONObject("music_info")
        val soundTitle = musicObj?.optString("title") ?: "♫ Âm thanh gốc"
        var cover = item.optString("cover").ifEmpty { item.optString("origin_cover") }

        val imagesArray = item.optJSONArray("images")
        val imagesList = mutableListOf<String>()
        if (imagesArray != null) {
            for (j in 0 until imagesArray.length()) {
                val img = imagesArray.optString(j)
                if (img.isNotBlank()) imagesList.add(img)
            }
        }
        if (cover.isBlank() && imagesList.isNotEmpty()) {
            cover = imagesList[0]
        }
        val isPhoto = imagesList.isNotEmpty() || playUrl.contains("audio_mpeg") || playUrl.contains("music")

        val diggCount = formatCount(item.optInt("digg_count", 15200))
        val commentCount = formatCount(item.optInt("comment_count", 890))
        val shareCount = formatCount(item.optInt("share_count", 4500))

        return TikTokItem(
            id = id,
            videoUrl = playUrl,
            title = if (title.isNotEmpty()) title else "Bản nhạc thịnh hành TikTok Việt Nam",
            author = if (author.startsWith("@")) author else "@$author",
            authorUniqueId = uniqueId,
            authorAvatar = avatar,
            soundTitle = soundTitle,
            coverUrl = cover,
            likeCount = diggCount,
            commentCount = commentCount,
            shareCount = shareCount,
            isPhotoPost = isPhoto,
            images = imagesList
        )
    }

    /**
     * Ghi nhận tương tác xem video để huấn luyện thuật toán đề xuất
     */
    fun recordInteraction(item: TikTokItem, watchedRatio: Float, isLiked: Boolean, isSaved: Boolean) {
        val normTitle = normalizeVietnamese(item.title)
        val delta = when {
            isLiked || isSaved -> 2.0f
            watchedRatio > 0.7f -> 1.0f
            watchedRatio < 0.2f -> -0.5f
            else -> 0.0f
        }

        val tags = listOf("chill", "remix", "tamtrang", "vpop", "dance", "review", "xuhuong")
        for (tag in tags) {
            if (normTitle.contains(tag)) {
                val current = userTagAffinities[tag] ?: 1.0f
                userTagAffinities[tag] = (current + delta).coerceIn(0.1f, 10.0f)
            }
        }
    }

    private fun rankVideosByRecommendation(items: List<TikTokItem>): List<TikTokItem> {
        return items.sortedByDescending { item ->
            var score = 1.0f
            val normTitle = normalizeVietnamese(item.title)
            for ((tag, affinity) in userTagAffinities) {
                if (normTitle.contains(tag)) {
                    score += affinity * 2.0f
                }
            }
            score
        }
    }

    private fun isVietnameseContent(item: JSONObject, title: String, author: String, sound: String): Boolean {
        val region = item.optString("region")
        if (region.equals("VN", ignoreCase = true)) return true

        val combined = "$title $author $sound"
        // Kiểm tra nguyên âm có dấu tiếng Việt
        val vnRegex = Regex("[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ]")
        if (vnRegex.containsMatchIn(combined)) return true

        // Kiểm tra từ khóa tiếng Việt không dấu phổ biến
        val lower = combined.lowercase()
        val vnKeywords = listOf(
            "nhac", "chill", "remix", "vietnam", "vpop", "tam trang",
            "xuhuong", "ancungtiktok", "review", "bien hinh", "thoi trang", "haivl"
        )
        return vnKeywords.any { lower.contains(it) }
    }

    fun getVideosByAuthor(authorUniqueId: String, authorNickname: String): List<TikTokItem> {
        val direct = allLoadedVideos.filter {
            (authorUniqueId.isNotEmpty() && it.authorUniqueId.equals(authorUniqueId, ignoreCase = true)) ||
            it.author.equals(authorNickname, ignoreCase = true) ||
            it.author.equals("@$authorNickname", ignoreCase = true)
        }

        if (direct.size >= 6) {
            return direct
        }

        // Bổ sung các video hấp dẫn khác để lưới 3 cột của Profile luôn đầy đặn (6-12 video)
        val result = direct.toMutableList()
        val extra = allLoadedVideos.filter { !result.contains(it) }.take(9 - result.size)
        result.addAll(extra)
        return result
    }

    fun normalizeVietnamese(str: String): String {
        val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace('đ', 'd').replace('Đ', 'D')
            .lowercase().trim()
    }

    private fun formatCount(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    fun loadOfflineVideos(context: Context): List<TikTokItem> {
        val result = mutableListOf<TikTokItem>()
        try {
            val jsonStr = context.assets.open("offline_videos/manifest.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val assetPath = obj.optString("assetPath")
                val item = TikTokItem(
                    id = obj.optString("id"),
                    videoUrl = "asset:///$assetPath",
                    title = obj.optString("title"),
                    author = "@${obj.optString("author")}",
                    authorUniqueId = obj.optString("unique_id"),
                    authorAvatar = obj.optString("avatar"),
                    soundTitle = "♫ Bản nhạc xu hướng TikTok Việt Nam",
                    coverUrl = obj.optString("cover"),
                    likeCount = formatCount(obj.optInt("digg", 50000)),
                    commentCount = formatCount(obj.optInt("comment", 1200)),
                    shareCount = formatCount(obj.optInt("share", 8500)),
                    isPhotoPost = false,
                    images = emptyList()
                )
                result.add(item)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    /**
     * Nạp danh sách bình luận thực tế chuẩn TikTok từ máy chủ
     */
    fun fetchComments(
        videoUrl: String,
        onResult: (List<TikTokComment>) -> Unit
    ) {
        executor.execute {
            val commentsList = mutableListOf<TikTokComment>()
            try {
                val encodedUrl = URLEncoder.encode(videoUrl, "UTF-8")
                val apiUrl = "https://www.tikwm.com/api/comment/list?url=$encodedUrl&count=30"
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; V2318A) Mobile Safari/537.36")
                    setRequestProperty("Referer", "https://www.tikwm.com/")
                    setRequestProperty("Accept", "application/json")
                }
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                if (root.optInt("code") == 0) {
                    val dataObj = root.optJSONObject("data")
                    val commentsArr = dataObj?.optJSONArray("comments")
                    if (commentsArr != null) {
                        for (i in 0 until commentsArr.length()) {
                            val cObj = commentsArr.getJSONObject(i)
                            val userObj = cObj.optJSONObject("user")
                            val text = cObj.optString("text")
                            val author = userObj?.optString("nickname") ?: "Người dùng TikTok"
                            val avatar = userObj?.optString("avatar") ?: ""
                            val diggCount = formatCount(cObj.optInt("digg_count", 0))
                            val createTime = cObj.optLong("create_time", 0L)
                            val timeAgo = formatTimeAgo(createTime)

                            commentsList.add(
                                TikTokComment(
                                    id = cObj.optString("id", "c_$i"),
                                    text = text,
                                    author = author,
                                    authorAvatar = avatar,
                                    timeAgo = timeAgo,
                                    likeCount = diggCount
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            onResult(commentsList)
        }
    }

    private fun formatTimeAgo(timestampSeconds: Long): String {
        if (timestampSeconds <= 0L) return "Vừa xong"
        val diffSec = (System.currentTimeMillis() / 1000) - timestampSeconds
        return when {
            diffSec < 60 -> "Vừa xong"
            diffSec < 3600 -> "${diffSec / 60} phút trước"
            diffSec < 86400 -> "${diffSec / 3600} giờ trước"
            diffSec < 2592000 -> "${diffSec / 86400} ngày trước"
            else -> "${diffSec / 2592000} tháng trước"
        }
    }
}
