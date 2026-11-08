package com.tiktokmusic.clean

import android.animation.ObjectAnimator
import android.graphics.Color
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView

class TikTokVideoAdapter(
    private val items: MutableList<TikTokItem>,
    private val onVideoClick: (position: Int, isPlaying: Boolean) -> Unit,
    private val onAuthorClick: (item: TikTokItem) -> Unit,
    private val onLongClick: (item: TikTokItem) -> Unit,
    private val onShareClick: (item: TikTokItem) -> Unit,
    private val onCommentClick: (item: TikTokItem) -> Unit,
    private val onDoubleTapLike: (item: TikTokItem) -> Unit
) : RecyclerView.Adapter<TikTokVideoAdapter.VideoViewHolder>() {

    var exoPlayer: ExoPlayer? = null
    var currentPlayingPosition: Int = -1

    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val heartAnimationContainer: FrameLayout = itemView.findViewById(R.id.heartAnimationContainer)
        val ivCenterPlay: ImageView = itemView.findViewById(R.id.ivCenterPlay)

        val btnAuthorAvatar: View = itemView.findViewById(R.id.btnAuthorAvatar)
        val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        val tvFollowPlus: TextView = itemView.findViewById(R.id.tvFollowPlus)

        val btnLike: View = itemView.findViewById(R.id.btnLike)
        val ivLikeHeart: ImageView = itemView.findViewById(R.id.ivLikeHeart)
        val tvLikeCount: TextView = itemView.findViewById(R.id.tvLikeCount)

        val btnComment: View = itemView.findViewById(R.id.btnComment)
        val tvCommentCount: TextView = itemView.findViewById(R.id.tvCommentCount)

        val btnSave: View = itemView.findViewById(R.id.btnSave)
        val ivSaveIcon: ImageView = itemView.findViewById(R.id.ivSaveIcon)
        val tvSaveCount: TextView = itemView.findViewById(R.id.tvSaveCount)

        val btnShare: View = itemView.findViewById(R.id.btnShare)
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
        var isFollowed: Boolean = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tiktok_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = items[position]

        // Gắn ExoPlayer ngay khi ViewHolder được bind nếu là vị trí đang phát
        if (position == currentPlayingPosition && exoPlayer != null) {
            holder.playerView.player = exoPlayer
        } else {
            if (holder.playerView.player != null) {
                holder.playerView.player = null
            }
        }

        holder.tvAuthorName.text = item.author
        holder.tvCaption.text = item.title
        holder.tvMusicTitle.text = item.soundTitle
        holder.tvMusicTitle.isSelected = true

        holder.tvLikeCount.text = item.likeCount
        holder.tvCommentCount.text = item.commentCount
        holder.tvSaveCount.text = item.commentCount
        holder.tvShareCount.text = item.shareCount

        holder.ivCenterPlay.visibility = View.GONE
        holder.ivThumbnail.visibility = View.VISIBLE

        ImageLoader.load(item.coverUrl, holder.ivThumbnail, false)
        ImageLoader.load(item.authorAvatar, holder.ivAvatar, true)
        ImageLoader.load(item.coverUrl, holder.ivDiscCover, true)

        // Hoạt họa đĩa than xoay tròn 360 độ
        holder.discAnimator?.cancel()
        holder.discAnimator = ObjectAnimator.ofFloat(holder.discContainer, "rotation", 0f, 360f).apply {
            duration = 7000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Tương tác bấm vào Avatar hoặc Tên Tác giả -> Mở xem thông tin kênh
        holder.btnAuthorAvatar.setOnClickListener {
            onAuthorClick(item)
        }
        holder.tvAuthorName.setOnClickListener {
            onAuthorClick(item)
        }

        // Tương tác nút Follow (+)
        holder.tvFollowPlus.setOnClickListener {
            holder.isFollowed = !holder.isFollowed
            if (holder.isFollowed) {
                holder.tvFollowPlus.visibility = View.GONE
                Toast.makeText(holder.itemView.context, "Đã theo dõi ${item.author}", Toast.LENGTH_SHORT).show()
            } else {
                holder.tvFollowPlus.visibility = View.VISIBLE
            }
        }

        // Tương tác thả tim (Like)
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

        // Tương tác bấm vào Comment: Mở khung bình luận thực tế chuẩn TikTok
        holder.btnComment.setOnClickListener {
            onCommentClick(item)
        }

        // Tương tác lưu bookmark
        holder.btnSave.setOnClickListener {
            holder.isSaved = !holder.isSaved
            if (holder.isSaved) {
                holder.ivSaveIcon.setColorFilter(Color.parseColor("#FACE15"))
                Toast.makeText(holder.itemView.context, "Đã thêm vào mục Yêu thích", Toast.LENGTH_SHORT).show()
            } else {
                holder.ivSaveIcon.clearColorFilter()
            }
        }

        // Tương tác chia sẻ
        holder.btnShare.setOnClickListener {
            onShareClick(item)
        }

        // Nhận diện cử chỉ chạm đơn (Play/Pause), chạm kép (Double Tap Thả tim), nhấn giữ (Menu)
        val gestureDetector = GestureDetector(holder.itemView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
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
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                showBurstHeartAnimation(holder, e.x, e.y)
                if (!holder.isLiked) {
                    holder.isLiked = true
                    holder.ivLikeHeart.setColorFilter(Color.parseColor("#FE2C55"))
                    holder.ivLikeHeart.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction {
                        holder.ivLikeHeart.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    }.start()
                }
                onDoubleTapLike(item)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onLongClick(item)
            }
        })

        holder.itemView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun showBurstHeartAnimation(holder: VideoViewHolder, x: Float, y: Float) {
        val context = holder.itemView.context
        val heart = ImageView(context).apply {
            setImageResource(R.drawable.ic_heart)
            setColorFilter(Color.parseColor("#FE2C55"))
            val size = (80 * context.resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (x - size / 2).toInt()
                topMargin = (y - size / 2).toInt()
            }
            rotation = (-20..20).random().toFloat()
            scaleX = 0.2f
            scaleY = 0.2f
            alpha = 0.95f
        }
        holder.heartAnimationContainer.addView(heart)

        heart.animate()
            .scaleX(1.25f)
            .scaleY(1.25f)
            .setDuration(180)
            .withEndAction {
                heart.animate()
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .alpha(0f)
                    .translationYBy(-90f)
                    .setDuration(350)
                    .withEndAction {
                        holder.heartAnimationContainer.removeView(heart)
                    }
                    .start()
            }
            .start()
    }

    override fun onViewAttachedToWindow(holder: VideoViewHolder) {
        super.onViewAttachedToWindow(holder)
        val pos = holder.bindingAdapterPosition
        if (pos == currentPlayingPosition && exoPlayer != null) {
            holder.playerView.player = exoPlayer
        }
    }

    override fun onViewDetachedFromWindow(holder: VideoViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val pos = holder.bindingAdapterPosition
        if (pos != currentPlayingPosition && holder.playerView.player != null) {
            holder.playerView.player = null
        }
    }

    override fun getItemCount(): Int = items.size
}
