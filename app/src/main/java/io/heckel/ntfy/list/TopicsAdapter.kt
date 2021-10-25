/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.heckel.ntfy.list

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.heckel.ntfy.R
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

        /* Bind topic name and image. */
        fun bind(topic: Topic) {
            currentTopic = topic
            topicTextView.text = topic.url
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
        return oldItem.id == newItem.id
    }
}
