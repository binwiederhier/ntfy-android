package io.heckel.ntfy.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Action
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.AlarmBroadcastReceiver
import io.heckel.ntfy.msg.AlarmConfig
import io.heckel.ntfy.msg.AlarmSessionManager
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.msg.UserActionManager
import io.heckel.ntfy.msg.parseAlarmConfig
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.formatDateShort
import io.heckel.ntfy.util.formatMessage
import io.heckel.ntfy.util.supportedImage
import io.heckel.ntfy.util.formatTitle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Alarm-clock-style screen shown over the lockscreen for "fullscreen"-tagged messages, launched
 * by the system via the alarm notification's full-screen intent (or by tapping the notification).
 *
 * This screen is UI-only: sound/vibration/timeout are owned by AlarmSessionManager, which was
 * started when the notification was displayed. Buttons are entirely backend-defined via ntfy
 * actions; "broadcast" actions with the reserved ALARM_DISMISS/ALARM_SNOOZE intents act locally,
 * everything else runs through the normal action machinery and also silences the alarm. If the
 * message defines no actions, a fallback Dismiss button is shown.
 */
class AlarmActivity : AppCompatActivity() {
    private val repository by lazy { Repository.getInstance(this) }
    private val sessionManager by lazy { AlarmSessionManager.getInstance(this) }
    private var notification: Notification? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)
        setupShowOverLockscreen()
        onBackPressedDispatcher.addCallback(this) {
            // Back = silence and close; the message stays in the notification shade
            stopAndFinish()
        }
        handleIntent(intent)
        observeSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent) // A second alarm replaced the first one (launchMode=singleInstance)
    }

    private fun setupShowOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun handleIntent(intent: Intent) {
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
        if (notificationId == null) {
            finish()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val notification = repository.getNotification(notificationId)
            val subscription = notification?.let { repository.getSubscription(it.subscriptionId) }
            withContext(Dispatchers.Main) {
                if (notification == null || subscription == null) {
                    Log.w(TAG, "Notification $notificationId not found; closing alarm screen")
                    finish()
                } else {
                    show(subscription, notification)
                }
            }
        }
    }

    private fun observeSession() {
        lifecycleScope.launch {
            sessionManager.activeNotificationId.collect { activeId ->
                // Auto-close when the session ends elsewhere (timeout, shade button) or is replaced.
                // Only once a notification is loaded; the session is always started before the
                // notification (and hence this activity) exists.
                val shownId = notification?.id ?: return@collect
                if (activeId != shownId) {
                    finish()
                }
            }
        }
    }

    private fun show(subscription: Subscription, notification: Notification) {
        this.notification = notification
        val config = parseAlarmConfig(notification.tags) ?: AlarmConfig()

        findViewById<TextView>(R.id.alarm_topic_text).text = displayName(getString(R.string.app_base_url), subscription)
        findViewById<TextView>(R.id.alarm_time_text).text = formatDateShort(notification.timestamp)
        val titleView = findViewById<TextView>(R.id.alarm_title_text)
        val title = formatTitle(notification)
        titleView.text = title.ifEmpty { getString(R.string.channel_alarm_name) }
        findViewById<TextView>(R.id.alarm_message_text).text = formatMessage(notification)
        maybeShowAttachmentImage(notification)

        val container = findViewById<LinearLayout>(R.id.alarm_buttons_container)
        container.removeAllViews()
        val actions = notification.actions ?: emptyList()
        if (actions.isEmpty()) {
            addButton(container, getString(R.string.notification_popup_action_alarm_dismiss)) {
                dismiss(notification, clear = false)
            }
        } else {
            actions.forEach { action -> addActionButton(container, notification, config, action) }
        }
    }

    /**
     * Shows the message's image attachment on the alarm screen, e.g. the camera snapshot on a
     * doorbell alarm — the picture is usually the whole reason the alarm is worth looking at.
     *
     * Loaded straight from the attachment URL rather than waiting for contentUri: the download
     * worker may not have finished (or may be disabled by the auto-download size limit) while the
     * alarm is already ringing, and a snapshot that arrives after the ringing stops is useless.
     * Glide prefers the downloaded copy when there is one. Non-image attachments are ignored;
     * the existing message text already names them.
     */
    private fun maybeShowAttachmentImage(notification: Notification) {
        val imageView = findViewById<ImageView>(R.id.alarm_attachment_image)
        val attachment = notification.attachment
        if (attachment == null || !supportedImage(attachment.type)) {
            imageView.visibility = View.GONE
            return
        }
        try {
            Glide.with(this)
                .load(attachment.contentUri ?: attachment.url)
                .fitCenter()
                .into(imageView)
            imageView.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.w(TAG, "Unable to load alarm attachment image", e)
            imageView.visibility = View.GONE
        }
    }

    private fun addActionButton(container: LinearLayout, notification: Notification, config: AlarmConfig, action: Action) {
        val isBroadcast = action.action.lowercase(Locale.getDefault()) == NotificationService.ACTION_BROADCAST
        when {
            isBroadcast && action.intent == AlarmBroadcastReceiver.ACTION_ALARM_DISMISS -> {
                val clear = action.clear == true || action.extras?.get("clear") == "true"
                addButton(container, action.label) { dismiss(notification, clear) }
            }
            isBroadcast && action.intent == AlarmBroadcastReceiver.ACTION_ALARM_SNOOZE -> {
                val minutes = action.extras?.get("minutes")?.toIntOrNull() ?: config.snoozeMinutes
                addButton(container, action.label) { snooze(notification, minutes) }
            }
            action.action.lowercase(Locale.getDefault()) == NotificationService.ACTION_VIEW -> {
                addButton(container, action.label) { view(notification, action) }
            }
            else -> {
                // http, ordinary broadcast, copy: silence the alarm, then run through the normal
                // action machinery (which also honors the action's clear flag)
                addButton(container, action.label) {
                    sessionManager.stop(cancelNotification = true)
                    UserActionManager.enqueue(this, notification.id, action.id)
                    finish()
                }
            }
        }
    }

    private fun addButton(container: LinearLayout, label: String, onClick: () -> Unit) {
        val button = MaterialButton(this)
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.topMargin = (8 * resources.displayMetrics.density).toInt()
        button.layoutParams = params
        button.text = label
        button.textSize = 18f
        button.minHeight = (56 * resources.displayMetrics.density).toInt()
        button.setOnClickListener { onClick() }
        container.addView(button)
    }

    private fun dismiss(notification: Notification, clear: Boolean) {
        sessionManager.stop(cancelNotification = true)
        if (clear) {
            clearMessage(notification)
        }
        finish()
    }

    private fun snooze(notification: Notification, minutes: Int) {
        sessionManager.snooze(notification.id, notification.notificationId, minutes)
        Toast.makeText(this, getString(R.string.alarm_snoozed_toast, minutes), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun view(notification: Notification, action: Action) {
        sessionManager.stop(cancelNotification = true)
        val url = action.url
        if (url != null) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e: Exception) {
                Log.w(TAG, "Unable to open URL $url", e)
                Toast.makeText(this, getString(R.string.detail_item_cannot_open_url, e.message), Toast.LENGTH_LONG).show()
            }
        }
        if (action.clear == true) {
            clearMessage(notification)
        }
        finish()
    }

    private fun clearMessage(notification: Notification) {
        NotificationService(this).cancel(notification.notificationId)
        val app = applicationContext as Application
        app.ioScope.launch {
            Repository.getInstance(app).markAsReadBySequenceId(notification.subscriptionId, notification.sequenceId)
        }
    }

    private fun stopAndFinish() {
        sessionManager.stop(cancelNotification = true)
        finish()
    }

    companion object {
        private const val TAG = "NtfyAlarmActivity"

        const val EXTRA_NOTIFICATION_ID = "notificationId" // Database ID (String)
        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
    }
}
