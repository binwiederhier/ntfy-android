package io.heckel.ntfy

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.data.Status
import io.heckel.ntfy.data.Topic
import io.heckel.ntfy.data.topicUrl

class TopicsAdapter(private val onClick: (Topic) -> Unit) :
    ListAdapter<Topic, TopicsAdapter.TopicViewHolder>(TopicDiffCallback) {

    /* ViewHolder for Topic, takes in the inflated view and the onClick behavior. */
    class TopicViewHolder(itemView: View, val onClick: (Topic) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private var topic: Topic? = null
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

        fun bind(topic: Topic) {
            this.topic = topic
            val statusText = when (topic.status) {
                Status.CONNECTING -> context.getString(R.string.status_connecting)
                else -> context.getString(R.string.status_subscribed)
            }
            val statusMessage = if (topic.messages == 1) {
                context.getString(R.string.status_text_one, statusText, topic.messages)
            } else {
                context.getString(R.string.status_text_not_one, statusText, topic.messages)
            }
            nameView.text = topicUrl(topic)
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

object TopicDiffCallback : DiffUtil.ItemCallback<Topic>() {
    override fun areItemsTheSame(oldItem: Topic, newItem: Topic): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Topic, newItem: Topic): Boolean {
        return oldItem == newItem
    }
}
