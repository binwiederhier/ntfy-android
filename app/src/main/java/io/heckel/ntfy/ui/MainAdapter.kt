package io.heckel.ntfy.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.ConnectionState
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.readBitmapFromUriOrNull
import java.text.DateFormat
import java.util.*

class MainAdapter(
    private val repository: Repository,
    private val onClick: (Subscription) -> Unit,
    private val onLongClick: (Subscription) -> Unit,
    private val countDrawable: Drawable,
    private val onPrimaryColor: Int
) :
    ListAdapter<Subscription, MainAdapter.SubscriptionViewHolder>(TopicDiffCallback) {
    val selected = mutableSetOf<Long>() // Subscription IDs

    /* Creates and inflates view and return TopicViewHolder. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubscriptionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_main_item, parent, false)
        return SubscriptionViewHolder(view, repository, selected, onClick, onLongClick, countDrawable, onPrimaryColor)
    }

    /* Gets current topic and uses it to bind view. */
    override fun onBindViewHolder(holder: SubscriptionViewHolder, position: Int) {
        val subscription = getItem(position)
        holder.bind(subscription)
    }

    fun toggleSelection(subscriptionId: Long) {
        if (selected.contains(subscriptionId)) {
            selected.remove(subscriptionId)
        } else {
            selected.add(subscriptionId)
        }

        if (selected.size != 0) {
            val listIds = currentList.map { subscription -> subscription.id }
            val subscriptionPosition = listIds.indexOf(subscriptionId)
            notifyItemChanged(subscriptionPosition)
        }
    }

    /* ViewHolder for Topic, takes in the inflated view and the onClick behavior. */
    class SubscriptionViewHolder(
        itemView: View,
        private val repository: Repository,
        private val selected: Set<Long>,
        val onClick: (Subscription) -> Unit,
        val onLongClick: (Subscription) -> Unit,
        private val countDrawable: Drawable,
        private val onPrimaryColor: Int
    ) :
        RecyclerView.ViewHolder(itemView) {
        private var subscription: Subscription? = null
        private val context: Context = itemView.context
        private val imageView: ImageView = itemView.findViewById(R.id.main_item_image)
        private val nameView: TextView = itemView.findViewById(R.id.main_item_text)
        private val statusView: TextView = itemView.findViewById(R.id.main_item_status)
        private val dateView: TextView = itemView.findViewById(R.id.main_item_date)
        private val notificationDisabledUntilImageView: View = itemView.findViewById(R.id.main_item_notification_disabled_until_image)
        private val notificationDisabledForeverImageView: View = itemView.findViewById(R.id.main_item_notification_disabled_forever_image)
        private val instantImageView: View = itemView.findViewById(R.id.main_item_instant_image)
        private val newItemsView: TextView = itemView.findViewById(R.id.main_item_new)
        private val appBaseUrl = context.getString(R.string.app_base_url)

        fun bind(subscription: Subscription) {
            this.subscription = subscription
            val isUnifiedPush = subscription.upAppId != null
            var statusMessage = if (isUnifiedPush) {
                context.getString(R.string.main_item_status_unified_push, subscription.upAppId)
            } else if (subscription.totalCount == 1) {
                context.getString(R.string.main_item_status_text_one, subscription.totalCount)
            } else {
                context.getString(R.string.main_item_status_text_not_one, subscription.totalCount)
            }
            if (subscription.instant && subscription.state == ConnectionState.CONNECTING) {
                statusMessage += ", " + context.getString(R.string.main_item_status_reconnecting)
            }
            val date = Date(subscription.lastActive * 1000)
            val dateStr = DateFormat.getDateInstance(DateFormat.SHORT).format(date)
            val moreThanOneDay = System.currentTimeMillis()/1000 - subscription.lastActive > 24 * 60 * 60
            val sameDay = dateStr == DateFormat.getDateInstance(DateFormat.SHORT).format(Date()) // Omg this is horrible
            val dateText = if (subscription.lastActive == 0L) {
                ""
            } else if (sameDay) {
                DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
            } else if (!moreThanOneDay) {
                context.getString(R.string.main_item_date_yesterday)
            } else {
                dateStr
            }
            val globalMutedUntil = repository.getGlobalMutedUntil()
            val showMutedForeverIcon = (subscription.mutedUntil == 1L || globalMutedUntil == 1L) && !isUnifiedPush
            val showMutedUntilIcon = !showMutedForeverIcon && (subscription.mutedUntil > 1L || globalMutedUntil > 1L) && !isUnifiedPush
            if (subscription.icon != null) {
                imageView.setImageBitmap(subscription.icon.readBitmapFromUriOrNull(context))
            } else {
                imageView.setImageResource(R.drawable.ic_sms_gray_24dp)
            }
            nameView.text = displayName(appBaseUrl, subscription)
            statusView.text = statusMessage
            dateView.text = dateText
            dateView.visibility = if (isUnifiedPush) View.GONE else View.VISIBLE
            notificationDisabledUntilImageView.visibility = if (showMutedUntilIcon) View.VISIBLE else View.GONE
            notificationDisabledForeverImageView.visibility = if (showMutedForeverIcon) View.VISIBLE else View.GONE
            instantImageView.visibility = if (subscription.instant && BuildConfig.FIREBASE_AVAILABLE) View.VISIBLE else View.GONE
            if (isUnifiedPush || subscription.newCount == 0) {
                newItemsView.visibility = View.GONE
            } else {
                newItemsView.visibility = View.VISIBLE
                newItemsView.text = if (subscription.newCount <= 99) subscription.newCount.toString() else "99+"
                newItemsView.setTextColor(onPrimaryColor)
                newItemsView.background = countDrawable
            }
            itemView.setOnClickListener { onClick(subscription) }
            itemView.setOnLongClickListener { onLongClick(subscription); true }
            if (selected.contains(subscription.id)) {
                itemView.setBackgroundColor(Colors.itemSelectedBackground(context))
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    object TopicDiffCallback : DiffUtil.ItemCallback<Subscription>() {
        override fun areItemsTheSame(oldItem: Subscription, newItem: Subscription): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Subscription, newItem: Subscription): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        const val TAG = "NtfyMainAdapter"
    }
}
