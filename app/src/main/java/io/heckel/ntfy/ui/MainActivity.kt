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
import com.google.firebase.messaging.FirebaseMessaging
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.data.topicShortUrl
import java.util.*
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private val viewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory((application as Application).repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        // TODO implement multi-select delete - https://enoent.fr/posts/recyclerview-basics/

        // Action bar
        title = getString(R.string.main_action_bar_title)

        // Floating action button ("+")
        val fab: View = findViewById(R.id.fab)
        fab.setOnClickListener {
            onSubscribeButtonClick()
        }

        // Update main list based on viewModel (& its datasource/livedata)
        val noEntriesText: View = findViewById(R.id.main_no_subscriptions_text)
        val adapter = SubscriptionsAdapter { subscription -> onSubscriptionItemClick(subscription) }
        val mainList: RecyclerView = findViewById(R.id.main_subscriptions_list)
        mainList.adapter = adapter

        viewModel.list().observe(this) {
            it?.let {
                adapter.submitList(it as MutableList<Subscription>)
                if (it.isEmpty()) {
                    mainList.visibility = View.GONE
                    noEntriesText.visibility = View.VISIBLE
                } else {
                    mainList.visibility = View.VISIBLE
                    noEntriesText.visibility = View.GONE
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
            R.id.main_menu_source -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.main_menu_source_url))))
                true
            }
            R.id.main_menu_website -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.app_base_url))))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onSubscribeButtonClick() {
        val newFragment = AddFragment(viewModel) { topic, baseUrl -> onSubscribe(topic, baseUrl) }
        newFragment.show(supportFragmentManager, "AddFragment")
    }

    private fun onSubscribe(topic: String, baseUrl: String) {
        Log.d(TAG, "Adding subscription ${topicShortUrl(baseUrl, topic)}")

        val subscription = Subscription(id = Random.nextLong(), baseUrl = baseUrl, topic = topic, notifications = 0, lastActive = Date().time/1000)
        viewModel.add(subscription)
        FirebaseMessaging.getInstance().subscribeToTopic(topic) // FIXME ignores baseUrl
    }

    private fun onSubscriptionItemClick(subscription: Subscription) {
        Log.d(TAG, "Entering detail view for subscription $subscription")

        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(EXTRA_SUBSCRIPTION_ID, subscription.id)
        intent.putExtra(EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
        intent.putExtra(EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
        startActivityForResult(intent, REQUEST_CODE_DELETE_SUBSCRIPTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_DELETE_SUBSCRIPTION && resultCode == RESULT_OK) {
            val subscriptionId = data?.getLongExtra(EXTRA_SUBSCRIPTION_ID, 0)
            val subscriptionTopic = data?.getStringExtra(EXTRA_SUBSCRIPTION_TOPIC)
            Log.d(TAG, "Deleting subscription with subscription ID $subscriptionId (topic: $subscriptionTopic)")

            subscriptionId?.let { id -> viewModel.remove(id) }
            subscriptionTopic?.let { topic -> FirebaseMessaging.getInstance().unsubscribeFromTopic(topic) } // FIXME This only works for ntfy.sh
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    companion object {
        const val TAG = "NtfyMainActivity"
        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        const val EXTRA_SUBSCRIPTION_BASE_URL = "subscriptionBaseUrl"
        const val EXTRA_SUBSCRIPTION_TOPIC = "subscriptionTopic"
        const val REQUEST_CODE_DELETE_SUBSCRIPTION = 1;
    }
}
