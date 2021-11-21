package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.topicShortUrl
import io.heckel.ntfy.data.topicUrl
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.atomic.AtomicLong

class DetailActivity : AppCompatActivity(), ActionMode.Callback, NotificationFragment.NotificationSettingsListener {
    private val viewModel by viewModels<DetailViewModel> {
        DetailViewModelFactory((application as Application).repository)
    }
    private val repository by lazy { (application as Application).repository }
    private val api = ApiService()
    private var subscriberManager: SubscriberManager? = null // Context-dependent
    private var notifier: NotificationService? = null // Context-dependent

    // Which subscription are we looking at
    private var subscriptionId: Long = 0L // Set in onCreate()
    private var subscriptionBaseUrl: String = "" // Set in onCreate()
    private var subscriptionTopic: String = "" // Set in onCreate()
    private var subscriptionInstant: Boolean = false // Set in onCreate() & updated by options menu!

    // UI elements
    private lateinit var adapter: DetailAdapter
    private lateinit var mainList: RecyclerView
    private lateinit var mainListContainer: SwipeRefreshLayout
    private lateinit var menu: Menu

    // Action mode stuff
    private var actionMode: ActionMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.detail_activity)

        Log.d(MainActivity.TAG, "Create $this")

        // Dependencies that depend on Context
        subscriberManager = SubscriberManager(this)
        notifier = NotificationService(this)

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Get extras required for the return to the main activity
        subscriptionId = intent.getLongExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, 0)
        subscriptionBaseUrl = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL) ?: return
        subscriptionTopic = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC) ?: return
        subscriptionInstant = intent.getBooleanExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, false)

        // Set title
        val subscriptionBaseUrl = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL) ?: return
        val topicUrl = topicShortUrl(subscriptionBaseUrl, subscriptionTopic)
        title = topicUrl

        // Set "how to instructions"
        val howToExample: TextView = findViewById(R.id.detail_how_to_example)
        howToExample.linksClickable = true

        val howToText = getString(R.string.detail_how_to_example, topicUrl)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            howToExample.text = Html.fromHtml(howToText, Html.FROM_HTML_MODE_LEGACY)
        } else {
            howToExample.text = Html.fromHtml(howToText)
        }

        // Swipe to refresh
        mainListContainer = findViewById(R.id.detail_notification_list_container)
        mainListContainer.setOnRefreshListener { refresh() }
        mainListContainer.setColorSchemeResources(R.color.primaryColor)

        // Update main list based on viewModel (& its datasource/livedata)
        val noEntriesText: View = findViewById(R.id.detail_no_notifications)
        val onNotificationClick = { n: Notification -> onNotificationClick(n) }
        val onNotificationLongClick = { n: Notification -> onNotificationLongClick(n) }

        adapter = DetailAdapter(onNotificationClick, onNotificationLongClick)
        mainList = findViewById(R.id.detail_notification_list)
        mainList.adapter = adapter

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

        // Scroll up when new notification is added
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    Log.d(TAG, "$itemCount item(s) inserted at $positionStart, scrolling to the top")
                    mainList.scrollToPosition(positionStart)
                }
            }
        })

        // React to changes in fast delivery setting
        repository.getSubscriptionIdsWithInstantStatusLiveData().observe(this) {
            subscriberManager?.refreshService(it)
        }

        // Mark this subscription as "open" so we don't receive notifications for it
        Log.d(TAG, "onCreate hook: Marking subscription $subscriptionId as 'open'")
        repository.detailViewSubscriptionId.set(subscriptionId)
    }

    override fun onResume() {
        super.onResume()

        Log.d(TAG, "onResume hook: Marking subscription $subscriptionId as 'open'")
        repository.detailViewSubscriptionId.set(subscriptionId) // Mark as "open" so we don't send notifications while this is open
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onResume hook: Marking subscription $subscriptionId as 'not open'")
        repository.detailViewSubscriptionId.set(0) // Mark as closed
    }

    override fun onDestroy() {
        repository.detailViewSubscriptionId.set(0) // Mark as closed
        Log.d(TAG, "onDestroy hook: Marking subscription $subscriptionId as 'not open'")
        super.onDestroy()
    }

    private fun maybeCancelNotificationPopups(notifications: List<Notification>) {
        val notificationsWithPopups = notifications.filter { notification -> notification.notificationId != 0 }
        if (notificationsWithPopups.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                notificationsWithPopups.forEach { notification ->
                    notifier?.cancel(notification)
                    repository.updateNotification(notification.copy(notificationId = 0))
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.detail_action_bar_menu, menu)
        this.menu = menu
        showHideInstantMenuItems(subscriptionInstant)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.detail_menu_test -> {
                onTestClick()
                true
            }
            R.id.detail_menu_notification -> {
                onNotificationSettingsClick()
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
            R.id.detail_menu_instant_info -> {
                onInstantInfoClick()
                true
            }
            R.id.detail_menu_copy_url -> {
                onCopyUrlClick()
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
                val message = getString(R.string.detail_test_message, Date().toString())
                api.publish(subscriptionBaseUrl, subscriptionTopic, message)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast
                        .makeText(this@DetailActivity, getString(R.string.detail_test_message_error, e.message), Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private fun onNotificationSettingsClick() {
        Log.d(TAG, "Showing notification settings dialog for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")
        val intent = Intent(this, SubscriptionSettingsActivity::class.java)
        startActivityForResult(intent, /*XXXXXX*/MainActivity.REQUEST_CODE_DELETE_SUBSCRIPTION)
/*
        val notificationFragment = NotificationFragment()
        notificationFragment.show(supportFragmentManager, NotificationFragment.TAG)*/
    }

    override fun onNotificationSettingsChanged(mutedUntil: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val subscription = repository.getSubscription(subscriptionId)
            val newSubscription = subscription?.copy(mutedUntil = mutedUntil)
            newSubscription?.let { repository.updateSubscription(newSubscription) }
            runOnUiThread {
                when (mutedUntil) {
                    0L -> Toast.makeText(this@DetailActivity, getString(R.string.notification_dialog_enabled_toast_message), Toast.LENGTH_SHORT).show()
                    1L -> Toast.makeText(this@DetailActivity, getString(R.string.notification_dialog_muted_forever_toast_message), Toast.LENGTH_SHORT).show()
                    else -> {
                        val mutedUntilDate = Date(mutedUntil).toString()
                        Toast.makeText(
                            this@DetailActivity,
                            getString(R.string.notification_dialog_muted_until_toast_message, mutedUntilDate),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    }

    private fun onCopyUrlClick() {
        val url = topicUrl(subscriptionBaseUrl, subscriptionTopic)
        Log.d(TAG, "Copying topic URL $url to clipboard ")

        runOnUiThread {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("topic address", url)
            clipboard.setPrimaryClip(clip)
            Toast
                .makeText(this, getString(R.string.detail_copied_to_clipboard_message), Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun refresh() {
        Log.d(TAG, "Fetching cached notifications for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val notifications = api.poll(subscriptionId, subscriptionBaseUrl, subscriptionTopic)
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
                        .makeText(this@DetailActivity, getString(R.string.refresh_message_error, e.message), Toast.LENGTH_LONG)
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

    private fun onInstantInfoClick() {
        Log.d(TAG, "Showing instant info toast")
        Toast.makeText(this@DetailActivity, getString(R.string.detail_instant_info), Toast.LENGTH_LONG)
            .show()
    }

    private fun showHideInstantMenuItems(enable: Boolean) {
        subscriptionInstant = enable
        runOnUiThread {
            val appBaseUrl = getString(R.string.app_base_url)
            val enableInstantItem = menu.findItem(R.id.detail_menu_enable_instant)
            val disableInstantItem = menu.findItem(R.id.detail_menu_disable_instant)
            val instantInfoItem = menu.findItem(R.id.detail_menu_instant_info)
            if (subscriptionBaseUrl == appBaseUrl) {
                enableInstantItem?.isVisible = !subscriptionInstant
                disableInstantItem?.isVisible = subscriptionInstant
                instantInfoItem?.isVisible = false
            } else {
                enableInstantItem?.isVisible = false
                disableInstantItem?.isVisible = false
                instantInfoItem?.isVisible = true
            }
        }
    }

    private fun onDeleteClick() {
        Log.d(TAG, "Deleting subscription ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        val builder = AlertDialog.Builder(this)
        builder
            .setMessage(R.string.detail_delete_dialog_message)
            .setPositiveButton(R.string.detail_delete_dialog_permanently_delete) { _, _ ->
                // Return to main activity
                val result = Intent()
                    .putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
                    .putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscriptionBaseUrl)
                    .putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscriptionTopic)
                    .putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, subscriptionInstant)
                setResult(RESULT_OK, result)
                finish()

                // The deletion will be done in MainActivity.onResult
            }
            .setNegativeButton(R.string.detail_delete_dialog_cancel) { _, _ -> /* Do nothing */ }
            .create()
            .show()
    }

    private fun onNotificationClick(notification: Notification) {
        if (actionMode != null) {
            handleActionModeClick(notification)
        } else {
            copyToClipboard(notification)
        }
    }

    private fun copyToClipboard(notification: Notification) {
        runOnUiThread {
            val message = notification.message + "\n\n" + Date(notification.timestamp * 1000).toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("notification message", message)
            clipboard.setPrimaryClip(clip)
            Toast
                .makeText(this, getString(R.string.detail_copied_to_clipboard_message), Toast.LENGTH_LONG)
                .show()
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
            redrawList()
        }
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        this.actionMode = mode
        if (mode != null) {
            mode.menuInflater.inflate(R.menu.detail_action_mode_menu, menu)
            mode.title = "1" // One item selected
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        return false
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when (item?.itemId) {
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

    private fun onMultiCopyClick() {
        Log.d(TAG, "Copying multiple notifications to clipboard")

        lifecycleScope.launch(Dispatchers.IO) {
            val content = adapter.selected.joinToString("\n\n") { notificationId ->
                val notification = repository.getNotification(notificationId)
                notification?.let {
                    it.message + "\n" + Date(it.timestamp * 1000).toString()
                }.orEmpty()
            }
            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("notifications", content)
                clipboard.setPrimaryClip(clip)
                Toast
                    .makeText(this@DetailActivity, getString(R.string.detail_copied_to_clipboard_message), Toast.LENGTH_LONG)
                    .show()
                finishActionMode()
            }
        }
    }

    private fun onMultiDeleteClick() {
        Log.d(TAG, "Showing multi-delete dialog for selected items")

        val builder = AlertDialog.Builder(this)
        builder
            .setMessage(R.string.detail_action_mode_delete_dialog_message)
            .setPositiveButton(R.string.detail_action_mode_delete_dialog_permanently_delete) { _, _ ->
                adapter.selected.map { notificationId -> viewModel.remove(notificationId) }
                finishActionMode()
            }
            .setNegativeButton(R.string.detail_action_mode_delete_dialog_cancel) { _, _ ->
                finishActionMode()
            }
            .create()
            .show()
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        endActionModeAndRedraw()
    }

    private fun beginActionMode(notification: Notification) {
        actionMode = startActionMode(this)
        adapter.selected.add(notification.id)
        redrawList()

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

        // Fade status bar color
        val fromColor = ContextCompat.getColor(this, R.color.primaryDarkColor)
        val toColor = ContextCompat.getColor(this, R.color.primaryColor)
        fadeStatusBarColor(window, fromColor, toColor)
    }

    private fun redrawList() {
        mainList.adapter = adapter // Oh, what a hack ...
    }

    companion object {
        const val TAG = "NtfyDetailActivity"
    }
}
