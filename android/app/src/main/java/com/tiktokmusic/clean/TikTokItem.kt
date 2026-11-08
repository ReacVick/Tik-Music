package com.tiktokmusic.clean

data class TikTokItem(
    val id: String,
    val title: String,
    val authorUsername: String,
    val authorNickname: String,
    val authorAvatar: String,
    val videoUrl: String,
    val coverUrl: String,
    var likesCount: String = "128.5K",
    var savesCount: String = "18.2K",
    var sharesCount: String = "4.6K",
    val musicTitle: String = "Bản nhạc gốc · TikTok Music Clean",
    val durationSec: Int = 30,
    var isLiked: Boolean = false,
    var isSaved: Boolean = false
)