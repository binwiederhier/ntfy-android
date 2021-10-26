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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.R
import io.heckel.ntfy.add.AddTopicActivity
import io.heckel.ntfy.add.TOPIC_URL
import io.heckel.ntfy.data.NtfyApi
import io.heckel.ntfy.data.Topic
import io.heckel.ntfy.detail.TopicDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

const val TOPIC_ID = "topic id"

class TopicsListActivity : AppCompatActivity() {
    private val api = NtfyApi(this)
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

        createNotificationChannel()

        api.getEventsFlow().asLiveData(Dispatchers.IO).observe(this) { event ->
            this.lifecycleScope.launch(Dispatchers.Main) {
                withContext(Dispatchers.IO) {
                    handleEvent(event)
                }
            }
        }
    }

    private fun handleEvent(event: NtfyApi.Event) {
        if (event.data.isJsonNull || !event.data.has("message")) {
            return
        }
        println("PHIL EVENT: " + event.data)
        val channelId = getString(R.string.notification_channel_id)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ntfy)
            .setContentTitle("ntfy")
            .setContentText(event.data.get("message").asString)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        with(NotificationManagerCompat.from(this)) {
            notify(Random.nextInt(), notification)
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
                val topicName = data.getStringExtra(TOPIC_URL)
                topicsListViewModel.insertTopic(topicName)
            }
        }
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getString(R.string.notification_channel_id)
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_name)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

}
