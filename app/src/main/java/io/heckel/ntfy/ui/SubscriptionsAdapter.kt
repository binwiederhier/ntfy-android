package io.heckel.ntfy.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Status
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.data.topicUrl

class TopicsAdapter(private val onClick: (Subscription) -> Unit) :
    ListAdapter<Subscription, TopicsAdapter.TopicViewHolder>(TopicDiffCallback) {

    /* ViewHolder for Topic, takes in the inflated view and the onClick behavior. */
    class TopicViewHolder(itemView: View, val onClick: (Subscription) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private var topic: Subscription? = null
        private val context: Context = itemView.context
        private val nameView: TextView = itemView.findViewById(R.id.topic_text)
        private val statusView: TextView = itemView.findViewById(R.id.topic_status)

        init {
            itemView.setOnClickListener {
                topic?.let {
                    onClick(it)
                }
            }
        }

        fun bind(subscription: Subscription) {
            this.topic = subscription
            val statusText = when (subscription.status) {
                Status.CONNECTING -> context.getString(R.string.status_connecting)
                else -> context.getString(R.string.status_connected)
            }
            val statusMessage = if (subscription.messages == 1) {
                context.getString(R.string.status_text_one, statusText, subscription.messages)
            } else {
                context.getString(R.string.status_text_not_one, statusText, subscription.messages)
            }
            nameView.text = topicUrl(subscription)
            statusView.text = statusMessage
        }
    }

    /* Creates and inflates view and return TopicViewHolder. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopicViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.topic_item, parent, false)
        return TopicViewHolder(view, onClick)
    }

    /* Gets current topic and uses it to bind view. */
    override fun onBindViewHolder(holder: TopicViewHolder, position: Int) {
        val topic = getItem(position)
        holder.bind(topic)
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
