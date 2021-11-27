package io.heckel.ntfy.msg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.util.topicShortUrl
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import io.heckel.ntfy.util.formatMessage
import io.heckel.ntfy.util.formatTitle

class NotificationService(val context: Context) {
    fun send(subscription: Subscription, notification: Notification) {
        Log.d(TAG, "Displaying notification $notification")

        // Create an Intent for the activity you want to start
        val intent = Intent(context, DetailActivity::class.java)
        intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
        intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
        intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
        intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
        intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)
        val pendingIntent: PendingIntent? = TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent) // Add the intent, which inflates the back stack
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT) // Get the PendingIntent containing the entire back stack
        }

        val title = formatTitle(subscription, notification)
        val message = formatMessage(notification)
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val channelId = toChannelId(notification.priority)
        var notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.primaryColor))
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent) // Click target for notification
            .setAutoCancel(true) // Cancel when notification is clicked

        if (notification.priority == 4) {
            notificationBuilder = notificationBuilder
                .setVibrate(longArrayOf(500, 500, 500, 500, 500, 500))
                .setLights(Color.YELLOW, 3000, 3000)
        } else if (notification.priority == 5) {
            notificationBuilder = notificationBuilder
                .setVibrate(longArrayOf(1000, 500, 1000, 500, 1000, 500))
                .setLights(Color.RED, 3000, 3000)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(notificationManager, notification)
        }
        notificationManager.notify(notification.notificationId, notificationBuilder.build())
    }

    fun cancel(notification: Notification) {
        if (notification.notificationId != 0) {
            Log.d(TAG, "Cancelling notification ${notification.id}: ${notification.message}")
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notification.notificationId)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(notificationManager: NotificationManager, notification: Notification) {
        val channel = when (notification.priority) {
            1 -> NotificationChannel(CHANNEL_ID_MIN, context.getString(R.string.channel_notifications_min_name), NotificationManager.IMPORTANCE_MIN)
            2 -> NotificationChannel(CHANNEL_ID_LOW, context.getString(R.string.channel_notifications_low_name), NotificationManager.IMPORTANCE_LOW)
            4 -> NotificationChannel(CHANNEL_ID_HIGH, context.getString(R.string.channel_notifications_high_name), NotificationManager.IMPORTANCE_HIGH)
            5 -> NotificationChannel(CHANNEL_ID_MAX, context.getString(R.string.channel_notifications_max_name), NotificationManager.IMPORTANCE_MAX)
            else -> NotificationChannel(CHANNEL_ID_DEFAULT, context.getString(R.string.channel_notifications_default_name), NotificationManager.IMPORTANCE_DEFAULT)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun toChannelId(priority: Int): String {
        return when (priority) {
            1 -> CHANNEL_ID_MIN
            2 -> CHANNEL_ID_LOW
            4 -> CHANNEL_ID_HIGH
            5 -> CHANNEL_ID_MAX
            else -> CHANNEL_ID_DEFAULT
        }
    }

    companion object {
        private const val TAG = "NtfyNotificationService"
        private const val CHANNEL_ID_MIN = "ntfy-min"
        private const val CHANNEL_ID_LOW = "ntfy-low"
        private const val CHANNEL_ID_DEFAULT = "ntfy"
        private const val CHANNEL_ID_HIGH = "ntfy-high"
        private const val CHANNEL_ID_MAX = "ntfy-max"
    }
}
