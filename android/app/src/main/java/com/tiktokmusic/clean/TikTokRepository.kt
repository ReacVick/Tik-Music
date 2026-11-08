package com.tiktokmusic.clean

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Executors

class TikTokRepository(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val seenIds = Collections.synchronizedSet(HashSet<String>())

    fun loadInitialVideos(): List<TikTokItem> {
        val list = mutableListOf<TikTokItem>()
        try {
            val jsonStr = context.assets.open("feed_data.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val item = parseJsonItem(obj)
                if (item != null && item.videoUrl.isNotEmpty()) {
                    if (seenIds.add(item.id)) {
                        list.add(item)
                    }
                }
            }
        } catch (_: Exception) {}
        list.shuffle()
        return list
    }

    fun fetchOnlineBatchAsync(keyword: String = "nhacchill", onResult: (List<TikTokItem>) -> Unit) {
        executor.execute {
            val freshList = mutableListOf<TikTokItem>()
            try {
                val cleanKw = URLEncoder.encode(keyword, "UTF-8")
                val postData = "keywords=$cleanKw&count=35&cursor=0&HD=1"
                val url = URL("https://www.tikwm.com/api/feed/search/")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")

                val os = conn.outputStream
                os.write(postData.toByteArray())
                os.flush()
                os.close()

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    reader.close()

                    val root = JSONObject(sb.toString())
                    val data = root.optJSONObject("data")
                    val videos = data?.optJSONArray("videos")
                    if (videos != null) {
                        for (i in 0 until videos.length()) {
                            val vObj = videos.getJSONObject(i)
                            val item = parseTikWMItem(vObj)
                            if (item != null && item.videoUrl.isNotEmpty()) {
                                if (seenIds.add(item.id)) {
                                    freshList.add(item)
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
            onResult(freshList)
        }
    }

    private fun parseJsonItem(obj: JSONObject): TikTokItem? {
        val id = obj.optString("id", "")
        if (id.isEmpty()) return null
        val desc = obj.optString("desc", "")
        val duration = obj.optInt("duration", 30)
        val cover = obj.optString("cover", "")
        val streamUrl = obj.optString("streamVideoUrl", "")

        val authorObj = obj.optJSONObject("author")
        val username = authorObj?.optString("username", "tiktok_user") ?: "tiktok_user"
        val nickname = authorObj?.optString("nickname", username) ?: username
        val avatar = authorObj?.optString("avatar", "") ?: ""

        val musicObj = obj.optJSONObject("music")
        val musicTitle = musicObj?.optString("title", "Bản nhạc gốc - $nickname") ?: "Bản nhạc gốc - $nickname"

        val statsObj = obj.optJSONObject("stats")
        val digg = statsObj?.optInt("diggCount", 12000) ?: 12000
        val collect = statsObj?.optInt("collectCount", 3400) ?: 3400
        val share = statsObj?.optInt("shareCount", 890) ?: 890

        return TikTokItem(
            id = id,
            title = desc,
            authorUsername = username,
            authorNickname = nickname,
            authorAvatar = avatar,
            videoUrl = streamUrl,
            coverUrl = cover,
            likesCount = formatCount(digg),
            savesCount = formatCount(collect),
            sharesCount = formatCount(share),
            musicTitle = musicTitle,
            durationSec = duration
        )
    }

    private fun parseTikWMItem(obj: JSONObject): TikTokItem? {
        val id = obj.optString("id", obj.optString("video_id", ""))
        if (id.isEmpty()) return null
        val title = obj.optString("title", "")
        val duration = obj.optInt("duration", 30)
        val cover = obj.optString("cover", "")
        val play = obj.optString("play", obj.optString("hdplay", ""))

        val authorObj = obj.optJSONObject("author")
        val username = authorObj?.optString("unique_id", "creator") ?: "creator"
        val nickname = authorObj?.optString("nickname", username) ?: username
        val avatar = authorObj?.optString("avatar", "") ?: ""

        val musicInfo = obj.optJSONObject("music_info")
        val musicTitle = musicInfo?.optString("title", "Bản nhạc gốc - $nickname") ?: "Bản nhạc gốc - $nickname"

        val digg = obj.optInt("digg_count", 9800)
        val collect = obj.optInt("collect_count", 2100)
        val share = obj.optInt("share_count", 540)

        return TikTokItem(
            id = id,
            title = title,
            authorUsername = username,
            authorNickname = nickname,
            authorAvatar = avatar,
            videoUrl = play,
            coverUrl = cover,
            likesCount = formatCount(digg),
            savesCount = formatCount(collect),
            sharesCount = formatCount(share),
            musicTitle = musicTitle,
            durationSec = duration
        )
    }

    private fun formatCount(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}