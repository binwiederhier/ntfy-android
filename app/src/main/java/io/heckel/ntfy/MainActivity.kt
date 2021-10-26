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

package io.heckel.ntfy

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
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.add.AddTopicActivity
import io.heckel.ntfy.data.Topic
import io.heckel.ntfy.detail.DetailActivity
import io.heckel.ntfy.list.*
import kotlin.random.Random

const val TOPIC_ID = "topic id"
const val TOPIC_URL = "url"

class MainActivity : AppCompatActivity() {
    private val newTopicActivityRequestCode = 1
    private val topicsViewModel by viewModels<TopicsViewModel> {
        TopicsViewModelFactory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Floating action button ("+")
        val fab: View = findViewById(R.id.fab)
        fab.setOnClickListener {
            fabOnClick()
        }

        // Update main list based on topicsViewModel (& its datasource/livedata)
        val adapter = TopicsAdapter { topic -> topicOnClick(topic) }
        val recyclerView: RecyclerView = findViewById(R.id.recycler_view)
        recyclerView.adapter = adapter

        topicsViewModel.list().observe(this) {
            it?.let {
                adapter.submitList(it as MutableList<Topic>)
            }
        }

        // Set up notification channel
        createNotificationChannel()
        topicsViewModel.setNotificationListener { n -> displayNotification(n) }
    }

    /* Opens TopicDetailActivity when RecyclerView item is clicked. */
    private fun topicOnClick(topic: Topic) {
        val intent = Intent(this, DetailActivity()::class.java)
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

        if (requestCode == newTopicActivityRequestCode && resultCode == Activity.RESULT_OK) {
            intentData?.let { data ->
                val topicId = Random.nextLong()
                val topicUrl = data.getStringExtra(TOPIC_URL) ?: return
                val topic = Topic(topicId, topicUrl)

                topicsViewModel.add(topic)
            }
        }
    }

    private fun displayNotification(n: Notification) {
        val channelId = getString(R.string.notification_channel_id)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ntfy)
            .setContentTitle(n.topic)
            .setContentText(n.message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        with(NotificationManagerCompat.from(this)) {
            notify(Random.nextInt(), notification)
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
