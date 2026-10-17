package io.heckel.ntfy.ui

import android.content.Intent.ACTION_VIEW
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.copyToClipboard
import io.heckel.ntfy.util.decodeMessage
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.isDarkThemeOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Lists the notifications of all topics in one list, newest first, like "All notifications"
 * in the web app. Each card is labeled with its topic; tapping, swiping and the card menu
 * behave as in [DetailActivity].
 */
class AllNotificationsActivity : AppCompatActivity() {
    private val repository by lazy { (application as Application).repository }
    private var notifier: NotificationService? = null // Context-dependent
    private var topicNames: Map<Long, String> = emptyMap() // Subscription ID -> display name

    private lateinit var adapter: DetailAdapter
    private lateinit var mainList: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_notifications)

        Log.d(TAG, "Create $this")
        notifier = NotificationService(this)

        // Toolbar, same look as the detail activity
        val toolbarLayout = findViewById<View>(R.id.app_bar_drawer)
        val dynamicColors = repository.getDynamicColorsEnabled()
        val darkMode = isDarkThemeOn(this)
        toolbarLayout.setBackgroundColor(Colors.statusBarNormal(this, dynamicColors, darkMode))
        val toolbarTextColor = Colors.toolbarTextColor(this, dynamicColors, darkMode)
        val toolbar = toolbarLayout.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.setTitleTextColor(toolbarTextColor)
        toolbar.setNavigationIconTint(toolbarTextColor)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.all_notifications_title)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
            Colors.shouldUseLightStatusBar(dynamicColors, darkMode)

        // Background: theme background for dynamic colors, static gray otherwise
        val contentLayout = findViewById<View>(R.id.all_notifications_content_layout)
        val staticBackground = ContextCompat.getColor(this, R.color.detail_activity_background)
        contentLayout.setBackgroundColor(
            if (dynamicColors) MaterialColors.getColor(this, android.R.attr.colorBackground, staticBackground) else staticBackground
        )

        // List, with each card labeled by its topic
        adapter = DetailAdapter(
            activity = this,
            lifecycleScope = lifecycleScope,
            repository = repository,
            onClick = { n -> onNotificationClick(n) },
            onLongClick = { _ -> }, // No multi-select here; that lives in the topic view
            topicLabel = { n -> topicNames[n.subscriptionId] }
        )
        mainList = findViewById(R.id.all_notifications_list)
        mainList.adapter = adapter
        ViewCompat.setOnApplyWindowInsetsListener(mainList) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }

        // Topic names first, so the labels are there when the notifications arrive
        val appBaseUrl = getString(R.string.app_base_url)
        repository.getSubscriptionsLiveData().observe(this) { subscriptions ->
            topicNames = subscriptions.associate { it.id to displayName(appBaseUrl, it) }
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }

        // Notifications of all topics
        val noEntries: View = findViewById(R.id.all_notifications_no_notifications)
        repository.getAllNotificationsLiveData().observe(this) { notifications ->
            adapter.submitList(notifications.toMutableList())
            mainList.visibility = if (notifications.isEmpty()) View.GONE else View.VISIBLE
            noEntries.visibility = if (notifications.isEmpty()) View.VISIBLE else View.GONE
            maybeCancelNotificationPopups(notifications)
        }

        // Swipe to remove, with undo
        val itemTouchCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return false
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, swipeDir: Int) {
                val notification = adapter.get(viewHolder.absoluteAdapterPosition)
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.markAsDeleted(notification.id)
                }
                Snackbar.make(mainList, R.string.detail_item_snack_deleted, Snackbar.LENGTH_SHORT)
                    .setAction(R.string.detail_item_snack_undo) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            repository.undeleteNotification(notification.id)
                        }
                    }
                    .show()
            }
        }
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(mainList)

        // Scroll up when a new notification arrives
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    mainList.scrollToPosition(0)
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        // Everything was on screen, so clear the "new" markers for all topics (like DetailActivity does for one)
        GlobalScope.launch(Dispatchers.IO) {
            repository.markAllAsRead()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun onNotificationClick(notification: Notification) {
        if (notification.click != "") {
            try {
                startActivity(android.content.Intent(ACTION_VIEW, notification.click.toUri()))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open click URL", e)
                Toast
                    .makeText(this, getString(R.string.detail_item_cannot_open_url, e.message), Toast.LENGTH_LONG)
                    .show()
            }
        } else {
            copyToClipboard(this, "notification", decodeMessage(notification))
        }
    }

    private fun maybeCancelNotificationPopups(notifications: List<Notification>) {
        val notificationsWithPopups = notifications.filter { notification -> notification.notificationId != 0 }
        if (notificationsWithPopups.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                notificationsWithPopups.forEach { notification ->
                    notifier?.cancel(notification)
                }
            }
        }
    }

    companion object {
        const val TAG = "NtfyAllNotificationsActivity"
    }
}
