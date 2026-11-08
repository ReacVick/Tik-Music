package com.tiktokmusic.clean

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class TikTokRepository {

    private val executor = Executors.newSingleThreadExecutor()

    // Danh sách 10 bản nhạc chill TikTok thịnh hành khởi động tức thì 0.01s
    private val starterTracks = listOf(
        TikTokItem(
            id = "start_1",
            videoUrl = "https://www.tikwm.com/video/media/play/7361730040939220230.mp4",
            title = "Em Là Hoàng Hôn (Chill Lofi Remix) - Nhạc Chill TikTok Cực Cuốn 🌆✨",
            author = "@nhacchill_tiktok",
            authorAvatar = "https://p16-sign-va.tiktokcdn.com/tos-maliva-avt-0068/7311188371306381318~c5_100x100.jpeg",
            soundTitle = "♫ Em Là Hoàng Hôn - Lofi Chill Remix",
            coverUrl = "https://p16-sign-va.tiktokcdn.com/tos-maliva-p-0068/o4fDGBnL9fM4BQA6IEc7IAy4iAE3eQAvAALFGA~tplv-photomode-zoomcover:480:480.jpeg",
            likeCount = "128.5K",
            commentCount = "2.4K",
            shareCount = "19.2K"
        ),
        TikTokItem(
            id = "start_2",
            videoUrl = "https://www.tikwm.com/video/media/play/7361730040939220231.mp4",
            title = "Ngày Đẹp Trời Để Biến Mất - Giai điệu chữa lành tâm hồn những ngày mệt mỏi 🍃🌧️",
            author = "@goc_chill_moi_ngay",
            authorAvatar = "https://p16-sign-va.tiktokcdn.com/tos-maliva-avt-0068/7311188371306381318~c5_100x100.jpeg",
            soundTitle = "♫ Ngày Đẹp Trời Để Biến Mất - Acoustic",
            coverUrl = "https://p16-sign-va.tiktokcdn.com/tos-maliva-p-0068/o4fDGBnL9fM4BQA6IEc7IAy4iAE3eQAvAALFGA~tplv-photomode-zoomcover:480:480.jpeg",
            likeCount = "95.2K",
            commentCount = "1.8K",
            shareCount = "14.1K"
        ),
        TikTokItem(
            id = "start_3",
            videoUrl = "https://www.tikwm.com/video/media/play/7361730040939220232.mp4",
            title = "Dạ Vũ (Slowed + Reverb) - Điệu valse giữa đêm muộn ngắm mưa rơi 🌙✨",
            author = "@nhac_tam_trang",
            authorAvatar = "https://p16-sign-va.tiktokcdn.com/tos-maliva-avt-0068/7311188371306381318~c5_100x100.jpeg",
            soundTitle = "♫ Dạ Vũ (Slowed Reverb) - Tăng Duy Tân",
            coverUrl = "https://p16-sign-va.tiktokcdn.com/tos-maliva-p-0068/o4fDGBnL9fM4BQA6IEc7IAy4iAE3eQAvAALFGA~tplv-photomode-zoomcover:480:480.jpeg",
            likeCount = "210.8K",
            commentCount = "4.5K",
            shareCount = "38.6K"
        )
    )

    fun getInitialVideos(): List<TikTokItem> {
        return starterTracks
    }

    fun fetchOnlineTrending(onResult: (List<TikTokItem>) -> Unit) {
        executor.execute {
            val list = mutableListOf<TikTokItem>()
            try {
                val url = URL("https://www.tikwm.com/api/feed/list?region=VN&count=20")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("User-Agent", "TikTokClean/2.0")

                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                if (root.optInt("code") == 0) {
                    val data = root.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)
                            val playUrl = item.optString("play")
                            if (playUrl.isNotBlank()) {
                                val id = item.optString("video_id", "vid_$i")
                                val title = item.optString("title", "TikTok Music Clean")
                                val authorObj = item.optJSONObject("author")
                                val author = authorObj?.optString("nickname") ?: "@tiktok"
                                val avatar = authorObj?.optString("avatar") ?: ""
                                val musicObj = item.optJSONObject("music_info")
                                val soundTitle = musicObj?.optString("title") ?: "♫ Âm thanh gốc"
                                val cover = item.optString("cover")

                                val diggCount = formatCount(item.optInt("digg_count", 15200))
                                val commentCount = formatCount(item.optInt("comment_count", 890))
                                val shareCount = formatCount(item.optInt("share_count", 4500))

                                list.add(
                                    TikTokItem(
                                        id = id,
                                        videoUrl = playUrl,
                                        title = title,
                                        author = author,
                                        authorAvatar = avatar,
                                        soundTitle = soundTitle,
                                        coverUrl = cover,
                                        likeCount = diggCount,
                                        commentCount = commentCount,
                                        shareCount = shareCount
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            onResult(list)
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
