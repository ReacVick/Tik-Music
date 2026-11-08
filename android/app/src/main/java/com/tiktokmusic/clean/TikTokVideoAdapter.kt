package com.tiktokmusic.clean

import android.animation.ObjectAnimator
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView

class TikTokVideoAdapter(
    private val items: MutableList<TikTokItem>,
    private val onVideoClick: (Int, TikTokItem) -> Unit
) : RecyclerView.Adapter<TikTokVideoAdapter.VideoViewHolder>() {

    class VideoViewHolder(val root: View) : RecyclerView.ViewHolder(root) {
        val playerView: PlayerView = root.findViewById(R.id.playerView)
        val ivThumbnail: ImageView = root.findViewById(R.id.ivThumbnail)
        val ivCenterPlay: ImageView = root.findViewById(R.id.ivCenterPlay)

        val btnAuthorAvatar: FrameLayout = root.findViewById(R.id.btnAuthorAvatar)
        val ivAvatar: ImageView = root.findViewById(R.id.ivAvatar)
        val tvFollowPlus: TextView = root.findViewById(R.id.tvFollowPlus)

        val btnLike: View = root.findViewById(R.id.btnLike)
        val ivLikeHeart: ImageView = root.findViewById(R.id.ivLikeHeart)
        val tvLikeCount: TextView = root.findViewById(R.id.tvLikeCount)

        val btnSave: View = root.findViewById(R.id.btnSave)
        val ivSaveIcon: ImageView = root.findViewById(R.id.ivSaveIcon)
        val tvSaveCount: TextView = root.findViewById(R.id.tvSaveCount)

        val btnShare: View = root.findViewById(R.id.btnShare)
        val tvShareCount: TextView = root.findViewById(R.id.tvShareCount)

        val discContainer: FrameLayout = root.findViewById(R.id.discContainer)
        val ivVinylDisc: ImageView = root.findViewById(R.id.ivVinylDisc)
        val ivDiscCover: ImageView = root.findViewById(R.id.ivDiscCover)

        val tvAuthorName: TextView = root.findViewById(R.id.tvAuthorName)
        val tvCaption: TextView = root.findViewById(R.id.tvCaption)
        val tvMusicTitle: TextView = root.findViewById(R.id.tvMusicTitle)
        val pbTimeline: ProgressBar = root.findViewById(R.id.pbTimeline)

        var discAnimator: ObjectAnimator? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tiktok_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = items[position]

        holder.tvAuthorName.text = if (item.authorUsername.startsWith("@")) item.authorUsername else "@${item.authorUsername}"
        holder.tvCaption.text = item.title
        holder.tvMusicTitle.text = item.musicTitle
        holder.tvLikeCount.text = item.likesCount
        holder.tvSaveCount.text = item.savesCount
        holder.tvShareCount.text = item.sharesCount

        // Load thumbnail và avatar
        ImageLoader.load(item.coverUrl, holder.ivThumbnail, false)
        ImageLoader.load(item.authorAvatar, holder.ivAvatar, true)
        ImageLoader.load(item.coverUrl, holder.ivDiscCover, true)

        // Đĩa than xoay tròn mượt mà
        holder.discAnimator?.cancel()
        holder.discAnimator = ObjectAnimator.ofFloat(holder.ivVinylDisc, "rotation", 0f, 360f).apply {
            duration = 3200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Tương tác Thả tim
        holder.btnLike.setOnClickListener {
            item.isLiked = !item.isLiked
            if (item.isLiked) {
                holder.ivLikeHeart.setColorFilter(Color.parseColor("#FE2C55"))
            } else {
                holder.ivLikeHeart.clearColorFilter()
            }
        }

        // Tương tác Lưu
        holder.btnSave.setOnClickListener {
            item.isSaved = !item.isSaved
            if (item.isSaved) {
                holder.ivSaveIcon.setColorFilter(Color.parseColor("#FACB2A"))
            } else {
                holder.ivSaveIcon.clearColorFilter()
            }
        }

        // Ẩn dấu cộng khi bấm avatar
        holder.btnAuthorAvatar.setOnClickListener {
            holder.tvFollowPlus.visibility = View.GONE
        }

        // Chạm vào màn hình để Play/Pause
        holder.root.setOnClickListener {
            onVideoClick(position, item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun addItems(newItems: List<TikTokItem>) {
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }
}