package io.heckel.ntfy.msg

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.launch

/**
 * Handles explicit intents for the full-screen alarm feature: the AlarmManager-scheduled
 * ring timeout and snooze re-fire, plus the action buttons on the dedicated alarm notification.
 *
 * Note that this receiver is intentionally NOT reached via the regular ntfy "broadcast" action
 * machinery: BroadcastService.sendUserAction() sends implicit broadcasts, which manifest-registered
 * receivers never receive on Android 8+. The reserved ALARM_DISMISS/ALARM_SNOOZE action verbs are
 * therefore intercepted in UserActionWorker and AlarmActivity instead.
 */
class AlarmBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received alarm broadcast: $action")
        val sessionManager = AlarmSessionManager.getInstance(context)
        when (action) {
            ACTION_ALARM_TIMEOUT -> {
                sessionManager.stop(cancelNotification = true)
                cancelAlarmNotification(context, intent) // In case the session died with the process
            }
            ACTION_ALARM_DISMISS -> {
                sessionManager.stop(cancelNotification = true)
                cancelAlarmNotification(context, intent)
                if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
                    clearMessage(context, intent)
                }
            }
            ACTION_ALARM_SNOOZE -> {
                val notificationDbId = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
                val androidNotificationId = intent.getIntExtra(EXTRA_ANDROID_NOTIFICATION_ID, 0)
                val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, ALARM_DEFAULT_SNOOZE_MINUTES)
                sessionManager.snooze(notificationDbId, androidNotificationId, minutes)
                cancelAlarmNotification(context, intent)
            }
            ACTION_ALARM_FIRE -> {
                val notificationDbId = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
                fireAlarm(context, notificationDbId)
            }
            ACTION_ALARM_USER_ACTION -> {
                val notificationDbId = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
                val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
                sessionManager.stop(cancelNotification = true)
                cancelAlarmNotification(context, intent)
                UserActionManager.enqueue(context, notificationDbId, actionId)
            }
        }
    }

    /**
     * Cancels the dedicated alarm notification by its Android ID from the intent. This is a
     * fallback for when the process died while ringing: the fresh AlarmSessionManager no longer
     * knows the active notification, but the intent still carries its ID.
     */
    private fun cancelAlarmNotification(context: Context, intent: Intent) {
        val androidNotificationId = intent.getIntExtra(EXTRA_ANDROID_NOTIFICATION_ID, 0)
        if (androidNotificationId != 0) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(androidNotificationId)
        }
    }

    /**
     * Removes the message's notification and marks it as read (the "clear=true" semantics of
     * ntfy actions).
     */
    private fun clearMessage(context: Context, intent: Intent) {
        val subscriptionId = intent.getLongExtra(EXTRA_SUBSCRIPTION_ID, 0)
        val androidNotificationId = intent.getIntExtra(EXTRA_ANDROID_NOTIFICATION_ID, 0)
        val sequenceId = intent.getStringExtra(EXTRA_SEQUENCE_ID) ?: ""
        if (subscriptionId == 0L) return
        NotificationService(context).cancel(androidNotificationId)
        val app = context.applicationContext as? Application ?: return
        val pendingResult = goAsync()
        app.ioScope.launch {
            try {
                if (sequenceId.isNotEmpty()) {
                    Repository.getInstance(context).markAsReadBySequenceId(subscriptionId, sequenceId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Re-fires the full-screen alarm after a snooze. Reloads the notification from the database,
     * so this works even if the app process died in the meantime. Re-posting the full-screen
     * notification from an AlarmManager broadcast is the sanctioned path (no activity trampoline).
     */
    private fun fireAlarm(context: Context, notificationDbId: String) {
        val app = context.applicationContext as? Application ?: return
        val pendingResult = goAsync()
        app.ioScope.launch {
            try {
                val repository = Repository.getInstance(context)
                val notification = repository.getNotification(notificationDbId)
                if (notification == null || notification.deleted || notification.notificationId == 0) {
                    Log.d(TAG, "Not re-firing alarm; notification $notificationDbId is gone or was read")
                    return@launch
                }
                val subscription = repository.getSubscription(notification.subscriptionId) ?: return@launch
                val config = parseAlarmConfig(notification.tags) ?: return@launch
                NotificationService(context).displayAlarm(subscription, notification, config)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-fire alarm for notification $notificationDbId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NtfyAlarmReceiver"

        const val ACTION_ALARM_FIRE = "io.heckel.ntfy.ALARM_FIRE"
        const val ACTION_ALARM_TIMEOUT = "io.heckel.ntfy.ALARM_TIMEOUT"
        const val ACTION_ALARM_DISMISS = "io.heckel.ntfy.ALARM_DISMISS"
        const val ACTION_ALARM_SNOOZE = "io.heckel.ntfy.ALARM_SNOOZE"
        const val ACTION_ALARM_USER_ACTION = "io.heckel.ntfy.ALARM_USER_ACTION"

        const val EXTRA_NOTIFICATION_ID = "notificationId" // Database ID (String)
        const val EXTRA_ANDROID_NOTIFICATION_ID = "androidNotificationId" // Popup ID (Int)
        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        const val EXTRA_SEQUENCE_ID = "sequenceId"
        const val EXTRA_ACTION_ID = "actionId"
        const val EXTRA_SNOOZE_MINUTES = "snoozeMinutes"
        const val EXTRA_CLEAR = "clear"
    }
}
