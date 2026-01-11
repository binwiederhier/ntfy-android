package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.firebase.FirebaseMessenger
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.copyToClipboard
import io.heckel.ntfy.util.dangerButton
import io.heckel.ntfy.util.decodeMessage
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.formatDateShort
import io.heckel.ntfy.util.isDarkThemeOn
import io.heckel.ntfy.util.randomSubscriptionId
import io.heckel.ntfy.util.topicShortUrl
import io.heckel.ntfy.util.topicUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.random.Random
import androidx.core.view.size
import androidx.core.view.get
import androidx.core.net.toUri
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import android.widget.ImageButton

class DetailActivity : AppCompatActivity(), NotificationFragment.NotificationSettingsListener, PublishFragment.PublishListener {
    private val viewModel by viewModels<DetailViewModel> {
        DetailViewModelFactory((application as Application).repository)
    }
    private val repository by lazy { (application as Application).repository }
    private val api by lazy { ApiService(this) }
    private val messenger = FirebaseMessenger()
    private var notifier: NotificationService? = null // Context-dependent
    private var appBaseUrl: String? = null // Context-dependent

    // Which subscription are we looking at
    private var subscriptionId: Long = 0L // Set in onCreate()
    private var subscriptionBaseUrl: String = "" // Set in onCreate()
    private var subscriptionTopic: String = "" // Set in onCreate()
    private var subscriptionDisplayName: String = "" // Set in onCreate() & updated by options menu!
    private var subscriptionInstant: Boolean = false // Set in onCreate() & updated by options menu!
    private var subscriptionMutedUntil: Long = 0L // Set in onCreate() & updated by options menu!

    // UI elements
    private lateinit var adapter: DetailAdapter
    private lateinit var mainList: RecyclerView
    private lateinit var mainListContainer: SwipeRefreshLayout
    private lateinit var menu: Menu
    private lateinit var fab: FloatingActionButton
    private lateinit var messageBar: View
    private lateinit var messageBarText: TextInputEditText
    private lateinit var messageBarPublishButton: FloatingActionButton
    private lateinit var messageBarExpandButton: ImageButton

