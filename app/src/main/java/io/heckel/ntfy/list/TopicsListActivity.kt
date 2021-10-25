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

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.add.AddTopicActivity
import io.heckel.ntfy.detail.TopicDetailActivity
import com.heckel.ntfy.R
import io.heckel.ntfy.add.TOPIC_DESCRIPTION
import io.heckel.ntfy.add.TOPIC_NAME
import io.heckel.ntfy.data.Topic

const val TOPIC_ID = "topic id"

class TopicsListActivity : AppCompatActivity() {
    private val newTopicActivityRequestCode = 1
    private val topicsListViewModel by viewModels<TopicsListViewModel> {
        TopicsListViewModelFactory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val adapter = TopicsAdapter { topic -> adapterOnClick(topic) }
        val recyclerView: RecyclerView = findViewById(R.id.recycler_view)
        recyclerView.adapter = adapter

        topicsListViewModel.topicsLiveData.observe(this) {
            it?.let {
                adapter.submitList(it as MutableList<Topic>)
            }
        }

        val fab: View = findViewById(R.id.fab)
        fab.setOnClickListener {
            fabOnClick()
        }
    }

    /* Opens TopicDetailActivity when RecyclerView item is clicked. */
    private fun adapterOnClick(topic: Topic) {
        val intent = Intent(this, TopicDetailActivity()::class.java)
        intent.putExtra(TOPIC_ID, topic.id)
        startActivity(intent)
    }

    /* Adds topic to topicList when FAB is clicked. */
    private fun fabOnClick() {
        val intent = Intent(this, AddTopicActivity::class.java)
        startActivityForResult(intent, newTopicActivityRequestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intentData: Intent?) {
        super.onActivityResult(requestCode, resultCode, intentData)

        /* Inserts topic into viewModel. */
        if (requestCode == newTopicActivityRequestCode && resultCode == Activity.RESULT_OK) {
            intentData?.let { data ->
                val topicName = data.getStringExtra(TOPIC_NAME)
                val topicDescription = data.getStringExtra(TOPIC_DESCRIPTION)

                topicsListViewModel.insertTopic(topicName, topicDescription)
            }
        }
    }
}
