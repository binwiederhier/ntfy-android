package io.heckel.ntfy.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.data.topicShortUrl
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.msg.SubscriberService
import io.heckel.ntfy.msg.SubscriberService.ServiceState
import io.heckel.ntfy.msg.SubscriberService.Actions
import io.heckel.ntfy.msg.SubscriberService.Companion.readServiceState
import io.heckel.ntfy.work.PollWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MainActivity : AppCompatActivity(), ActionMode.Callback {
    private val viewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory((application as Application).repository)
    }
    private val repository by lazy { (application as Application).repository }
    private val api = ApiService()

    private lateinit var mainList: RecyclerView
    private lateinit var adapter: MainAdapter
    private lateinit var fab: View
    private var actionMode: ActionMode? = null
    private var workManager: WorkManager? = null // Context-dependent
    private var notifier: NotificationService? = null // Context-dependent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        Log.d(TAG, "Create $this")

        // Dependencies that depend on Context
        workManager = WorkManager.getInstance(this)
        notifier = NotificationService(this)

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
            it?.let { subscriptions ->
                adapter.submitList(subscriptions as MutableList<Subscription>)
                if (it.isEmpty()) {
                    mainList.visibility = View.GONE
                    noEntries.visibility = View.VISIBLE
                } else {
                    mainList.visibility = View.VISIBLE
                    noEntries.visibility = View.GONE
                }
            }
        }

        viewModel.listIds().observe(this) {
            maybeStartOrStopSubscriberService()
        }

        // Background things
        startPeriodicWorker()
        maybeStartOrStopSubscriberService()
    }

    private fun startPeriodicWorker() {
        val sharedPrefs = getSharedPreferences(SHARED_PREFS_ID, Context.MODE_PRIVATE)
        val workPolicy = if (sharedPrefs.getInt(SHARED_PREFS_POLL_WORKER_VERSION, 0) == PollWorker.VERSION) {
            Log.d(TAG, "Poll worker version matches: choosing KEEP as existing work policy")
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            Log.d(TAG, "Poll worker version DOES NOT MATCH: choosing REPLACE as existing work policy")
            sharedPrefs.edit()
                .putInt(SHARED_PREFS_POLL_WORKER_VERSION, PollWorker.VERSION)
                .apply()
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(PollWorker.TAG)
            .addTag(PollWorker.WORK_NAME_PERIODIC)
            .build()
        workManager!!.enqueueUniquePeriodicWork(PollWorker.WORK_NAME_PERIODIC, workPolicy, work)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_action_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.main_menu_refresh -> {
                refreshAllSubscriptions()
                true
            }
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
        val newFragment = AddFragment(viewModel) { topic, baseUrl, instant -> onSubscribe(topic, baseUrl, instant) }
        newFragment.show(supportFragmentManager, "AddFragment")
    }

    private fun onSubscribe(topic: String, baseUrl: String, instant: Boolean) {
        Log.d(TAG, "Adding subscription ${topicShortUrl(baseUrl, topic)}")

        // Add subscription to database
        val subscription = Subscription(
            id = Random.nextLong(),
            baseUrl = baseUrl,
            topic = topic,
            instant = instant,
            notifications = 0,
            lastActive = Date().time/1000
        )
        viewModel.add(subscription)

        // Subscribe to Firebase topic (instant subscriptions are triggered in observe())
        if (!instant) {
            Log.d(TAG, "Subscribing to Firebase")
            FirebaseMessaging
                .getInstance()
                .subscribeToTopic(topic)
                .addOnCompleteListener {
                    Log.d(TAG, "Subscribing to topic complete: result=${it.result}, exception=${it.exception}, successful=${it.isSuccessful}")
                }
                .addOnFailureListener {
                    Log.e(TAG, "Subscribing to topic failed: $it")
                }
        }

        // Fetch cached messages
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic)
                notifications.forEach { notification -> repository.addNotification(notification) }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to fetch notifications: ${e.stackTrace}")
            }
        }

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

    private fun refreshAllSubscriptions() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Polling for new notifications")
                var newNotificationsCount = 0
                repository.getSubscriptions().forEach { subscription ->
                    val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic)
                    val newNotifications = repository.onlyNewNotifications(subscription.id, notifications)
                    newNotifications.forEach { notification ->
                        repository.addNotification(notification)
                        notifier?.send(subscription, notification.message)
                        newNotificationsCount++
                    }
                }
                val toastMessage = if (newNotificationsCount == 0) {
                    getString(R.string.refresh_message_no_results)
                } else {
                    getString(R.string.refresh_message_result, newNotificationsCount)
                }
                runOnUiThread { Toast.makeText(this@MainActivity, toastMessage, Toast.LENGTH_LONG).show() }
                Log.d(TAG, "Finished polling for new notifications")
            } catch (e: Exception) {
                Log.e(TAG, "Polling failed: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getString(R.string.refresh_message_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun maybeStartOrStopSubscriberService() {
        Log.d(TAG, "Triggering subscriber service refresh")
        lifecycleScope.launch(Dispatchers.IO) {
            val instantSubscriptions = repository.getSubscriptions().filter { s -> s.instant }
            if (instantSubscriptions.isEmpty()) {
                performActionOnSubscriberService(Actions.STOP)
            } else {
                performActionOnSubscriberService(Actions.START)
            }
        }
    }

    private fun performActionOnSubscriberService(action: Actions) {
        val serviceState = readServiceState(this)
        if (serviceState == ServiceState.STOPPED && action == Actions.STOP) {
            return
        }
        val intent = Intent(this, SubscriberService::class.java)
        intent.action = action.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "Performing SubscriberService action: ${action.name} (as foreground service, API >= 26)")
            startForegroundService(intent)
            return
        } else {
            Log.d(TAG, "Performing SubscriberService action: ${action.name} (as background service, API >= 26)")
            startService(intent)
        }
    }

    private fun startDetailView(subscription: Subscription) {
        Log.d(TAG, "Entering detail view for subscription $subscription")

        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(EXTRA_SUBSCRIPTION_ID, subscription.id)
        intent.putExtra(EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
        intent.putExtra(EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
        intent.putExtra(EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
        startActivityForResult(intent, REQUEST_CODE_DELETE_SUBSCRIPTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_DELETE_SUBSCRIPTION && resultCode == RESULT_OK) {
            val subscriptionId = data?.getLongExtra(EXTRA_SUBSCRIPTION_ID, 0)
            val subscriptionTopic = data?.getStringExtra(EXTRA_SUBSCRIPTION_TOPIC)
            val subscriptionInstant = data?.getBooleanExtra(EXTRA_SUBSCRIPTION_INSTANT, false)
            Log.d(TAG, "Deleting subscription with subscription ID $subscriptionId (topic: $subscriptionTopic)")

            subscriptionId?.let { id -> viewModel.remove(id) }
            subscriptionInstant?.let { instant ->
                if (!instant) {
                    Log.d(TAG, "Unsubscribing from Firebase")
                    subscriptionTopic?.let { topic -> FirebaseMessaging.getInstance().unsubscribeFromTopic(topic) }
                }
                // Subscriber service changes are triggered in the observe() call above
            }
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
        const val EXTRA_SUBSCRIPTION_INSTANT = "subscriptionInstant"
        const val REQUEST_CODE_DELETE_SUBSCRIPTION = 1
        const val ANIMATION_DURATION = 80L
        const val SHARED_PREFS_ID = "MainPreferences"
        const val SHARED_PREFS_POLL_WORKER_VERSION = "PollWorkerVersion"
    }
}