    // Action mode stuff
    private var actionMode: ActionMode? = null
    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            actionMode = mode
            if (mode != null) {
                mode.menuInflater.inflate(R.menu.menu_detail_action_mode, menu)
                mode.title = "1" // One item selected
            }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.detail_action_mode_copy -> {
                    onMultiCopyClick()
                    true
                }
                R.id.detail_action_mode_delete -> {
                    onMultiDeleteClick()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            endActionModeAndRedraw()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        Log.d(TAG, "Create $this")

        // Dependencies that depend on Context
        notifier = NotificationService(this)
        appBaseUrl = getString(R.string.app_base_url)

        val toolbarLayout = findViewById<View>(R.id.app_bar_drawer)
        val dynamicColors = repository.getDynamicColorsEnabled()
        val darkMode = isDarkThemeOn(this)
        val statusBarColor = Colors.statusBarNormal(this, dynamicColors, darkMode)
        val toolbarTextColor = Colors.toolbarTextColor(this, dynamicColors, darkMode)
        toolbarLayout.setBackgroundColor(statusBarColor)
        
        val toolbar = toolbarLayout.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setTitleTextColor(toolbarTextColor)
        toolbar.setNavigationIconTint(toolbarTextColor)
        toolbar.overflowIcon?.setTint(toolbarTextColor)
        setSupportActionBar(toolbar)
        
        // Set system status bar appearance
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            Colors.shouldUseLightStatusBar(dynamicColors, darkMode)

        // Set detail activity background: use theme background for dynamic colors, static gray for non-dynamic
        val detailContentLayout = findViewById<View>(R.id.detail_content_layout)
        if (repository.getDynamicColorsEnabled()) {
            detailContentLayout.setBackgroundColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this,
                    android.R.attr.colorBackground,
                    ContextCompat.getColor(this, R.color.detail_activity_background)
                )
            )
        } else {
            detailContentLayout.setBackgroundColor(
                ContextCompat.getColor(this, R.color.detail_activity_background)
            )
        }

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Hide links that lead to payments, see https://github.com/binwiederhier/ntfy/issues/1463
        val howToLink = findViewById<TextView>(R.id.detail_how_to_link)
        howToLink.isVisible = BuildConfig.PAYMENT_LINKS_AVAILABLE

        // Handle direct deep links to topic "ntfy://..."
        val url = intent?.data
        if (intent?.action == ACTION_VIEW && url != null) {
            maybeSubscribeAndLoadView(url)
        } else {
            loadView()
        }
    }

    private fun maybeSubscribeAndLoadView(url: Uri) {
        if (url.pathSegments.size != 1) {
            Log.w(TAG, "Invalid link $url. Aborting.")
            finish()
            return
        }
        val secure = url.getBooleanQueryParameter("secure", true) // Default to https://
        val displayName = url.getQueryParameter("display")
        val baseUrl = extractBaseUrl(url, secure)
        val topic = url.pathSegments.first()

        title = topicShortUrl(baseUrl, topic)

        // Subscribe to topic if it doesn't already exist
        lifecycleScope.launch(Dispatchers.IO) {
            var subscription = repository.getSubscription(baseUrl, topic)
            if (subscription == null) {
                val instant = baseUrl != appBaseUrl
                subscription = Subscription(
                    id = randomSubscriptionId(),
                    baseUrl = baseUrl,
                    topic = topic,
                    instant = instant,
                    dedicatedChannels = false,
                    mutedUntil = 0,
                    minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
                    autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
                    insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
                    lastNotificationId = null,
                    icon = null,
                    upAppId = null,
                    upConnectorToken = null,
                    displayName = displayName,
                    totalCount = 0,
                    newCount = 0,
                    lastActive = Date().time/1000
                )
                repository.addSubscription(subscription)

                // Subscribe to Firebase topic if ntfy.sh (even if instant, just to be sure!)
                if (baseUrl == appBaseUrl) {
                    Log.d(TAG, "Subscribing to Firebase topic $topic")
                    messenger.subscribe(topic)
                }

                // Fetch cached messages
                try {
                    val user = repository.getUser(subscription.baseUrl) // May be null
                    val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic, user)
                    notifications.forEach { notification -> repository.addNotification(notification) }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to fetch notifications: ${e.message}", e)
                }

                runOnUiThread {
                    val message = getString(R.string.detail_deep_link_subscribed_toast_message, topicShortUrl(baseUrl, topic))
                    Toast.makeText(this@DetailActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            // Add extras needed in loadView(); normally these are added in MainActivity
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(appBaseUrl, subscription))
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)

            runOnUiThread {
                loadView()
            }
        }
    }

    fun extractBaseUrl(url: Uri, secure: Boolean): String {
        if (secure) {
            return if (url.port != 443 && url.port != -1) "https://${url.host}:${url.port}" else "https://${url.host}"
        }
        return if (url.port != 80 && url.port != -1)  "http://${url.host}:${url.port}" else "http://${url.host}"
    }

    private fun loadView() {
        // Get extras required for the return to the main activity
        subscriptionId = intent.getLongExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, 0)
        subscriptionBaseUrl = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL) ?: return
        subscriptionTopic = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC) ?: return
        subscriptionDisplayName = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME) ?: return
        subscriptionInstant = intent.getBooleanExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, false)
        subscriptionMutedUntil = intent.getLongExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, 0L)

        // Set title
        val subscriptionBaseUrl = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL) ?: return
        val topicUrl = topicShortUrl(subscriptionBaseUrl, subscriptionTopic)
        title = subscriptionDisplayName

        // Set "how to instructions"
        val howToExample: TextView = findViewById(R.id.detail_how_to_example)
        howToExample.linksClickable = true

        val howToText = getString(R.string.detail_how_to_example, topicUrl)
        howToExample.text = Html.fromHtml(howToText, Html.FROM_HTML_MODE_LEGACY)

        // Swipe to refresh
        mainListContainer = findViewById(R.id.detail_notification_list_container)
        mainListContainer.setOnRefreshListener { refresh() }
        mainListContainer.setColorSchemeColors(Colors.swipeToRefreshColor(this))

        // Update main list based on viewModel (& its datasource/livedata)
        val noEntriesText: View = findViewById(R.id.detail_no_notifications)
        val onNotificationClick = { n: Notification -> onNotificationClick(n) }
        val onNotificationLongClick = { n: Notification -> onNotificationLongClick(n) }

        adapter = DetailAdapter(this, lifecycleScope, repository, onNotificationClick, onNotificationLongClick)
        mainList = findViewById(R.id.detail_notification_list)
        mainList.adapter = adapter
        
        // Apply window insets to ensure content is not covered by navigation bar
        mainList.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(mainList) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = systemBars.bottom)
            insets
        }

        viewModel.list(subscriptionId).observe(this) {
            it?.let {
                // Show list view
                adapter.submitList(it as MutableList<Notification>)
                if (it.isEmpty()) {
                    mainListContainer.visibility = View.GONE
                    noEntriesText.visibility = View.VISIBLE
                } else {
                    mainListContainer.visibility = View.VISIBLE
                    noEntriesText.visibility = View.GONE
                }

                // Cancel notifications that still have popups
                maybeCancelNotificationPopups(it)
            }
        }

        // Swipe to remove
        val itemTouchCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return false
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, swipeDir: Int) {
                val notification = adapter.get(viewHolder.absoluteAdapterPosition)
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.markAsDeleted(notification.id)
                }
                val snackbar = Snackbar.make(mainList, R.string.detail_item_snack_deleted, Snackbar.LENGTH_SHORT)
                snackbar.setAction(R.string.detail_item_snack_undo) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        repository.undeleteNotification(notification.id)
                    }
                }
                snackbar.show()
            }
        }
        val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(mainList)

        // Scroll up when new notification is added
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    Log.d(TAG, "$itemCount item(s) inserted at 0, scrolling to the top")
                    mainList.scrollToPosition(positionStart)
                }
            }
        })

        // React to changes in fast delivery setting
        repository.getSubscriptionIdsWithInstantStatusLiveData().observe(this) {
            SubscriberServiceManager.refresh(this)
        }

        // Observe connection errors and update menu item visibility
        repository.getConnectionErrorsLiveData().observe(this) { errors ->
            showHideConnectionErrorMenuItem(errors)
        }

        // Mark this subscription as "open" so we don't receive notifications for it
        repository.detailViewSubscriptionId.set(subscriptionId)

        // Stop insistent playback (if running, otherwise it'll throw)
        try {
            repository.mediaPlayer.stop()
        } catch (_: Exception) {
            // Ignore errors
        }

        // Setup FAB and message bar
        setupPublishUI()
    }

    private fun setupPublishUI() {
        fab = findViewById(R.id.detail_fab)
        messageBar = findViewById(R.id.detail_message_bar)
        messageBarText = messageBar.findViewById(R.id.message_bar_text)
        messageBarPublishButton = messageBar.findViewById(R.id.message_bar_publish_button)
        messageBarExpandButton = messageBar.findViewById(R.id.message_bar_expand_button)

        // Message bar enabled: Show message bar, hide FAB
        if (repository.getMessageBarEnabled()) {
            fab.visibility = View.GONE
            messageBar.visibility = View.VISIBLE

            // Send button click
            messageBarPublishButton.setOnClickListener {
                publishMessage(messageBarText.text.toString()) // Allow publishing empty messages
            }

            // Expand button click opens the full dialog
            messageBarExpandButton.setOnClickListener {
                openPublishDialog(messageBarText.text.toString())
            }

            // Handle window insets for navigation bar and keyboard
            val contentLayout = findViewById<View>(R.id.detail_content_layout)
            ViewCompat.setOnApplyWindowInsetsListener(contentLayout) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                // Use the larger of navigation bar or keyboard height
                val bottomPadding = maxOf(systemBars.bottom, ime.bottom)
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomPadding)
                insets
            }
        } else {
            // Show FAB, hide message bar
            fab.visibility = View.VISIBLE
            messageBar.visibility = View.GONE

            fab.setOnClickListener {
                openPublishDialog("")
            }

            // Add bottom padding to FAB to account for navigation bar
            ViewCompat.setOnApplyWindowInsetsListener(fab) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val layoutParams = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
                layoutParams.bottomMargin = systemBars.bottom + resources.getDimensionPixelSize(R.dimen.fab_margin)
                view.layoutParams = layoutParams
                insets
            }
        }
    }

    private fun openPublishDialog(initialMessage: String) {
        val fragment = PublishFragment.newInstance(subscriptionBaseUrl, subscriptionTopic, subscriptionDisplayName, initialMessage)
        fragment.show(supportFragmentManager, PublishFragment.TAG)
    }

    private fun publishMessage(message: String) {
        // Disable send button while publishing
        messageBarPublishButton.isEnabled = false
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = repository.getUser(subscriptionBaseUrl)
                api.publish(
                    baseUrl = subscriptionBaseUrl,
                    topic = subscriptionTopic,
                    user = user,
                    message = message,
                    title = "",
                    priority = 3, // Default priority
                    tags = emptyList(),
                    delay = ""
                )
                runOnUiThread {
                    messageBarText.text?.clear()
                    messageBarPublishButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to publish message", e)
                runOnUiThread {
                    messageBarPublishButton.isEnabled = true
                    val errorMessage = when (e) {
                        is ApiService.UnauthorizedException -> {
                            if (e.user != null) {
                                getString(R.string.detail_test_message_error_unauthorized_user, e.user.username)
                            } else {
                                getString(R.string.detail_test_message_error_unauthorized_anon)
                            }
                        }
                        is ApiService.EntityTooLargeException -> {
                            getString(R.string.detail_test_message_error_too_large)
                        }
                        is ApiService.ApiException -> {
                            getString(R.string.publish_dialog_error_server, e.error, e.code)
                        }
                        else -> {
                            getString(R.string.publish_dialog_error_sending, e.message)
                        }
                    }
                    Toast.makeText(this@DetailActivity, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Called by the publish dialog (PublishFragment) after the notification
     * was successfully published.
     */
    override fun onPublished() {
        // Clear the message bar text when a message is published from the dialog
        if (this::messageBarText.isInitialized) {
            messageBarText.text?.clear()
        }
    }

    override fun onResume() {
        super.onResume()

        // Mark as "open" so we don't send notifications while this is open
        repository.detailViewSubscriptionId.set(subscriptionId)

        // Update buttons (this is for when we return from the preferences screen)
        lifecycleScope.launch(Dispatchers.IO) {
            val subscription = repository.getSubscription(subscriptionId) ?: return@launch
            subscriptionInstant = subscription.instant
            subscriptionMutedUntil = subscription.mutedUntil
            subscriptionDisplayName = displayName(appBaseUrl, subscription)

            showHideInstantMenuItems(subscriptionInstant)
            showHideMutedUntilMenuItems(subscriptionMutedUntil)
            showHideCopyMenuItems(subscription.baseUrl)
            showHideConnectionErrorMenuItem(repository.getConnectionErrors())
            updateTitle(subscriptionDisplayName)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause hook: Removing 'notificationId' from all notifications for $subscriptionId")
        GlobalScope.launch(Dispatchers.IO) {
            // Note: This is here and not in onDestroy/onStop, because we want to clear notifications as early
            // as possible, so that we don't see the "new" bubble in the main list anymore.
            repository.clearAllNotificationIds(subscriptionId)
        }
        Log.d(TAG, "onPause hook: Marking subscription $subscriptionId as 'not open'")
        repository.detailViewSubscriptionId.set(0) // Mark as closed
    }

    private fun maybeCancelNotificationPopups(notifications: List<Notification>) {
        val notificationsWithPopups = notifications.filter { notification -> notification.notificationId != 0 }
        if (notificationsWithPopups.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                notificationsWithPopups.forEach { notification ->
                    notifier?.cancel(notification)
                    // Do NOT remove the notificationId here, we need that for the UI indicators; we'll remove it in onPause()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail_action_bar, menu)
        this.menu = menu
        
        // Tint menu icons based on theme
        val toolbarTextColor = Colors.toolbarTextColor(this, repository.getDynamicColorsEnabled(), isDarkThemeOn(this))
        for (i in 0 until menu.size) {
            menu[i].icon?.setTint(toolbarTextColor)
        }

        // Show and hide buttons
        showHideInstantMenuItems(subscriptionInstant)
        showHideMutedUntilMenuItems(subscriptionMutedUntil)
        showHideCopyMenuItems(subscriptionBaseUrl)
        showHideConnectionErrorMenuItem(repository.getConnectionErrors())

        // Regularly check if "notification muted" time has passed
        // NOTE: This is done here, because then we know that we've initialized the menu items.
        startNotificationMutedChecker()

        return true
    }

    private fun startNotificationMutedChecker() {
        // FIXME This is awful and has to go.

        lifecycleScope.launch(Dispatchers.IO) {
            delay(1000) // Just to be sure we've initialized all the things, we wait a bit ...
            while (isActive) {
                Log.d(TAG, "Checking 'muted until' timestamp for subscription $subscriptionId")
                val subscription = repository.getSubscription(subscriptionId) ?: return@launch
                val mutedUntilExpired = subscription.mutedUntil > 1L && System.currentTimeMillis()/1000 > subscription.mutedUntil
                if (mutedUntilExpired) {
                    val newSubscription = subscription.copy(mutedUntil = 0L)
                    repository.updateSubscription(newSubscription)
                    showHideMutedUntilMenuItems(0L)
                }
                delay(60_000)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.detail_menu_test -> {
                onTestClick()
                true
            }
            R.id.detail_menu_notifications_enabled -> {
                onMutedUntilClick(enable = false)
                true
            }
            R.id.detail_menu_notifications_disabled_until -> {
                onMutedUntilClick(enable = true)
                true
            }
            R.id.detail_menu_notifications_disabled_forever -> {
                onMutedUntilClick(enable = true)
                true
            }
            R.id.detail_menu_enable_instant -> {
                onInstantEnableClick(enable = true)
                true
            }
            R.id.detail_menu_disable_instant -> {
                onInstantEnableClick(enable = false)
                true
            }
            R.id.detail_menu_connection_error -> {
                onConnectionErrorClick()
                true
            }
            R.id.detail_menu_copy_url -> {
                onCopyUrlClick()
                true
            }
            R.id.detail_menu_clear -> {
                onClearClick()
                true
            }
            R.id.detail_menu_settings -> {
                onSettingsClick()
                true
            }
            R.id.detail_menu_unsubscribe -> {
                onDeleteClick()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onTestClick() {
        Log.d(TAG, "Sending test notification to ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = repository.getUser(subscriptionBaseUrl) // May be null
                val possibleTags = listOf(
                    "warning", "skull", "success", "triangular_flag_on_post", "de",  "dog", "rotating_light", "cat", "bike", // Emojis
                    "backup", "rsync", "de-server1", "this-is-a-tag"
                )
                val priority = Random.nextInt(1, 6)
                val tags = possibleTags.shuffled().take(Random.nextInt(0, 4))
                val title = if (Random.nextBoolean()) getString(R.string.detail_test_title) else ""
                val message = getString(R.string.detail_test_message, priority)
                api.publish(subscriptionBaseUrl, subscriptionTopic, user, message, title, priority, tags, delay = "")
            } catch (e: Exception) {
                runOnUiThread {
                    val message = if (e is ApiService.UnauthorizedException) {
                        if (e.user != null) {
                            getString(R.string.detail_test_message_error_unauthorized_user, e.user.username)
                        }  else {
                            getString(R.string.detail_test_message_error_unauthorized_anon)
                        }
                    } else {
                        getString(R.string.detail_test_message_error, e.message)
                    }
                    Toast
                        .makeText(this@DetailActivity, message, Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private fun onMutedUntilClick(enable: Boolean) {
        if (!enable) {
            Log.d(TAG, "Showing notification settings dialog for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")
            val notificationFragment = NotificationFragment()
            notificationFragment.show(supportFragmentManager, NotificationFragment.TAG)
        } else {
            Log.d(TAG, "Re-enabling notifications ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")
            onNotificationMutedUntilChanged(Repository.MUTED_UNTIL_SHOW_ALL)
        }
    }

    private fun onConnectionErrorClick() {
        Log.d(TAG, "Showing connection error dialog for ${subscriptionBaseUrl}")
        val connectionErrorFragment = ConnectionErrorFragment.newInstance(subscriptionBaseUrl)
        connectionErrorFragment.show(supportFragmentManager, ConnectionErrorFragment.TAG)
    }

    override fun onNotificationMutedUntilChanged(mutedUntilTimestamp: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Setting subscription 'muted until' to $mutedUntilTimestamp")
            val subscription = repository.getSubscription(subscriptionId)
            val newSubscription = subscription?.copy(mutedUntil = mutedUntilTimestamp)
            newSubscription?.let { repository.updateSubscription(newSubscription) }
            subscriptionMutedUntil = mutedUntilTimestamp
            showHideMutedUntilMenuItems(mutedUntilTimestamp)
            runOnUiThread {
                when (mutedUntilTimestamp) {
                    0L -> Toast.makeText(this@DetailActivity, getString(R.string.notification_dialog_enabled_toast_message), Toast.LENGTH_LONG).show()
                    1L -> Toast.makeText(this@DetailActivity, getString(R.string.notification_dialog_muted_forever_toast_message), Toast.LENGTH_LONG).show()
                    else -> {
                        val formattedDate = formatDateShort(mutedUntilTimestamp)
                        Toast.makeText(this@DetailActivity, getString(R.string.notification_dialog_muted_until_toast_message, formattedDate), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun onCopyUrlClick() {
        val url = topicUrl(subscriptionBaseUrl, subscriptionTopic)
        Log.d(TAG, "Copying topic URL $url to clipboard ")

        runOnUiThread {
            copyToClipboard(this, "topic address", url)
        }
    }

    private fun refresh() {
        Log.d(TAG, "Fetching cached notifications for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val subscription = repository.getSubscription(subscriptionId) ?: return@launch
                val user = repository.getUser(subscription.baseUrl) // May be null
                val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic, user, subscription.lastNotificationId)
                val newNotifications = repository.onlyNewNotifications(subscriptionId, notifications)
                val toastMessage = if (newNotifications.isEmpty()) {
                    getString(R.string.refresh_message_no_results)
                } else {
                    getString(R.string.refresh_message_result, newNotifications.size)
                }
                newNotifications.forEach { notification -> repository.addNotification(notification) }
                runOnUiThread {
                    Toast.makeText(this@DetailActivity, toastMessage, Toast.LENGTH_LONG).show()
                    mainListContainer.isRefreshing = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching notifications for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}: ${e.stackTrace}", e)
                runOnUiThread {
                    Toast
                        .makeText(this@DetailActivity, getString(R.string.refresh_message_error_one, e.message), Toast.LENGTH_LONG)
                        .show()
                    mainListContainer.isRefreshing = false
                }
            }
        }
    }

    private fun onInstantEnableClick(enable: Boolean) {
        Log.d(TAG, "Toggling instant delivery setting for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        lifecycleScope.launch(Dispatchers.IO) {
            val subscription = repository.getSubscription(subscriptionId)
            val newSubscription = subscription?.copy(instant = enable)
            newSubscription?.let { repository.updateSubscription(newSubscription) }
            showHideInstantMenuItems(enable)
            runOnUiThread {
                if (enable) {
                    Toast.makeText(this@DetailActivity, getString(R.string.detail_instant_delivery_enabled), Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(this@DetailActivity, getString(R.string.detail_instant_delivery_disabled), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun showHideInstantMenuItems(enable: Boolean) {
        if (!this::menu.isInitialized) {
            return
        }
        subscriptionInstant = enable
        runOnUiThread {
            val appBaseUrl = getString(R.string.app_base_url)
            val enableInstantItem = menu.findItem(R.id.detail_menu_enable_instant)
            val disableInstantItem = menu.findItem(R.id.detail_menu_disable_instant)
            val allowToggleInstant = BuildConfig.FIREBASE_AVAILABLE && subscriptionBaseUrl == appBaseUrl
            if (allowToggleInstant) {
                enableInstantItem?.isVisible = !subscriptionInstant
                disableInstantItem?.isVisible = subscriptionInstant
            } else {
                enableInstantItem?.isVisible = false
                disableInstantItem?.isVisible = false
            }
        }
    }

    private fun showHideMutedUntilMenuItems(mutedUntilTimestamp: Long) {
        if (!this::menu.isInitialized) {
            return
        }
        subscriptionMutedUntil = mutedUntilTimestamp
        runOnUiThread {
            val notificationsEnabledItem = menu.findItem(R.id.detail_menu_notifications_enabled)
            val notificationsDisabledUntilItem = menu.findItem(R.id.detail_menu_notifications_disabled_until)
            val notificationsDisabledForeverItem = menu.findItem(R.id.detail_menu_notifications_disabled_forever)
            notificationsEnabledItem?.isVisible = subscriptionMutedUntil == 0L
            notificationsDisabledForeverItem?.isVisible = subscriptionMutedUntil == 1L
            notificationsDisabledUntilItem?.isVisible = subscriptionMutedUntil > 1L
            if (subscriptionMutedUntil > 1L) {
                val formattedDate = formatDateShort(subscriptionMutedUntil)
                notificationsDisabledUntilItem?.title = getString(R.string.detail_menu_notifications_disabled_until, formattedDate)
            }
        }
    }


    private fun showHideCopyMenuItems(subscriptionBaseUrl: String) {
        if (!this::menu.isInitialized) {
            return
        }
        runOnUiThread {
            // Hide links that lead to payments, see https://github.com/binwiederhier/ntfy/issues/1463
            val copyUrlItem = menu.findItem(R.id.detail_menu_copy_url)
            copyUrlItem?.isVisible = appBaseUrl != subscriptionBaseUrl || BuildConfig.PAYMENT_LINKS_AVAILABLE
        }
    }

    private fun showHideConnectionErrorMenuItem(errors: Map<String, io.heckel.ntfy.db.ConnectionError>) {
        if (!this::menu.isInitialized) {
            return
        }
        runOnUiThread {
            val connectionErrorItem = menu.findItem(R.id.detail_menu_connection_error)
            // Only show if there's an error for this subscription's base URL
            connectionErrorItem?.isVisible = errors.containsKey(subscriptionBaseUrl)
        }
    }

    private fun updateTitle(subscriptionDisplayName: String) {
        runOnUiThread {
            title = subscriptionDisplayName
        }
    }

    private fun onClearClick() {
        Log.d(TAG, "Clearing all notifications for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.detail_clear_dialog_message)
            .setPositiveButton(R.string.detail_clear_dialog_permanently_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.markAllAsDeleted(subscriptionId)
                }
            }
            .setNegativeButton(R.string.detail_clear_dialog_cancel) { _, _ -> /* Do nothing */ }
            .create()
        dialog.setOnShowListener {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .dangerButton()
        }
        dialog.show()
    }

    private fun onSettingsClick() {
        Log.d(TAG, "Opening subscription settings for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        val intent = Intent(this, DetailSettingsActivity::class.java)
        intent.putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
        intent.putExtra(EXTRA_SUBSCRIPTION_BASE_URL, subscriptionBaseUrl)
        intent.putExtra(EXTRA_SUBSCRIPTION_TOPIC, subscriptionTopic)
        intent.putExtra(EXTRA_SUBSCRIPTION_DISPLAY_NAME, subscriptionDisplayName)
        startActivity(intent)
    }

    private fun onDeleteClick() {
        Log.d(TAG, "Deleting subscription ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.detail_delete_dialog_message)
            .setPositiveButton(R.string.detail_delete_dialog_permanently_delete) { _, _ ->
                Log.d(TAG, "Deleting subscription with subscription ID $subscriptionId (topic: $subscriptionTopic)")
                GlobalScope.launch(Dispatchers.IO) {
                    repository.removeAllNotifications(subscriptionId)
                    repository.removeSubscription(subscriptionId)
                    if (subscriptionBaseUrl == appBaseUrl) {
                        messenger.unsubscribe(subscriptionTopic)
                    }
                }
                finish()
            }
            .setNegativeButton(R.string.detail_delete_dialog_cancel) { _, _ -> /* Do nothing */ }
            .create()
        dialog.setOnShowListener {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .dangerButton()
        }
        dialog.show()
    }

    private fun onNotificationClick(notification: Notification) {
        if (actionMode != null) {
            handleActionModeClick(notification)
        } else if (notification.click != "") {
            try {
                startActivity(Intent(ACTION_VIEW, notification.click.toUri()))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open click URL", e)
                runOnUiThread {
                    Toast
                        .makeText(this@DetailActivity, getString(R.string.detail_item_cannot_open_url, e.message), Toast.LENGTH_LONG)
                        .show()
                }
            }
        } else {
            runOnUiThread {
                copyToClipboard(this, "notification", decodeMessage(notification))
            }
        }
    }

    private fun onNotificationLongClick(notification: Notification) {
        if (actionMode == null) {
            beginActionMode(notification)
        }
    }

    private fun handleActionModeClick(notification: Notification) {
        adapter.toggleSelection(notification.id)
        if (adapter.selected.size == 0) {
            finishActionMode()
        } else {
            actionMode!!.title = adapter.selected.size.toString()
        }
    }

    private fun onMultiCopyClick() {
        Log.d(TAG, "Copying multiple notifications to clipboard")

        lifecycleScope.launch(Dispatchers.IO) {
            val content = adapter.selected.joinToString("\n\n") { notificationId ->
                val notification = repository.getNotification(notificationId)
                notification?.let {
                    decodeMessage(it) + "\n" + Date(it.timestamp * 1000).toString()
                }.orEmpty()
            }
            runOnUiThread {
                copyToClipboard(this@DetailActivity, "notifications", content)
                finishActionMode()
            }
        }
    }

    private fun onMultiDeleteClick() {
        Log.d(TAG, "Showing multi-delete dialog for selected items")

        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.detail_action_mode_delete_dialog_message)
            .setPositiveButton(R.string.detail_action_mode_delete_dialog_permanently_delete) { _, _ ->
                adapter.selected.map { notificationId -> viewModel.markAsDeleted(notificationId) }
                finishActionMode()
            }
            .setNegativeButton(R.string.detail_action_mode_delete_dialog_cancel) { _, _ ->
                finishActionMode()
            }
            .create()
        dialog.setOnShowListener {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .dangerButton()
        }
        dialog.show()
    }

    private fun beginActionMode(notification: Notification) {
        actionMode = startSupportActionMode(actionModeCallback)
        adapter.toggleSelection(notification.id)
    }

    private fun finishActionMode() {
        actionMode?.finish()
        endActionModeAndRedraw()
    }

    private fun endActionModeAndRedraw() {
        actionMode = null
        adapter.selected.clear()
        adapter.notifyItemRangeChanged(0, adapter.currentList.size)
    }

    companion object {
        const val TAG = "NtfyDetailActivity"
        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        const val EXTRA_SUBSCRIPTION_BASE_URL = "baseUrl"
        const val EXTRA_SUBSCRIPTION_TOPIC = "topic"
        const val EXTRA_SUBSCRIPTION_DISPLAY_NAME = "displayName"
    }
}
