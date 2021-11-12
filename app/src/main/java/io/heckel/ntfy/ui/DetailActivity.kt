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
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.topicShortUrl
import io.heckel.ntfy.msg.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class DetailActivity : AppCompatActivity(), ActionMode.Callback {
    private val viewModel by viewModels<DetailViewModel> {
        DetailViewModelFactory((application as Application).repository)
    }
    private val repository by lazy { (application as Application).repository }
    private val api = ApiService()

    // Which subscription are we looking at
    private var subscriptionId: Long = 0L // Set in onCreate()
    private var subscriptionBaseUrl: String = "" // Set in onCreate()
    private var subscriptionTopic: String = "" // Set in onCreate()

    // Action mode stuff
    private lateinit var mainList: RecyclerView
    private lateinit var adapter: DetailAdapter
    private var actionMode: ActionMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.detail_activity)

        Log.d(MainActivity.TAG, "Create $this")

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Get extras required for the return to the main activity
        subscriptionId = intent.getLongExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, 0)
        subscriptionBaseUrl = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL) ?: return
        subscriptionTopic = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC) ?: return

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

        // Update main list based on viewModel (& its datasource/livedata)
        val noEntriesText: View = findViewById(R.id.detail_no_notifications)
        val onNotificationClick = { n: Notification -> onNotificationClick(n) }
        val onNotificationLongClick = { n: Notification -> onNotificationLongClick(n) }

        adapter = DetailAdapter(onNotificationClick, onNotificationLongClick)
        mainList = findViewById(R.id.detail_notification_list)
        mainList.adapter = adapter

        viewModel.list(subscriptionId).observe(this) {
            it?.let {
                adapter.submitList(it as MutableList<Notification>)
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
        menuInflater.inflate(R.menu.detail_action_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.detail_menu_test -> {
                onTestClick()
                true
            }
            R.id.detail_menu_refresh -> {
                onRefreshClick()
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
                Toast
                    .makeText(this@DetailActivity, getString(R.string.detail_test_message_error, e.message), Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun onRefreshClick() {
        Log.d(TAG, "Fetching cached notifications for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val notifications = api.poll(subscriptionId, subscriptionBaseUrl, subscriptionTopic)
                val newNotifications = repository.onlyNewNotifications(subscriptionId, notifications)
                val toastMessage = if (newNotifications.isEmpty()) {
                    getString(R.string.detail_refresh_message_no_results)
                } else {
                    getString(R.string.detail_refresh_message_result, newNotifications.size)
                }
                newNotifications.forEach { notification -> repository.addNotification(subscriptionId, notification) }
                runOnUiThread { Toast.makeText(this@DetailActivity, toastMessage, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                Toast
                    .makeText(this@DetailActivity, getString(R.string.detail_refresh_message_error, e.message), Toast.LENGTH_LONG)
                    .show()
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
                    .putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscriptionTopic)
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
        val message = notification.message + "\n\n" + Date(notification.timestamp * 1000).toString()
        val clipboard: ClipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("notification message", message)
        clipboard.setPrimaryClip(clip)
        Toast
            .makeText(this, getString(R.string.detail_copied_to_clipboard_message), Toast.LENGTH_LONG)
            .show()
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
            R.id.detail_action_mode_delete -> {
                onMultiDeleteClick()
                true
            }
            else -> false
        }
    }

    private fun onMultiDeleteClick() {
        Log.d(TAG, "Showing multi-delete dialog for selected items")

        val builder = AlertDialog.Builder(this)
        builder
            .setMessage(R.string.detail_action_mode_delete_dialog_message)
            .setPositiveButton(R.string.detail_action_mode_delete_dialog_permanently_delete) { _, _ ->
                adapter.selected.map { notificationId -> viewModel.remove(subscriptionId, notificationId) }
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
