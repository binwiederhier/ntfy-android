package io.heckel.ntfy.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.RecyclerView
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Status
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.data.topicShortUrl
import kotlin.random.Random


const val SUBSCRIPTION_ID = "topic_id"

class MainActivity : AppCompatActivity(), AddFragment.AddSubscriptionListener {
    private val subscriptionsViewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        // Action bar
        title = getString(R.string.main_action_bar_title)
        supportActionBar?.setIcon(R.drawable.ntfy) // FIXME this doesn't work

        // Floating action button ("+")
        val fab: View = findViewById(R.id.fab)
        fab.setOnClickListener {
            onAddButtonClick()
        }

        // Update main list based on topicsViewModel (& its datasource/livedata)
        val noSubscriptionsText: View = findViewById(R.id.main_no_subscriptions_text)
        val adapter = SubscriptionsAdapter(this) { subscription -> onUnsubscribe(subscription) }
        val mainList: RecyclerView = findViewById(R.id.main_subscriptions_list)
        mainList.adapter = adapter

        subscriptionsViewModel.list().observe(this) {
            it?.let {
                adapter.submitList(it as MutableList<Subscription>)
                if (it.isEmpty()) {
                    mainList.visibility = View.GONE
                    noSubscriptionsText.visibility = View.VISIBLE
                } else {
                    mainList.visibility = View.VISIBLE
                    noSubscriptionsText.visibility = View.GONE
                }
            }
        }

        // Set up notification channel
        createNotificationChannel()
        subscriptionsViewModel.setListener { n -> displayNotification(n) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_action_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_action_source -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.main_menu_source_url))))
                true
            }
            R.id.menu_action_website -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.main_menu_website_url))))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onUnsubscribe(subscription: Subscription) {
        subscriptionsViewModel.remove(subscription)
    }

    private fun onAddButtonClick() {
        val newFragment = AddFragment(this)
        newFragment.show(supportFragmentManager, "AddFragment")
    }

    override fun onAddSubscription(topic: String, baseUrl: String) {
        val subscription = Subscription(Random.nextLong(), topic, baseUrl, Status.CONNECTING, 0)
        subscriptionsViewModel.add(subscription)
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
