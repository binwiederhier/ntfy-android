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
import io.heckel.ntfy.data.Status
import io.heckel.ntfy.data.Topic
import io.heckel.ntfy.data.topicShortUrl
import io.heckel.ntfy.data.topicUrl
import io.heckel.ntfy.detail.DetailActivity
import kotlin.random.Random

const val TOPIC_ID = "topic_id"
const val TOPIC_NAME = "topic_name"
const val TOPIC_BASE_URL = "base_url"

class MainActivity : AppCompatActivity() {
    private val newTopicActivityRequestCode = 1
    private val topicsViewModel by viewModels<TopicsViewModel> {
        TopicsViewModelFactory()
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
                val name = data.getStringExtra(TOPIC_NAME) ?: return
                val baseUrl = data.getStringExtra(TOPIC_BASE_URL) ?: return
                val topic = Topic(Random.nextLong(), name, baseUrl, Status.CONNECTING, 0)

                topicsViewModel.add(topic)
            }
        }
    }

    private fun displayNotification(n: Notification) {
        val channelId = getString(R.string.notification_channel_id)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ntfy)
            .setContentTitle(topicShortUrl(n.topic))
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
