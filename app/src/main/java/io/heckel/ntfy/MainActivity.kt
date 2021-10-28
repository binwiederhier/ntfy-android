package io.heckel.ntfy

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
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Status
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.data.topicShortUrl
import io.heckel.ntfy.detail.DetailActivity
import kotlin.random.Random

const val SUBSCRIPTION_ID = "topic_id"

class MainActivity : AppCompatActivity(), AddFragment.Listener {
    private val subscriptionViewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory()
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
        val adapter = TopicsAdapter { topic -> subscriptionOnClick(topic) }
        val recyclerView: RecyclerView = findViewById(R.id.recycler_view)
        recyclerView.adapter = adapter

        subscriptionViewModel.list().observe(this) {
            it?.let {
                println("new data arrived: $it")
                adapter.submitList(it as MutableList<Subscription>)
            }
        }

        // Set up notification channel
        createNotificationChannel()
        subscriptionViewModel.setListener { n -> displayNotification(n) }
    }

    /* Opens detail view when list item is clicked. */
    private fun subscriptionOnClick(subscription: Subscription) {
        val intent = Intent(this, DetailActivity()::class.java)
        intent.putExtra(SUBSCRIPTION_ID, subscription.id)
        startActivity(intent)
    }

    /* Adds topic to topicList when FAB is clicked. */
    private fun fabOnClick() {
        val newFragment = AddFragment(this)
        newFragment.show(supportFragmentManager, "AddFragment")
    }

    override fun onAddClicked(topic: String, baseUrl: String) {
        val subscription = Subscription(Random.nextLong(), topic, baseUrl, Status.CONNECTING, 0)
        subscriptionViewModel.add(subscription)
    }

    private fun displayNotification(n: Notification) {
        val channelId = getString(R.string.notification_channel_id)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ntfy)
            .setContentTitle(topicShortUrl(n.subscription))
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
