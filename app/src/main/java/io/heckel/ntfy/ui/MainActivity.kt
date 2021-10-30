package io.heckel.ntfy.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.tasks.OnCompleteListener
import io.heckel.ntfy.R
import kotlin.random.Random
import com.google.firebase.messaging.FirebaseMessaging
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.*

class MainActivity : AppCompatActivity(), AddFragment.AddSubscriptionListener {
    private val subscriptionsViewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory((application as Application).repository)
    }

    fun doStuff() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@OnCompleteListener
            }

            // Get new FCM registration token
            val token = task.result

            // Log and toast
            Log.d(TAG, "message token: $token")
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        // Action bar
        title = getString(R.string.main_action_bar_title)

        // Floating action button ("+")
        val fab: View = findViewById(R.id.fab)
        fab.setOnClickListener {
            onSubscribeButtonClick()
        }

        // Update main list based on topicsViewModel (& its datasource/livedata)
        val noSubscriptionsText: View = findViewById(R.id.main_no_subscriptions_text)
        val adapter = SubscriptionsAdapter { subscription -> onUnsubscribe(subscription) }
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
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.app_base_url))))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onSubscribeButtonClick() {
        val newFragment = AddFragment(this)
        newFragment.show(supportFragmentManager, "AddFragment")
    }

    override fun onSubscribe(topic: String, baseUrl: String) {
        val subscription = Subscription(id = Random.nextLong(), baseUrl = baseUrl, topic = topic, messages = 0)
        subscriptionsViewModel.add(subscription)
        FirebaseMessaging.getInstance().subscribeToTopic(topic) // FIXME ignores baseUrl
    }

    private fun onUnsubscribe(subscription: Subscription) {
        subscriptionsViewModel.remove(subscription)
        FirebaseMessaging.getInstance().unsubscribeFromTopic(subscription.topic)
    }

    companion object {
        const val TAG = "NtfyMainActivity"
    }
}
