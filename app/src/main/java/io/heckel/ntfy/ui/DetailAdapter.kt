package io.heckel.ntfy.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.util.*
import java.util.*

class DetailAdapter(private val onClick: (Notification) -> Unit, private val onLongClick: (Notification) -> Unit) :
    ListAdapter<Notification, DetailAdapter.DetailViewHolder>(TopicDiffCallback) {
    val selected = mutableSetOf<String>() // Notification IDs

    /* Creates and inflates view and return TopicViewHolder. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DetailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_detail_item, parent, false)
        return DetailViewHolder(view, selected, onClick, onLongClick)
    }

    /* Gets current topic and uses it to bind view. */
    override fun onBindViewHolder(holder: DetailViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun toggleSelection(notificationId: String) {
        if (selected.contains(notificationId)) {
            selected.remove(notificationId)
        } else {
            selected.add(notificationId)
        }
    }

    /* ViewHolder for Topic, takes in the inflated view and the onClick behavior. */
    class DetailViewHolder(itemView: View, private val selected: Set<String>, val onClick: (Notification) -> Unit, val onLongClick: (Notification) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private var notification: Notification? = null
        private val priorityImageView: ImageView = itemView.findViewById(R.id.detail_item_priority_image)
        private val dateView: TextView = itemView.findViewById(R.id.detail_item_date_text)
        private val titleView: TextView = itemView.findViewById(R.id.detail_item_title_text)
        private val messageView: TextView = itemView.findViewById(R.id.detail_item_message_text)
        private val newImageView: View = itemView.findViewById(R.id.detail_item_new_dot)
        private val tagsView: TextView = itemView.findViewById(R.id.detail_item_tags)
        private val imageView: ImageView = itemView.findViewById(R.id.detail_item_image)

        fun bind(notification: Notification) {
            this.notification = notification

            val ctx = itemView.context
            val unmatchedTags = unmatchedTags(splitTags(notification.tags))

            dateView.text = Date(notification.timestamp * 1000).toString()
            messageView.text = formatMessage(notification)
            newImageView.visibility = if (notification.notificationId == 0) View.GONE else View.VISIBLE
            itemView.setOnClickListener { onClick(notification) }
            itemView.setOnLongClickListener { onLongClick(notification); true }
            if (notification.title != "") {
                titleView.visibility = View.VISIBLE
                titleView.text = formatTitle(notification)
            } else {
                titleView.visibility = View.GONE
            }
            if (unmatchedTags.isNotEmpty()) {
                tagsView.visibility = View.VISIBLE
                tagsView.text = ctx.getString(R.string.detail_item_tags, unmatchedTags.joinToString(", "))
            } else {
                tagsView.visibility = View.GONE
            }
            if (selected.contains(notification.id)) {
                itemView.setBackgroundResource(R.color.primarySelectedRowColor);
            }
            when (notification.priority) {
                1 -> {
                    priorityImageView.visibility = View.VISIBLE
                    priorityImageView.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_priority_1_24dp))
                }
                2 -> {
                    priorityImageView.visibility = View.VISIBLE
                    priorityImageView.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_priority_2_24dp))
                }
                3 -> {
                    priorityImageView.visibility = View.GONE
                }
                4 -> {
                    priorityImageView.visibility = View.VISIBLE
                    priorityImageView.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_priority_4_24dp))
                }
                5 -> {
                    priorityImageView.visibility = View.VISIBLE
                    priorityImageView.setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_priority_5_24dp))
                }
            }
            // 📄
            val contentUri = notification.attachment?.contentUri
            if (contentUri != null && supportedImage(notification.attachment.type)) {
                try {
                    val resolver = itemView.context.applicationContext.contentResolver
                    val bitmapStream = resolver.openInputStream(Uri.parse(contentUri))
                    val bitmap = BitmapFactory.decodeStream(bitmapStream)
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                } catch (_: Exception) {
                    imageView.visibility = View.GONE
                }
            } else {
                imageView.visibility = View.GONE
            }
        }
    }

    object TopicDiffCallback : DiffUtil.ItemCallback<Notification>() {
        override fun areItemsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem == newItem
        }
    }
}
