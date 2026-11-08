package com.tiktokmusic.clean

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TikTokCommentAdapter(
    private val comments: MutableList<TikTokComment>
) : RecyclerView.Adapter<TikTokCommentAdapter.CommentViewHolder>() {

    class CommentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAvatar: ImageView = itemView.findViewById(R.id.ivCommentAvatar)
        val tvAuthor: TextView = itemView.findViewById(R.id.tvCommentAuthor)
        val tvText: TextView = itemView.findViewById(R.id.tvCommentText)
        val tvTime: TextView = itemView.findViewById(R.id.tvCommentTime)
        val btnLike: View = itemView.findViewById(R.id.btnLikeComment)
        val ivHeart: ImageView = itemView.findViewById(R.id.ivCommentHeart)
        val tvLikeCount: TextView = itemView.findViewById(R.id.tvCommentLikeCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tiktok_comment, parent, false)
        return CommentViewHolder(view)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        val item = comments[position]

        holder.tvAuthor.text = item.author
        holder.tvText.text = item.text
        holder.tvTime.text = item.timeAgo
        holder.tvLikeCount.text = item.likeCount

        ImageLoader.load(item.authorAvatar, holder.ivAvatar, true)

        if (item.isLiked) {
            holder.ivHeart.setColorFilter(Color.parseColor("#FE2C55"))
        } else {
            holder.ivHeart.clearColorFilter()
        }

        holder.btnLike.setOnClickListener {
            item.isLiked = !item.isLiked
            if (item.isLiked) {
                holder.ivHeart.setColorFilter(Color.parseColor("#FE2C55"))
                holder.ivHeart.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction {
                    holder.ivHeart.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }.start()
            } else {
                holder.ivHeart.clearColorFilter()
            }
        }
    }

    override fun getItemCount(): Int = comments.size
}