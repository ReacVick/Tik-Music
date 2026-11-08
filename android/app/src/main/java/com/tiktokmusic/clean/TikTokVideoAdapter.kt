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
    private val onVideoClick: (position: Int, isPlaying: Boolean) -> Unit
) : RecyclerView.Adapter<TikTokVideoAdapter.VideoViewHolder>() {

    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val ivCenterPlay: ImageView = itemView.findViewById(R.id.ivCenterPlay)

        val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        val btnLike: View = itemView.findViewById(R.id.btnLike)
        val ivLikeHeart: ImageView = itemView.findViewById(R.id.ivLikeHeart)
        val tvLikeCount: TextView = itemView.findViewById(R.id.tvLikeCount)

        val btnSave: View = itemView.findViewById(R.id.btnSave)
        val ivSaveIcon: ImageView = itemView.findViewById(R.id.ivSaveIcon)
        val tvSaveCount: TextView = itemView.findViewById(R.id.tvSaveCount)

        val tvShareCount: TextView = itemView.findViewById(R.id.tvShareCount)

        val discContainer: FrameLayout = itemView.findViewById(R.id.discContainer)
        val ivDiscCover: ImageView = itemView.findViewById(R.id.ivDiscCover)

        val tvAuthorName: TextView = itemView.findViewById(R.id.tvAuthorName)
        val tvCaption: TextView = itemView.findViewById(R.id.tvCaption)
        val tvMusicTitle: TextView = itemView.findViewById(R.id.tvMusicTitle)
        val pbTimeline: ProgressBar = itemView.findViewById(R.id.pbTimeline)

        var discAnimator: ObjectAnimator? = null
        var isLiked: Boolean = false
        var isSaved: Boolean = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tiktok_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = items[position]

        holder.tvAuthorName.text = item.author
        holder.tvCaption.text = item.title
        holder.tvMusicTitle.text = item.soundTitle
        holder.tvMusicTitle.isSelected = true

        holder.tvLikeCount.text = item.likeCount
        holder.tvShareCount.text = item.shareCount
        holder.tvSaveCount.text = item.commentCount

        holder.ivCenterPlay.visibility = View.GONE
        holder.ivThumbnail.visibility = View.VISIBLE

        ImageLoader.load(item.coverUrl, holder.ivThumbnail, false)
        ImageLoader.load(item.authorAvatar, holder.ivAvatar, true)
        ImageLoader.load(item.coverUrl, holder.ivDiscCover, true)

        // Hoạt họa đĩa than xoay 360 độ
        holder.discAnimator?.cancel()
        holder.discAnimator = ObjectAnimator.ofFloat(holder.discContainer, "rotation", 0f, 360f).apply {
            duration = 7000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Tương tác thả tim
        holder.btnLike.setOnClickListener {
            holder.isLiked = !holder.isLiked
            if (holder.isLiked) {
                holder.ivLikeHeart.setColorFilter(Color.parseColor("#FE2C55"))
                holder.ivLikeHeart.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction {
                    holder.ivLikeHeart.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }.start()
            } else {
                holder.ivLikeHeart.clearColorFilter()
            }
        }

        // Tương tác lưu bookmark
        holder.btnSave.setOnClickListener {
            holder.isSaved = !holder.isSaved
            if (holder.isSaved) {
                holder.ivSaveIcon.setColorFilter(Color.parseColor("#FACE15"))
            } else {
                holder.ivSaveIcon.clearColorFilter()
            }
        }

        // Chạm vào màn hình để Tạm dừng / Phát tiếp
        holder.itemView.setOnClickListener {
            val isCurrentlyPlaying = holder.ivCenterPlay.visibility != View.VISIBLE
            if (isCurrentlyPlaying) {
                holder.ivCenterPlay.visibility = View.VISIBLE
                holder.discAnimator?.pause()
                onVideoClick(position, false)
            } else {
                holder.ivCenterPlay.visibility = View.GONE
                holder.discAnimator?.resume()
                onVideoClick(position, true)
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
