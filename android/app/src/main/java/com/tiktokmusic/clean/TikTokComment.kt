package com.tiktokmusic.clean

data class TikTokComment(
    val id: String,
    val text: String,
    val author: String,
    val authorAvatar: String,
    val timeAgo: String,
    var likeCount: String,
    var isLiked: Boolean = false
)