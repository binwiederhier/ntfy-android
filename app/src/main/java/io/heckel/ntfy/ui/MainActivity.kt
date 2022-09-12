package io.heckel.ntfy.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.firebase.FirebaseMessenger
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.DownloadManager
import io.heckel.ntfy.msg.DownloadType
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.*
import io.heckel.ntfy.work.DeleteWorker
import io.heckel.ntfy.work.PollWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
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
    private lateinit var fab: FloatingActionButton

    // Other stuff
    private var actionMode: ActionMode? = null
    private var workManager: WorkManager? = null // Context-dependent
    private var dispatcher: NotificationDispatcher? = null // Context-dependent
    private var appBaseUrl: String? = null // Context-dependent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.init(this) // Init logs in all entry points
        Log.d(TAG, "Create $this")

        // Dependencies that depend on Context
        workManager = WorkManager.getInstance(this)
        dispatcher = NotificationDispatcher(this, repository)
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
        mainListContainer.setColorSchemeResources(Colors.refreshProgressIndicator)

        // Update main list based on viewModel (& its datasource/livedata)
        val noEntries: View = findViewById(R.id.main_no_subscriptions)
        val onSubscriptionClick = { s: Subscription -> onSubscriptionItemClick(s) }
        val onSubscriptionLongClick = { s: Subscription -> onSubscriptionItemLongClick(s) }

        mainList = findViewById(R.id.main_subscriptions_list)
        adapter = MainAdapter(repository, onSubscriptionClick, onSubscriptionLongClick)
        mainList.adapter = adapter

        viewModel.list().observe(this) {
            it?.let { subscriptions ->
                // Update main list
                adapter.submitList(subscriptions as MutableList<Subscription>)
                if (it.isEmpty()) {
                    mainListContainer.visibility = View.GONE
                    noEntries.visibility = View.VISIBLE
                } else {
                    mainListContainer.visibility = View.VISIBLE
                    noEntries.visibility = View.GONE
                }

                // Add scrub terms to log (in case it gets exported)
                subscriptions.forEach { s ->
                    Log.addScrubTerm(shortUrl(s.baseUrl), Log.TermType.Domain)
                    Log.addScrubTerm(s.topic)
                }

                // Update banner + WebSocket banner
                showHideBatteryBanner(subscriptions)
                showHideWebSocketBanner(subscriptions)
            }
        }

        // Add scrub terms to log (in case it gets exported) // FIXME this should be in Log.getFormatted
        repository.getUsersLiveData().observe(this) {
            it?.let { users ->
                users.forEach { u ->
                    Log.addScrubTerm(shortUrl(u.baseUrl), Log.TermType.Domain)
                    Log.addScrubTerm(u.username, Log.TermType.Username)
                    Log.addScrubTerm(u.password, Log.TermType.Password)
                }
            }
        }

        // Scrub terms for last topics // FIXME this should be in Log.getFormatted
        repository.getLastShareTopics().forEach { topicUrl ->
            maybeSplitTopicUrl(topicUrl)?.let {
                Log.addScrubTerm(shortUrl(it.first), Log.TermType.Domain)
                Log.addScrubTerm(shortUrl(it.second), Log.TermType.Term)
            }
        }

        // React to changes in instant delivery setting
        viewModel.listIdsWithInstantStatus().observe(this) {
            SubscriberServiceManager.refresh(this)
        }

        // Battery banner
        val batteryBanner = findViewById<View>(R.id.main_banner_battery) // Banner visibility is toggled in onResume()
        val dontAskAgainButton = findViewById<Button>(R.id.main_banner_battery_dontaskagain)
        val askLaterButton = findViewById<Button>(R.id.main_banner_battery_ask_later)
        val fixNowButton = findViewById<Button>(R.id.main_banner_battery_fix_now)
        dontAskAgainButton.setOnClickListener {
            batteryBanner.visibility = View.GONE
            repository.setBatteryOptimizationsRemindTime(Repository.BATTERY_OPTIMIZATIONS_REMIND_TIME_NEVER)
        }
        askLaterButton.setOnClickListener {
            batteryBanner.visibility = View.GONE
            repository.setBatteryOptimizationsRemindTime(System.currentTimeMillis() + ONE_DAY_MILLIS)
        }
        fixNowButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        }

        // WebSocket banner
        val wsBanner = findViewById<View>(R.id.main_banner_websocket) // Banner visibility is toggled in onResume()
        val wsText = findViewById<TextView>(R.id.main_banner_websocket_text)
        val wsDismissButton = findViewById<Button>(R.id.main_banner_websocket_dontaskagain)
        val wsRemindButton = findViewById<Button>(R.id.main_banner_websocket_remind_later)
        val wsEnableButton = findViewById<Button>(R.id.main_banner_websocket_enable)
        wsText.movementMethod = LinkMovementMethod.getInstance() // Make links clickable
        wsDismissButton.setOnClickListener {
            wsBanner.visibility = View.GONE
            repository.setWebSocketRemindTime(Repository.WEBSOCKET_REMIND_TIME_NEVER)
        }
        wsRemindButton.setOnClickListener {
            wsBanner.visibility = View.GONE
            repository.setWebSocketRemindTime(System.currentTimeMillis() + ONE_DAY_MILLIS)
        }
        wsEnableButton.setOnClickListener {
            repository.setConnectionProtocol(Repository.CONNECTION_PROTOCOL_WS)
            SubscriberServiceManager(this).restart()
            wsBanner.visibility = View.GONE
        }

        // Create notification channels right away, so we can configure them immediately after installing the app
        dispatcher?.init()

        // Subscribe to control Firebase channel (so we can re-start the foreground service if it dies)
        messenger.subscribe(ApiService.CONTROL_TOPIC)

        // Darrkkkk mode
        AppCompatDelegate.setDefaultNightMode(repository.getDarkMode())

        // Background things
        schedulePeriodicPollWorker()
        schedulePeriodicServiceRestartWorker()
        schedulePeriodicDeleteWorker()
    }

    override fun onResume() {
        super.onResume()
        showHideNotificationMenuItems()
        redrawList()
    }

    private fun showHideBatteryBanner(subscriptions: List<Subscription>) {
        val hasInstantSubscriptions = subscriptions.count { it.instant } > 0
        val batteryRemindTimeReached = repository.getBatteryOptimizationsRemindTime() < System.currentTimeMillis()
        val ignoringOptimizations = isIgnoringBatteryOptimizations(this@MainActivity)
        val showBanner = hasInstantSubscriptions && batteryRemindTimeReached && !ignoringOptimizations
        val batteryBanner = findViewById<View>(R.id.main_banner_battery)
        batteryBanner.visibility = if (showBanner) View.VISIBLE else View.GONE
        Log.d(TAG, "Battery: ignoring optimizations = $ignoringOptimizations (we want this to be true); instant subscriptions = $hasInstantSubscriptions; remind time reached = $batteryRemindTimeReached; banner = $showBanner")
    }

    private fun showHideWebSocketBanner(subscriptions: List<Subscription>) {
        val hasSelfHostedSubscriptions = subscriptions.count { it.baseUrl != appBaseUrl } > 0
        val usingWebSockets = repository.getConnectionProtocol() == Repository.CONNECTION_PROTOCOL_WS
        val wsRemindTimeReached = repository.getWebSocketRemindTime() < System.currentTimeMillis()
        val showBanner = hasSelfHostedSubscriptions && wsRemindTimeReached && !usingWebSockets
        val wsBanner = findViewById<View>(R.id.main_banner_websocket)
        wsBanner.visibility = if (showBanner) View.VISIBLE else View.GONE
    }

    private fun schedulePeriodicPollWorker() {
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
        val work = PeriodicWorkRequestBuilder<PollWorker>(POLL_WORKER_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(PollWorker.TAG)
            .addTag(PollWorker.WORK_NAME_PERIODIC_ALL)
            .build()
        Log.d(TAG, "Poll worker: Scheduling period work every $POLL_WORKER_INTERVAL_MINUTES minutes")
        workManager!!.enqueueUniquePeriodicWork(PollWorker.WORK_NAME_PERIODIC_ALL, workPolicy, work)
    }

    private fun schedulePeriodicDeleteWorker() {
        val workerVersion = repository.getDeleteWorkerVersion()
        val workPolicy = if (workerVersion == DeleteWorker.VERSION) {
            Log.d(TAG, "Delete worker version matches: choosing KEEP as existing work policy")
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            Log.d(TAG, "Delete worker version DOES NOT MATCH: choosing REPLACE as existing work policy")
            repository.setDeleteWorkerVersion(DeleteWorker.VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val work = PeriodicWorkRequestBuilder<DeleteWorker>(DELETE_WORKER_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .addTag(DeleteWorker.TAG)
            .addTag(DeleteWorker.WORK_NAME_PERIODIC_ALL)
            .build()
        Log.d(TAG, "Delete worker: Scheduling period work every $DELETE_WORKER_INTERVAL_MINUTES minutes")
        workManager!!.enqueueUniquePeriodicWork(DeleteWorker.WORK_NAME_PERIODIC_ALL, workPolicy, work)
    }

    private fun schedulePeriodicServiceRestartWorker() {
        val workerVersion = repository.getAutoRestartWorkerVersion()
        val workPolicy = if (workerVersion == SubscriberService.SERVICE_START_WORKER_VERSION) {
            Log.d(TAG, "ServiceStartWorker version matches: choosing KEEP as existing work policy")
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            Log.d(TAG, "ServiceStartWorker version DOES NOT MATCH: choosing REPLACE as existing work policy")
            repository.setAutoRestartWorkerVersion(SubscriberService.SERVICE_START_WORKER_VERSION)
            ExistingPeriodicWorkPolicy.REPLACE
        }
        val work = PeriodicWorkRequestBuilder<SubscriberServiceManager.ServiceStartWorker>(SERVICE_START_WORKER_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .addTag(SubscriberService.TAG)
            .addTag(SubscriberService.SERVICE_START_WORKER_WORK_NAME_PERIODIC)
            .build()
        Log.d(TAG, "ServiceStartWorker: Scheduling period work every $SERVICE_START_WORKER_INTERVAL_MINUTES minutes")
        workManager?.enqueueUniquePeriodicWork(SubscriberService.SERVICE_START_WORKER_WORK_NAME_PERIODIC, workPolicy, work)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main_action_bar, menu)
        this.menu = menu
        showHideNotificationMenuItems()
        checkSubscriptionsMuted() // This is done here, because then we know that we've initialized the menu
        return true
    }

    private fun checkSubscriptionsMuted(delayMillis: Long = 0L) {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(delayMillis) // Just to be sure we've initialized all the things, we wait a bit ...
            Log.d(TAG, "Checking global and subscription-specific 'muted until' timestamp")

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
        }
    }

    private fun showHideNotificationMenuItems() {
        if (!this::menu.isInitialized) {
            return
        }
        val mutedUntilSeconds = repository.getGlobalMutedUntil()
        runOnUiThread {
            // Show/hide in-app rate widget
            val rateAppItem = menu.findItem(R.id.main_menu_rate)
            rateAppItem.isVisible = BuildConfig.RATE_APP_AVAILABLE

            // Pause notification icons
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
            R.id.main_menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.main_menu_report_bug -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.main_menu_report_bug_url))))
                true
            }
            R.id.main_menu_rate -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                }
                true
            }
            R.id.main_menu_docs -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.main_menu_docs_url))))
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
            onNotificationMutedUntilChanged(Repository.MUTED_UNTIL_SHOW_ALL)
        }
    }

    override fun onNotificationMutedUntilChanged(mutedUntilTimestamp: Long) {
        repository.setGlobalMutedUntil(mutedUntilTimestamp)
        showHideNotificationMenuItems()
        runOnUiThread {
            redrawList() // Update the "muted until" icons
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
        Log.d(TAG, "Adding subscription ${topicShortUrl(baseUrl, topic)} (instant = $instant)")

        // Add subscription to database
        val subscription = Subscription(
            id = randomSubscriptionId(),
            baseUrl = baseUrl,
            topic = topic,
            instant = instant,
            mutedUntil = 0,
            minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
            autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
            lastNotificationId = null,
            icon = null,
            upAppId = null,
            upConnectorToken = null,
            displayName = null,
            totalCount = 0,
            newCount = 0,
            lastActive = Date().time/1000
        )
        viewModel.add(subscription)

        // Subscribe to Firebase topic if ntfy.sh (even if instant, just to be sure!)
        if (baseUrl == appBaseUrl) {
            Log.d(TAG, "Subscribing to Firebase topic $topic")
            messenger.subscribe(topic)
        }

        // Fetch cached messages
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = repository.getUser(subscription.baseUrl) // May be null
                val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic, user)
                notifications.forEach { notification ->
                    repository.addNotification(notification)
                    if (notification.icon != null) {
                        DownloadManager.enqueue(this@MainActivity, notification.id, userAction = false, DownloadType.ICON)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to fetch notifications: ${e.message}", e)
            }
        }

        // Switch to detail view after adding it
        onSubscriptionItemClick(subscription)
    }

    private fun onSubscriptionItemClick(subscription: Subscription) {
        if (actionMode != null) {
            handleActionModeClick(subscription)
        } else if (subscription.upAppId != null) { // UnifiedPush
            startDetailSettingsView(subscription)
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
                Log.d(TAG, "subscription: ${subscription}")
                try {
                    val user = repository.getUser(subscription.baseUrl) // May be null
                    val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic, user, subscription.lastNotificationId)
                    val newNotifications = repository.onlyNewNotifications(subscription.id, notifications)
                    newNotifications.forEach { notification ->
                        newNotificationsCount++
                        val notificationWithId = notification.copy(notificationId = Random.nextInt())
                        if (repository.addNotification(notificationWithId)) {
                            dispatcher?.dispatch(subscription, notificationWithId)
                        }
                    }
                } catch (e: Exception) {
                    val topic = displayName(subscription)
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
        intent.putExtra(EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(subscription))
        intent.putExtra(EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
        intent.putExtra(EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)
        startActivity(intent)
    }

    private fun startDetailSettingsView(subscription: Subscription) {
        Log.d(TAG, "Opening subscription settings for ${topicShortUrl(subscription.baseUrl, subscription.topic)}")

        val intent = Intent(this, DetailSettingsActivity::class.java)
        intent.putExtra(DetailActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
        intent.putExtra(DetailActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
        intent.putExtra(DetailActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
        intent.putExtra(DetailActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(subscription))
        startActivity(intent)
    }


    private fun handleActionModeClick(subscription: Subscription) {
        adapter.toggleSelection(subscription.id)
        if (adapter.selected.size == 0) {
            finishActionMode()
        } else {
            actionMode!!.title = adapter.selected.size.toString()
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
                adapter.selected.map { subscriptionId -> viewModel.remove(this, subscriptionId) }
                finishActionMode()
            }
            .setNegativeButton(R.string.main_action_mode_delete_dialog_cancel) { _, _ ->
                finishActionMode()
            }
            .create()
        dialog.setOnShowListener {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextAppearance(R.style.DangerText)
        }
        dialog.show()
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        endActionModeAndRedraw()
    }

    private fun beginActionMode(subscription: Subscription) {
        actionMode = startActionMode(this)
        adapter.toggleSelection(subscription.id)

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
        val fromColor = ContextCompat.getColor(this, Colors.statusBarNormal(this))
        val toColor = ContextCompat.getColor(this, Colors.statusBarActionMode(this))
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
        val fromColor = ContextCompat.getColor(this, Colors.statusBarActionMode(this))
        val toColor = ContextCompat.getColor(this, Colors.statusBarNormal(this))
        fadeStatusBarColor(window, fromColor, toColor)
    }

    private fun redrawList() {
        if (!this::mainList.isInitialized) {
            return
        }
        adapter.notifyItemRangeChanged(0, adapter.currentList.size)
    }

    companion object {
        const val TAG = "NtfyMainActivity"
        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        const val EXTRA_SUBSCRIPTION_BASE_URL = "subscriptionBaseUrl"
        const val EXTRA_SUBSCRIPTION_TOPIC = "subscriptionTopic"
        const val EXTRA_SUBSCRIPTION_DISPLAY_NAME = "subscriptionDisplayName"
        const val EXTRA_SUBSCRIPTION_INSTANT = "subscriptionInstant"
        const val EXTRA_SUBSCRIPTION_MUTED_UNTIL = "subscriptionMutedUntil"
        const val ANIMATION_DURATION = 80L
        const val ONE_DAY_MILLIS = 86400000L

        // As per documentation: The minimum repeat interval that can be defined is 15 minutes
        // (same as the JobScheduler API), but in practice 15 doesn't work. Using 16 here.
        // Thanks to varunon9 (https://gist.github.com/varunon9/f2beec0a743c96708eb0ef971a9ff9cd) for this!

        const val POLL_WORKER_INTERVAL_MINUTES = 60L
        const val DELETE_WORKER_INTERVAL_MINUTES = 8 * 60L
        const val SERVICE_START_WORKER_INTERVAL_MINUTES = 3 * 60L
    }
}
