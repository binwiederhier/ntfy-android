package io.heckel.ntfy.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Status
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.data.topicShortUrl

class SubscriptionsAdapter(private val context: Context, private val onClick: (Subscription) -> Unit) :
    ListAdapter<Subscription, SubscriptionsAdapter.SubscriptionViewHolder>(TopicDiffCallback) {

    /* ViewHolder for Topic, takes in the inflated view and the onClick behavior. */
    class SubscriptionViewHolder(itemView: View, val onUnsubscribe: (Subscription) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private var subscription: Subscription? = null
        private val context: Context = itemView.context
        private val nameView: TextView = itemView.findViewById(R.id.topic_text)
        private val statusView: TextView = itemView.findViewById(R.id.topic_status)

        init {
            val popup = PopupMenu(context, itemView)
            popup.inflate(R.menu.main_item_popup_menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                     R.id.main_item_popup_unsubscribe -> {
                         subscription?.let { s -> onUnsubscribe(s) }
                         true
                     }
                     else -> false
                 }
            }
            itemView.setOnLongClickListener {
                subscription?.let { popup.show() }
                true
            }
        }

        fun bind(subscription: Subscription) {
            this.subscription = subscription
            val notificationsCountMessage = if (subscription.messages == 1) {
                context.getString(R.string.main_item_status_text_one, subscription.messages)
            } else {
                context.getString(R.string.main_item_status_text_not_one, subscription.messages)
            }
            val statusText = when (subscription.status) {
                Status.CONNECTING -> notificationsCountMessage + ", " + context.getString(R.string.main_item_status_connecting)
                Status.RECONNECTING -> notificationsCountMessage + ", " + context.getString(R.string.main_item_status_reconnecting)
                else -> notificationsCountMessage
            }
            nameView.text = topicShortUrl(subscription)
            statusView.text = statusText
        }
    }

    /* Creates and inflates view and return TopicViewHolder. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubscriptionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.main_fragment_item, parent, false)
        return SubscriptionViewHolder(view, onClick)
    }

    /* Gets current topic and uses it to bind view. */
    override fun onBindViewHolder(holder: SubscriptionViewHolder, position: Int) {
        val subscription = getItem(position)
        holder.bind(subscription)
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
