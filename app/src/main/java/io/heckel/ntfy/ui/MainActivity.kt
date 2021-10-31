package io.heckel.ntfy.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Subscription
import kotlin.random.Random

class MainActivity : AppCompatActivity(), AddFragment.AddSubscriptionListener {
    private val subscriptionsViewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory((application as Application).repository)
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
