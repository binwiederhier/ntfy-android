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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import io.heckel.ntfy.add.AddTopicActivity
import io.heckel.ntfy.add.TOPIC_URL
import io.heckel.ntfy.data.Topic
import io.heckel.ntfy.detail.TopicDetailActivity
import io.heckel.ntfy.list.TopicsAdapter
import io.heckel.ntfy.list.TopicsViewModelFactory
import io.heckel.ntfy.list.TopicsViewModel
import kotlinx.coroutines.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

const val TOPIC_ID = "topic id"

class MainActivity : AppCompatActivity() {
    private val gson = GsonBuilder().create()
    private val jobs = mutableMapOf<Long, Job>()
    private val newTopicActivityRequestCode = 1
    private val topicsListViewModel by viewModels<TopicsViewModel> {
        TopicsViewModelFactory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val adapter = TopicsAdapter { topic -> adapterOnClick(topic) }
        val recyclerView: RecyclerView = findViewById(R.id.recycler_view)
        recyclerView.adapter = adapter

        topicsListViewModel.topics.observe(this) {
            it?.let {
                adapter.submitList(it as MutableList<Topic>)
            }
        }

        val fab: View = findViewById(R.id.fab)
        fab.setOnClickListener {
            fabOnClick()
        }

        createNotificationChannel()
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
                val topicId = Random.nextLong()
                val topicUrl = data.getStringExtra(TOPIC_URL) ?: return
                val topic = Topic(topicId, topicUrl)

                jobs[topicId] = subscribeTopic(topicUrl)
                topicsListViewModel.add(topic)
            }
        }
    }

    private fun subscribeTopic(url: String): Job {
        return this.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                openURL(this, url)
                delay(5000) // TODO exponential back-off
            }
        }
    }

    private fun openURL(scope: CoroutineScope, url: String) {
        println("Connecting to $url ...")
        val conn = (URL(url).openConnection() as HttpURLConnection).also {
            it.doInput = true
        }
        try {
            val input = conn.inputStream.bufferedReader()
            while (scope.isActive) {
                val line = input.readLine() ?: break // Break if null!
                try {
                    displayNotification(gson.fromJson(line, JsonObject::class.java))
                } catch (e: JsonSyntaxException) {
                    // Ignore invalid JSON
                }
            }
        } catch (e: IOException) {
            println("PHIL: " + e.message)
        } finally {
            conn.disconnect()
        }
        println("Connection terminated: $url")
    }

    private fun displayNotification(json: JsonObject) {
        if (json.isJsonNull || !json.has("message")) {
            return
        }
        val channelId = getString(R.string.notification_channel_id)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ntfy)
            .setContentTitle("ntfy")
            .setContentText(json.get("message").asString)
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
