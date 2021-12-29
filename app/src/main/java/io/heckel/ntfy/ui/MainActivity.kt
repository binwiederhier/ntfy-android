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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.*
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.util.topicShortUrl
import io.heckel.ntfy.work.PollWorker
import io.heckel.ntfy.firebase.FirebaseMessenger
import io.heckel.ntfy.msg.*
import io.heckel.ntfy.util.fadeStatusBarColor
import io.heckel.ntfy.util.formatDateShort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MainActivity : AppCompatActivity(), ActionMode.Callback, AddFragment.SubscribeListener, NotificationFragment.NotificationSettingsListener {
    private val viewModel by viewModels<SubscriptionsViewModel> {
        SubscriptionsViewModelFactory((application as Application).repository)
    }
    private val repository by lazy { (application as Application).repository }
    private val api = ApiService()
    private val messenger = FirebaseMessenger()

    // UI elements
    private lateinit var menu: Menu
    private lateinit var mainList: RecyclerView
    private lateinit var mainListContainer: SwipeRefreshLayout
    private lateinit var adapter: MainAdapter
    private lateinit var fab: View

    // Other stuff
    private var actionMode: ActionMode? = null
    private var workManager: WorkManager? = null // Context-dependent
    private var dispatcher: NotificationDispatcher? = null // Context-dependent
    private var subscriberManager: SubscriberManager? = null // Context-dependent
    private var appBaseUrl: String? = null // Context-dependent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "Create $this")

        // Dependencies that depend on Context
        workManager = WorkManager.getInstance(this)
        dispatcher = NotificationDispatcher(this, repository)
        subscriberManager = SubscriberManager(this)
        appBaseUrl = getString(R.string.app_base_url)

        // Action bar
        title = getString(R.string.main_action_bar_title)

        // Floating action button ("+")
        fab = findViewById(R.id.fab)
        fab.setOnClickListener {
            onSubscribeButtonClick()
        }

        // Swipe to refresh
        mainListContainer = findViewById(R.id.main_subscriptions_list_container)
        mainListContainer.setOnRefreshListener { refreshAllSubscriptions() }
        mainListContainer.setColorSchemeResources(R.color.primaryColor)

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
                    mainListContainer.visibility = View.GONE
                    noEntries.visibility = View.VISIBLE
                } else {
                    mainListContainer.visibility = View.VISIBLE
                    noEntries.visibility = View.GONE
                }
            }
        }

        // React to changes in instant delivery setting
        viewModel.listIdsWithInstantStatus().observe(this) {
            subscriberManager?.refreshService(it)
        }

        // Create notification channels right away, so we can configure them immediately after installing the app
        dispatcher?.init()

        // Subscribe to control Firebase channel (so we can re-start the foreground service if it dies)
        messenger.subscribe(ApiService.CONTROL_TOPIC)

        // Background things
        startPeriodicPollWorker()
        startPeriodicAutoRestartWorker()
    }

    private fun startPeriodicPollWorker() {
        val workerVersion = repository.getPollWorkerVersion()
        val workPolicy = if (workerVersion == PollWorker.VERSION) {
            Log.d(TAG, "Poll worker version matches: choosing KEEP as existing work policy")
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            Log.d(TAG, "Poll worker version DOES NOT MATCH: choosing REPLACE as existing work policy")
            repository.setPollWorkerVersion(PollWorker.VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val work = PeriodicWorkRequestBuilder<PollWorker>(MINIMUM_PERIODIC_WORKER_INTERVAL, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(PollWorker.TAG)
            .addTag(PollWorker.WORK_NAME_PERIODIC)
            .build()
        Log.d(TAG, "Poll worker: Scheduling period work every ${MINIMUM_PERIODIC_WORKER_INTERVAL} minutes")
        workManager!!.enqueueUniquePeriodicWork(PollWorker.WORK_NAME_PERIODIC, workPolicy, work)
    }

    private fun startPeriodicAutoRestartWorker() {
        val workerVersion = repository.getAutoRestartWorkerVersion()
        val workPolicy = if (workerVersion == SubscriberService.AUTO_RESTART_WORKER_VERSION) {
            Log.d(TAG, "Auto restart worker version matches: choosing KEEP as existing work policy")
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            Log.d(TAG, "Auto restart worker version DOES NOT MATCH: choosing REPLACE as existing work policy")
            repository.setAutoRestartWorkerVersion(SubscriberService.AUTO_RESTART_WORKER_VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val work = PeriodicWorkRequestBuilder<SubscriberService.AutoRestartWorker>(MINIMUM_PERIODIC_WORKER_INTERVAL, TimeUnit.MINUTES)
            .addTag(SubscriberService.TAG)
            .addTag(SubscriberService.AUTO_RESTART_WORKER_WORK_NAME_PERIODIC)
            .build()
        Log.d(TAG, "Auto restart worker: Scheduling period work every ${MINIMUM_PERIODIC_WORKER_INTERVAL} minutes")
        workManager!!.enqueueUniquePeriodicWork(SubscriberService.AUTO_RESTART_WORKER_WORK_NAME_PERIODIC, workPolicy, work)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main_action_bar, menu)
        this.menu = menu
        showHideNotificationMenuItems()
        startNotificationMutedChecker() // This is done here, because then we know that we've initialized the menu
        return true
    }

    private fun startNotificationMutedChecker() {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(1000) // Just to be sure we've initialized all the things, we wait a bit ...
            while (isActive) {
                Log.d(DetailActivity.TAG, "Checking global and subscription-specific 'muted until' timestamp")

                // Check global
                val changed = repository.checkGlobalMutedUntil()
                if (changed) {
                    Log.d(TAG, "Global muted until timestamp expired; updating prefs")
                    showHideNotificationMenuItems()
                }

                // Check subscriptions
                var rerenderList = false
                repository.getSubscriptions().forEach { subscription ->
                    val mutedUntilExpired = subscription.mutedUntil > 1L && System.currentTimeMillis()/1000 > subscription.mutedUntil
                    if (mutedUntilExpired) {
                        Log.d(TAG, "Subscription ${subscription.id}: Muted until timestamp expired, updating subscription")
                        val newSubscription = subscription.copy(mutedUntil = 0L)
                        repository.updateSubscription(newSubscription)
                        rerenderList = true
                    }
                }
                if (rerenderList) {
                    redrawList()
                }

                delay(60_000)
            }
        }
    }

    private fun showHideNotificationMenuItems() {
        val mutedUntilSeconds = repository.getGlobalMutedUntil()
        runOnUiThread {
            val notificationsEnabledItem = menu.findItem(R.id.main_menu_notifications_enabled)
            val notificationsDisabledUntilItem = menu.findItem(R.id.main_menu_notifications_disabled_until)
            val notificationsDisabledForeverItem = menu.findItem(R.id.main_menu_notifications_disabled_forever)
            notificationsEnabledItem?.isVisible = mutedUntilSeconds == 0L
            notificationsDisabledForeverItem?.isVisible = mutedUntilSeconds == 1L
            notificationsDisabledUntilItem?.isVisible = mutedUntilSeconds > 1L
            if (mutedUntilSeconds > 1L) {
                val formattedDate = formatDateShort(mutedUntilSeconds)
                notificationsDisabledUntilItem?.title = getString(R.string.main_menu_notifications_disabled_until, formattedDate)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.main_menu_notifications_enabled -> {
                onNotificationSettingsClick(enable = false)
                true
            }
            R.id.main_menu_notifications_disabled_forever -> {
                onNotificationSettingsClick(enable = true)
                true
            }
            R.id.main_menu_notifications_disabled_until -> {
                onNotificationSettingsClick(enable = true)
                true
            }
            R.id.main_menu_source -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.main_menu_source_url))))
                true
            }
            R.id.main_menu_website -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(appBaseUrl)))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onNotificationSettingsClick(enable: Boolean) {
        if (!enable) {
            Log.d(TAG, "Showing global notification settings dialog")
            val notificationFragment = NotificationFragment()
            notificationFragment.show(supportFragmentManager, NotificationFragment.TAG)
        } else {
            Log.d(TAG, "Re-enabling global notifications")
            onNotificationMutedUntilChanged(0L)
        }
    }

    override fun onNotificationMutedUntilChanged(mutedUntilTimestamp: Long) {
        repository.setGlobalMutedUntil(mutedUntilTimestamp)
        showHideNotificationMenuItems()
        runOnUiThread {
            when (mutedUntilTimestamp) {
                0L -> Toast.makeText(this@MainActivity, getString(R.string.notification_dialog_enabled_toast_message), Toast.LENGTH_LONG).show()
                1L -> Toast.makeText(this@MainActivity, getString(R.string.notification_dialog_muted_forever_toast_message), Toast.LENGTH_LONG).show()
                else -> {
                    val formattedDate = formatDateShort(mutedUntilTimestamp)
                    Toast.makeText(this@MainActivity, getString(R.string.notification_dialog_muted_until_toast_message, formattedDate), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onSubscribeButtonClick() {
        val newFragment = AddFragment()
        newFragment.show(supportFragmentManager, AddFragment.TAG)
    }

    override fun onSubscribe(topic: String, baseUrl: String, instant: Boolean) {
        Log.d(TAG, "Adding subscription ${topicShortUrl(baseUrl, topic)}")

        // Add subscription to database
        val subscription = Subscription(
            id = Random.nextLong(),
            baseUrl = baseUrl,
            topic = topic,
            instant = instant,
            mutedUntil = 0,
            upAppId = "",
            upConnectorToken = "",
            totalCount = 0,
            newCount = 0,
            lastActive = Date().time/1000
        )
        viewModel.add(subscription)

        // Subscribe to Firebase topic if ntfy.sh (even if instant, just to be sure!)
        if (baseUrl == appBaseUrl) {
            Log.d(TAG, "Subscribing to Firebase")
            messenger.subscribe(topic)
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
            Log.d(TAG, "Polling for new notifications")
            var errors = 0
            var errorMessage = "" // First error
            var newNotificationsCount = 0
            repository.getSubscriptions().forEach { subscription ->
                try {
                    val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic)
                    val newNotifications = repository.onlyNewNotifications(subscription.id, notifications)
                    newNotifications.forEach { notification ->
                        newNotificationsCount++
                        val notificationWithId = notification.copy(notificationId = Random.nextInt())
                        if (repository.addNotification(notificationWithId)) {
                            dispatcher?.dispatch(subscription, notificationWithId)
                        }
                    }
                } catch (e: Exception) {
                    val topic = topicShortUrl(subscription.baseUrl, subscription.topic)
                    if (errorMessage == "") errorMessage = "$topic: ${e.message}"
                    errors++
                }
            }
            val toastMessage = if (errors > 0) {
                getString(R.string.refresh_message_error, errors, errorMessage)
            } else if (newNotificationsCount == 0) {
                getString(R.string.refresh_message_no_results)
            } else {
                getString(R.string.refresh_message_result, newNotificationsCount)
            }
            runOnUiThread {
                Toast.makeText(this@MainActivity, toastMessage, Toast.LENGTH_LONG).show()
                mainListContainer.isRefreshing = false
            }
            Log.d(TAG, "Finished polling for new notifications")
        }
    }

    private fun startDetailView(subscription: Subscription) {
        Log.d(TAG, "Entering detail view for subscription $subscription")

        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(EXTRA_SUBSCRIPTION_ID, subscription.id)
        intent.putExtra(EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
        intent.putExtra(EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
        intent.putExtra(EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
        intent.putExtra(EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)
        startActivity(intent)
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
            mode.menuInflater.inflate(R.menu.menu_main_action_mode, menu)
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
        val dialog = builder
            .setMessage(R.string.main_action_mode_delete_dialog_message)
            .setPositiveButton(R.string.main_action_mode_delete_dialog_permanently_delete) { _, _ ->
                adapter.selected.map { viewModel.remove(it) }
                finishActionMode()
            }
            .setNegativeButton(R.string.main_action_mode_delete_dialog_cancel) { _, _ ->
                finishActionMode()
            }
            .create()
        dialog.setOnShowListener {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(ContextCompat.getColor(this, R.color.primaryDangerButtonColor))
        }
        dialog.show()
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
        runOnUiThread {
            mainList.adapter = adapter // Oh, what a hack ...
        }
    }

    companion object {
        const val TAG = "NtfyMainActivity"
        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        const val EXTRA_SUBSCRIPTION_BASE_URL = "subscriptionBaseUrl"
        const val EXTRA_SUBSCRIPTION_TOPIC = "subscriptionTopic"
        const val EXTRA_SUBSCRIPTION_INSTANT = "subscriptionInstant"
        const val EXTRA_SUBSCRIPTION_MUTED_UNTIL = "subscriptionMutedUntil"
        const val ANIMATION_DURATION = 80L

        // As per Documentation: The minimum repeat interval that can be defined is 15 minutes
        // (same as the JobScheduler API), but in practice 15 doesn't work. Using 16 here.
        // Thanks to varunon9 (https://gist.github.com/varunon9/f2beec0a743c96708eb0ef971a9ff9cd) for this!
        const val MINIMUM_PERIODIC_WORKER_INTERVAL = 16L
    }
}
