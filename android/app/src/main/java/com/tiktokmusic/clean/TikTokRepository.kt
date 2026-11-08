package com.tiktokmusic.clean

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

class TikTokRepository {

    private val executor = Executors.newFixedThreadPool(2)
    private val seenVideoIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val allLoadedVideos = Collections.synchronizedList(mutableListOf<TikTokItem>())

    // Danh sách các vùng hỗ trợ nạp video phong phú
    val availableRegions = listOf("VN", "US", "KR", "JP", "TH", "ID")

    /**
     * Nạp batch video thịnh hành từ TikWM API
     * Mỗi lần gọi trả về 10-20 video mới nhất, tự động lọc trùng lặp
     */
    fun fetchOnlineTrending(
        region: String = "VN",
        count: Int = 20,
        onResult: (List<TikTokItem>) -> Unit
    ) {
        executor.execute {
            val list = mutableListOf<TikTokItem>()
            try {
                val apiUrl = "https://www.tikwm.com/api/feed/list?region=$region&count=$count"
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; V2318A) Mobile Safari/537.36")
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
                                seenVideoIds.add(id)

                                val title = item.optString("title", "TikTok Video").trim()
                                val authorObj = item.optJSONObject("author")
                                val author = authorObj?.optString("nickname") ?: "@tiktok"
                                val uniqueId = authorObj?.optString("unique_id") ?: ""
                                val avatar = authorObj?.optString("avatar") ?: ""

                                val musicObj = item.optJSONObject("music_info")
                                val soundTitle = musicObj?.optString("title") ?: "♫ Âm thanh gốc"
                                val cover = item.optString("cover")

                                val diggCount = formatCount(item.optInt("digg_count", 15200))
                                val commentCount = formatCount(item.optInt("comment_count", 890))
                                val shareCount = formatCount(item.optInt("share_count", 4500))

                                val tiktokItem = TikTokItem(
                                    id = id,
                                    videoUrl = playUrl,
                                    title = if (title.isNotEmpty()) title else "Bản nhạc thịnh hành TikTok",
                                    author = if (author.startsWith("@")) author else "@$author",
                                    authorUniqueId = uniqueId,
                                    authorAvatar = avatar,
                                    soundTitle = soundTitle,
                                    coverUrl = cover,
                                    likeCount = diggCount,
                                    commentCount = commentCount,
                                    shareCount = shareCount
                                )
                                list.add(tiktokItem)
                                allLoadedVideos.add(tiktokItem)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            onResult(list)
        }
    }

    /**
     * Tìm kiếm video theo từ khóa trong kho video đã tải và nạp thêm từ nhiều vùng
     */
    fun searchVideos(
        query: String,
        onResult: (List<TikTokItem>) -> Unit
    ) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            onResult(allLoadedVideos.toList())
            return
        }

        // Lọc trong kho hiện tại trước
        val localMatches = allLoadedVideos.filter {
            it.title.lowercase().contains(q) ||
            it.author.lowercase().contains(q) ||
            it.soundTitle.lowercase().contains(q) ||
            it.authorUniqueId.lowercase().contains(q)
        }.toMutableList()

        if (localMatches.size >= 5) {
            onResult(localMatches)
            return
        }

        // Nếu kho chưa đủ kết quả, thử tải thêm từ khu vực US/VN và lọc
        fetchOnlineTrending(region = "US", count = 20) { newItems ->
            val additionalMatches = newItems.filter {
                it.title.lowercase().contains(q) ||
                it.author.lowercase().contains(q) ||
                it.soundTitle.lowercase().contains(q)
            }
            localMatches.addAll(additionalMatches)
            onResult(localMatches)
        }
    }

    /**
     * Lấy danh sách video của một tác giả cụ thể
     */
    fun getVideosByAuthor(authorUniqueId: String, authorNickname: String): List<TikTokItem> {
        return allLoadedVideos.filter {
            (authorUniqueId.isNotEmpty() && it.authorUniqueId.equals(authorUniqueId, ignoreCase = true)) ||
            it.author.equals(authorNickname, ignoreCase = true) ||
            it.author.equals("@$authorNickname", ignoreCase = true)
        }
    }

    private fun formatCount(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
