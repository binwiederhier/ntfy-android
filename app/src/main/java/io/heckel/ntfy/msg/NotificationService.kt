package io.heckel.ntfy.msg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import io.heckel.ntfy.util.formatMessage
import io.heckel.ntfy.util.formatTitle
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class NotificationService(val context: Context) {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS) // Total timeout for entire request
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun display(subscription: Subscription, notification: Notification) {
        Log.d(TAG, "Displaying notification $notification")

        val imageAttachment = notification.attachmentUrl != null && (notification.attachmentType?.startsWith("image/") ?: false)
        if (imageAttachment) {
            downloadImageAndDisplay(subscription, notification)
        } else {
            displayInternal(subscription, notification)
        }
    }

    fun cancel(notification: Notification) {
        if (notification.notificationId != 0) {
            Log.d(TAG, "Cancelling notification ${notification.id}: ${notification.message}")
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notification.notificationId)
        }
    }

    fun createNotificationChannels() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        (1..5).forEach { priority -> maybeCreateNotificationChannel(notificationManager, priority) }
    }

    private fun displayInternal(subscription: Subscription, notification: Notification, bitmap: Bitmap? = null) {
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
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent) // Click target for notification
            .setAutoCancel(true) // Cancel when notification is clicked
        notificationBuilder = if (bitmap != null) {
            notificationBuilder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))
        } else {
            notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(message))
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        maybeCreateNotificationChannel(notificationManager, notification.priority)
        notificationManager.notify(notification.notificationId, notificationBuilder.build())
    }

    private fun downloadImageAndDisplay(subscription: Subscription, notification: Notification) {
        val url = notification.attachmentUrl ?: return
        Log.d(TAG, "Downloading image $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ApiService.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) {
                displayInternal(subscription, notification)
                return
            }
            val bitmap = BitmapFactory.decodeStream(response.body!!.byteStream())
            displayInternal(subscription, notification, bitmap)
        }
    }

    private fun maybeCreateNotificationChannel(notificationManager: NotificationManager, priority: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Note: To change a notification channel, you must delete the old one and create a new one!

            val pause = 300L
            val channel = when (priority) {
                1 -> NotificationChannel(CHANNEL_ID_MIN, context.getString(R.string.channel_notifications_min_name), NotificationManager.IMPORTANCE_MIN)
                2 -> NotificationChannel(CHANNEL_ID_LOW, context.getString(R.string.channel_notifications_low_name), NotificationManager.IMPORTANCE_LOW)
                4 -> {
                    val channel = NotificationChannel(CHANNEL_ID_HIGH, context.getString(R.string.channel_notifications_high_name), NotificationManager.IMPORTANCE_HIGH)
                    channel.enableVibration(true)
                    channel.vibrationPattern = longArrayOf(
                        pause, 100, pause, 100, pause, 100,
                        pause, 2000
                    )
                    channel
                }
                5 -> {
                    val channel = NotificationChannel(CHANNEL_ID_MAX, context.getString(R.string.channel_notifications_max_name), NotificationManager.IMPORTANCE_MAX)
                    channel.enableLights(true)
                    channel.enableVibration(true)
                    channel.vibrationPattern = longArrayOf(
                        pause, 100, pause, 100, pause, 100,
                        pause, 2000,
                        pause, 100, pause, 100, pause, 100,
                        pause, 2000,
                        pause, 100, pause, 100, pause, 100,
                        pause, 2000
                    )
                    channel
                }
                else -> NotificationChannel(CHANNEL_ID_DEFAULT, context.getString(R.string.channel_notifications_default_name), NotificationManager.IMPORTANCE_DEFAULT)
            }
            notificationManager.createNotificationChannel(channel)
        }
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
