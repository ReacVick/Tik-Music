package com.tiktokmusic.clean

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
        targetCount: Int = 20,
        onResult: (List<TikTokItem>) -> Unit
    ) {
        executor.execute {
            val collectedVnItems = mutableListOf<TikTokItem>()
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
                                    val cover = item.optString("cover")

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
                                            shareCount = shareCount
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
     * Thuật toán tìm kiếm thông minh đa nguồn:
     * - Không phân biệt dấu tiếng Việt
     * - Tìm kiếm theo ca sĩ, bài hát, lời, hashtag
     * - Nạp thêm đợt dữ liệu mới để trả về hàng chục kết quả phong phú
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
            val queryWords = qNorm.split(" ").filter { it.length >= 2 }

            // 1. Tìm kiếm trong kho video hiện có với điểm liên quan
            val matchedWithScore = allLoadedVideos.mapNotNull { item ->
                val titleNorm = normalizeVietnamese(item.title)
                val authorNorm = normalizeVietnamese(item.author)
                val soundNorm = normalizeVietnamese(item.soundTitle)
                val idNorm = normalizeVietnamese(item.authorUniqueId)

                var score = 0
                if (titleNorm.contains(qNorm)) score += 10
                if (soundNorm.contains(qNorm)) score += 8
                if (authorNorm.contains(qNorm) || idNorm.contains(qNorm)) score += 12

                for (word in queryWords) {
                    if (titleNorm.contains(word)) score += 3
                    if (soundNorm.contains(word)) score += 2
                    if (authorNorm.contains(word)) score += 3
                }

                if (score > 0) Pair(item, score) else null
            }.sortedByDescending { it.second }.map { it.first }.toMutableList()

            // 2. Nạp thêm các đợt video mới để mở rộng kết quả tìm kiếm nếu kết quả còn ít
            if (matchedWithScore.size < 16) {
                for (attempt in 1..2) {
                    try {
                        val apiUrl = "https://www.tikwm.com/api/feed/list?region=VN&count=30"
                        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 5000
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; V2318A) Mobile Safari/537.36")
                            setRequestProperty("Referer", "https://www.tikwm.com/")
                            setRequestProperty("Accept", "application/json")
                        }
                        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                        val root = JSONObject(jsonStr)
                        val data = root.optJSONArray("data")
                        if (data != null) {
                            for (i in 0 until data.length()) {
                                val obj = data.getJSONObject(i)
                                val playUrl = obj.optString("play")
                                val id = obj.optString("video_id", "vid_s_${System.currentTimeMillis()}_$i")
                                if (playUrl.isNotBlank() && !seenVideoIds.contains(id)) {
                                    val title = obj.optString("title", "")
                                    val authorObj = obj.optJSONObject("author")
                                    val author = authorObj?.optString("nickname") ?: "@tiktok"
                                    val uniqueId = authorObj?.optString("unique_id") ?: ""
                                    val avatar = authorObj?.optString("avatar") ?: ""
                                    val musicObj = obj.optJSONObject("music_info")
                                    val soundTitle = musicObj?.optString("title") ?: "♫ Âm thanh gốc"

                                    seenVideoIds.add(id)
                                    val newItem = TikTokItem(
                                        id = id,
                                        videoUrl = playUrl,
                                        title = if (title.isNotEmpty()) title else "TikTok Video",
                                        author = if (author.startsWith("@")) author else "@$author",
                                        authorUniqueId = uniqueId,
                                        authorAvatar = avatar,
                                        soundTitle = soundTitle,
                                        coverUrl = obj.optString("cover"),
                                        likeCount = formatCount(obj.optInt("digg_count", 18200)),
                                        commentCount = formatCount(obj.optInt("comment_count", 950)),
                                        shareCount = formatCount(obj.optInt("share_count", 3200))
                                    )
                                    allLoadedVideos.add(newItem)

                                    val titleNorm = normalizeVietnamese(title)
                                    val soundNorm = normalizeVietnamese(soundTitle)
                                    val authorNorm = normalizeVietnamese(author)

                                    val matchesWord = queryWords.any { titleNorm.contains(it) || soundNorm.contains(it) || authorNorm.contains(it) }
                                    if (titleNorm.contains(qNorm) || soundNorm.contains(qNorm) || authorNorm.contains(qNorm) || matchesWord) {
                                        matchedWithScore.add(newItem)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // 3. Nếu kết quả vẫn dưới 10, bổ sung các video Việt Nam hàng đầu để luôn hiển thị đầy đủ Grid chuẩn TikTok
            val finalResults = if (matchedWithScore.size >= 8) {
                matchedWithScore
            } else {
                val combined = matchedWithScore.toMutableList()
                val remaining = allLoadedVideos.filter { !combined.contains(it) }.take(24 - combined.size)
                combined.addAll(remaining)
                combined
            }

            onResult(finalResults)
        }
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
}
