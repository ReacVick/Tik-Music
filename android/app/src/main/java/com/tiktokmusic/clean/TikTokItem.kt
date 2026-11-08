package com.tiktokmusic.clean

data class TikTokItem(
    val id: String,
    val videoUrl: String,
    val title: String,
    val author: String,
    val authorAvatar: String,
    val soundTitle: String,
    val coverUrl: String,
    val likeCount: String = "86.4K",
    val commentCount: String = "1.2K",
    val shareCount: String = "15.8K"
)
