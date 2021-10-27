package io.heckel.ntfy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.data.Topic

class TopicsAdapter(private val onClick: (Topic) -> Unit) :
    ListAdapter<Topic, TopicsAdapter.TopicViewHolder>(TopicDiffCallback) {

    /* ViewHolder for Topic, takes in the inflated view and the onClick behavior. */
    class TopicViewHolder(itemView: View, val onClick: (Topic) -> Unit) :
        RecyclerView.ViewHolder(itemView) {
        private val topicTextView: TextView = itemView.findViewById(R.id.topic_text)
        private var currentTopic: Topic? = null

        init {
            itemView.setOnClickListener {
                currentTopic?.let {
                    onClick(it)
                }
            }
        }

        fun bind(topic: Topic) {
            currentTopic = topic
            val shortBaseUrl = topic.baseUrl.replace("https://", "") // Leave http:// untouched
            val shortName = itemView.context.getString(R.string.topic_short_name_format, shortBaseUrl, topic.name)
            topicTextView.text = shortName
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
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: Topic, newItem: Topic): Boolean {
        return oldItem.name == newItem.name
    }
}
