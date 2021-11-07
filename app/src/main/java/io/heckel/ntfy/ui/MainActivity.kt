package io.heckel.ntfy.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.messaging.FirebaseMessaging
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.data.topicShortUrl
import io.heckel.ntfy.msg.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import kotlin.random.Random

class MainActivity : AppCompatActivity(), ActionMode.Callback {
    private val viewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory((application as Application).repository)
    }
    private val repository by lazy { (application as Application).repository }
    private lateinit var mainList: RecyclerView
    private lateinit var adapter: MainAdapter
    private lateinit var fab: View
    private var actionMode: ActionMode? = null
    private lateinit var api: ApiService // Context-dependent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        // Dependencies that depend on Context
        api = ApiService(this)

        // Action bar
        title = getString(R.string.main_action_bar_title)

        // Floating action button ("+")
        fab = findViewById(R.id.fab)
        fab.setOnClickListener {
            onSubscribeButtonClick()
        }

        // Update main list based on viewModel (& its datasource/livedata)
        val noEntries: View = findViewById(R.id.main_no_subscriptions)
        val onSubscriptionClick = { s: Subscription -> onSubscriptionItemClick(s) }
        val onSubscriptionLongClick = { s: Subscription -> onSubscriptionItemLongClick(s) }

        mainList = findViewById(R.id.main_subscriptions_list)
        adapter = MainAdapter(onSubscriptionClick, onSubscriptionLongClick)
        mainList.adapter = adapter

        viewModel.list().observe(this) {
            it?.let {
                adapter.submitList(it as MutableList<Subscription>)
                if (it.isEmpty()) {
                    mainList.visibility = View.GONE
                    noEntries.visibility = View.VISIBLE
                } else {
                    mainList.visibility = View.VISIBLE
                    noEntries.visibility = View.GONE
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
        // FIXME ignores baseUrl
        Log.d(TAG, "Adding subscription ${topicShortUrl(baseUrl, topic)}")

        // Add subscription to database
        val subscription = Subscription(
            id = Random.nextLong(),
            baseUrl = baseUrl,
            topic = topic,
            notifications = 0,
            lastActive = Date().time/1000
        )
        viewModel.add(subscription)

        // Subscribe to Firebase topic
        FirebaseMessaging
            .getInstance()
            .subscribeToTopic(topic)
            .addOnCompleteListener {
                Log.d(TAG, "Subscribing to topic complete: result=${it.result}, exception=${it.exception}, successful=${it.isSuccessful}")
            }
            .addOnFailureListener {
                Log.e(TAG, "Subscribing to topic failed: $it")
            }

        // Fetch cached messages
        val successFn = { notifications: List<Notification> ->
            lifecycleScope.launch(Dispatchers.IO) {
                notifications.forEach { repository.addNotification(it) }
            }
            Unit
        }
        api.poll(subscription.id, subscription.baseUrl, subscription.topic, successFn, { _ -> })

        // Switch to detail view after adding it
        onSubscriptionItemClick(subscription)
    }

    private fun onSubscriptionItemClick(subscription: Subscription) {
        if (actionMode != null) {
            handleActionModeClick(subscription)
        } else {
            startDetailView(subscription)
        }
    }

    private fun onSubscriptionItemLongClick(subscription: Subscription) {
        if (actionMode == null) {
            beginActionMode(subscription)
        }
    }

    private fun startDetailView(subscription: Subscription) {
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

    private fun handleActionModeClick(subscription: Subscription) {
        adapter.toggleSelection(subscription.id)
        if (adapter.selected.size == 0) {
            finishActionMode()
        } else {
            actionMode!!.title = adapter.selected.size.toString()
            redrawList()
        }
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        this.actionMode = mode
        if (mode != null) {
            mode.menuInflater.inflate(R.menu.main_action_mode_menu, menu)
            mode.title = "1" // One item selected
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        return false
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.main_action_mode_delete -> {
                onMultiDeleteClick()
                true
            }
            else -> false
        }
    }

    private fun onMultiDeleteClick() {
        Log.d(DetailActivity.TAG, "Showing multi-delete dialog for selected items")

        val builder = AlertDialog.Builder(this)
        builder
            .setMessage(R.string.main_action_mode_delete_dialog_message)
            .setPositiveButton(R.string.main_action_mode_delete_dialog_permanently_delete) { _, _ ->
                adapter.selected.map { viewModel.remove(it) }
                finishActionMode()
            }
            .setNegativeButton(R.string.main_action_mode_delete_dialog_cancel) { _, _ ->
                finishActionMode()
            }
            .create()
            .show()
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        endActionModeAndRedraw()
    }

    private fun beginActionMode(subscription: Subscription) {
        actionMode = startActionMode(this)
        adapter.selected.add(subscription.id)
        redrawList()

        // Fade out FAB
        fab.alpha = 1f
        fab
            .animate()
            .alpha(0f)
            .setDuration(ANIMATION_DURATION)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fab.visibility = View.GONE
                }
            })

        // Fade status bar color
        val fromColor = ContextCompat.getColor(this, R.color.primaryColor)
        val toColor = ContextCompat.getColor(this, R.color.primaryDarkColor)
        fadeStatusBarColor(window, fromColor, toColor)
    }

    private fun finishActionMode() {
        actionMode!!.finish()
        endActionModeAndRedraw()
    }

    private fun endActionModeAndRedraw() {
        actionMode = null
        adapter.selected.clear()
        redrawList()

        // Fade in FAB
        fab.alpha = 0f
        fab.visibility = View.VISIBLE
        fab
            .animate()
            .alpha(1f)
            .setDuration(ANIMATION_DURATION)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fab.visibility = View.VISIBLE // Required to replace the old listener
                }
            })

        // Fade status bar color
        val fromColor = ContextCompat.getColor(this, R.color.primaryDarkColor)
        val toColor = ContextCompat.getColor(this, R.color.primaryColor)
        fadeStatusBarColor(window, fromColor, toColor)
    }

    private fun redrawList() {
        mainList.adapter = adapter // Oh, what a hack ...
    }

    companion object {
        const val TAG = "NtfyMainActivity"
        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        const val EXTRA_SUBSCRIPTION_BASE_URL = "subscriptionBaseUrl"
        const val EXTRA_SUBSCRIPTION_TOPIC = "subscriptionTopic"
        const val REQUEST_CODE_DELETE_SUBSCRIPTION = 1
        const val ANIMATION_DURATION = 80L
    }
}
