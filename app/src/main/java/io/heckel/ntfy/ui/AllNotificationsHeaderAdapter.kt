package io.heckel.ntfy.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Subscription

/**
 * The "All notifications" row at the top of the main list, placed in front of [MainAdapter]
 * with a ConcatAdapter. It sums up the topics below it and is only shown when there are at
 * least two topics, since with one it would just repeat that topic.
 */
class AllNotificationsHeaderAdapter(
    private val onClick: () -> Unit,
    private val countDrawable: Drawable,
    private val onPrimaryColor: Int
) : RecyclerView.Adapter<AllNotificationsHeaderAdapter.HeaderViewHolder>() {
    private var summary: Summary? = null

    // UnifiedPush topics are left out, since their messages are for other apps and never listed
    fun update(subscriptions: List<Subscription>) {
        val topics = subscriptions.filter { it.upAppId == null }
        val newSummary = if (topics.size < MIN_TOPICS) null else Summary(
            totalCount = topics.sumOf { it.totalCount },
            newCount = topics.sumOf { it.newCount },
            lastActive = topics.maxOf { it.lastActive }
        )
        val wasShown = summary != null
        summary = newSummary
        when {
            wasShown && newSummary == null -> notifyItemRemoved(0)
            !wasShown && newSummary != null -> notifyItemInserted(0)
            newSummary != null -> notifyItemChanged(0)
        }
    }

    override fun getItemCount(): Int = if (summary != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.fragment_main_item, parent, false)
        return HeaderViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        summary?.let { holder.bind(it) }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val context = itemView.context
        private val imageView: ImageView = itemView.findViewById(R.id.main_item_image)
        private val nameView: TextView = itemView.findViewById(R.id.main_item_text)
        private val statusView: TextView = itemView.findViewById(R.id.main_item_status)
        private val dateView: TextView = itemView.findViewById(R.id.main_item_date)
        private val newItemsView: TextView = itemView.findViewById(R.id.main_item_new)

        fun bind(summary: Summary) {
            imageView.setImageResource(R.drawable.ic_notifications_gray_24dp)
            nameView.text = context.getString(R.string.all_notifications_title)
            statusView.text = if (summary.totalCount == 1) {
                context.getString(R.string.main_item_status_text_one, summary.totalCount)
            } else {
                context.getString(R.string.main_item_status_text_not_one, summary.totalCount)
            }
            dateView.text = MainAdapter.formatLastActive(context, summary.lastActive)
            dateView.visibility = View.VISIBLE

            // Topic-only indicators (connection error, muted, instant) do not apply here
            listOf(
                R.id.main_item_connection_error_image,
                R.id.main_item_notification_disabled_until_image,
                R.id.main_item_notification_disabled_forever_image,
                R.id.main_item_instant_image
            ).forEach { id -> itemView.findViewById<View>(id).visibility = View.GONE }

            if (summary.newCount == 0) {
                newItemsView.visibility = View.GONE
            } else {
                newItemsView.visibility = View.VISIBLE
                newItemsView.text = if (summary.newCount <= 99) summary.newCount.toString() else "99+"
                newItemsView.setTextColor(onPrimaryColor)
                newItemsView.background = countDrawable
            }
            itemView.setOnClickListener { onClick() }
        }
    }

    data class Summary(val totalCount: Int, val newCount: Int, val lastActive: Long)

    companion object {
        private const val MIN_TOPICS = 2
    }
}
