package io.heckel.ntfy.msg

import android.app.*
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannedString
import android.text.style.CharacterStyle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.*
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import io.heckel.ntfy.util.*
import java.util.*

class NotificationService(val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val repository = Repository.getInstance(context)
    private val markwon = MarkwonFactory.createForNotification(context)

    fun display(subscription: Subscription, notification: Notification) {
        Log.d(TAG, "Displaying notification $notification")
        displayInternal(subscription, notification)
    }

    fun update(subscription: Subscription, notification: Notification) {
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.activeNotifications.find { it.id == notification.notificationId } != null
        } else {
            true
        }
        if (active) {
            Log.d(TAG, "Updating notification $notification")
            displayInternal(subscription, notification, update = true)
        }
    }

    fun cancel(notification: Notification) {
        if (notification.notificationId != 0) {
            Log.d(TAG, "Cancelling notification ${notification.id}: ${decodeMessage(notification)}")
            notificationManager.cancel(notification.notificationId)
        }
    }

    fun cancel(notificationId: Int) {
        if (notificationId != 0) {
            Log.d(TAG, "Cancelling notification $notificationId")
            notificationManager.cancel(notificationId)
        }
    }

    fun createDefaultNotificationChannels() {
        maybeCreateNotificationGroup(DEFAULT_GROUP, context.getString(R.string.channel_notifications_group_default_name))
        ALL_PRIORITIES.forEach { priority -> maybeCreateNotificationChannel(DEFAULT_GROUP, priority) }
    }

    fun createSubscriptionNotificationChannels(subscription: Subscription) {
        val groupId = subscriptionGroupId(subscription)
        maybeCreateNotificationGroup(groupId, subscriptionGroupName(subscription))
        ALL_PRIORITIES.forEach { priority -> maybeCreateNotificationChannel(groupId, priority) }
    }

    fun deleteSubscriptionNotificationChannels(subscription: Subscription) {
        val groupId = subscriptionGroupId(subscription)
        ALL_PRIORITIES.forEach { priority -> maybeDeleteNotificationChannel(groupId, priority) }
        maybeDeleteNotificationGroup(groupId)
    }

    fun channelsSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    private fun subscriptionGroupId(subscription: Subscription): String {
        return SUBSCRIPTION_GROUP_PREFIX + subscription.id.toString()
    }

    private fun subscriptionGroupName(subscription: Subscription): String {
        return subscription.displayName ?: subscriptionTopicShortUrl(subscription)
    }

    private fun displayInternal(subscription: Subscription, notification: Notification, update: Boolean = false) {
        val title = formatTitle(subscription, notification)
        val groupId = if (subscription.dedicatedChannels) subscriptionGroupId(subscription) else DEFAULT_GROUP
        val channelId = toChannelId(groupId, notification.priority)
        val insistent = notification.priority == PRIORITY_MAX &&
                (repository.getInsistentMaxPriorityEnabled() || subscription.insistent == Repository.INSISTENT_MAX_PRIORITY_ENABLED)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, Colors.notificationIcon(context)))
            .setContentTitle(title)
            .setOnlyAlertOnce(true) // Do not vibrate or play sound if already showing (updates!)
            .setAutoCancel(true) // Cancel when notification is clicked
        setStyleAndText(builder, subscription, notification) // Preview picture or big text style
        setClickAction(builder, subscription, notification)
        maybeSetDeleteIntent(builder, insistent)
        maybeSetSound(builder, insistent, update)
        maybeSetProgress(builder, notification)
        maybeAddOpenAction(builder, notification)
        maybeAddBrowseAction(builder, notification)
        maybeAddDownloadAction(builder, notification)
        maybeAddCancelAction(builder, notification)
        maybeAddUserActions(builder, notification)

        maybeCreateNotificationGroup(groupId, subscriptionGroupName(subscription))
        maybeCreateNotificationChannel(groupId, notification.priority)
        maybePlayInsistentSound(groupId, insistent)

        notificationManager.notify(notification.notificationId, builder.build())
    }

    private fun maybeSetDeleteIntent(builder: NotificationCompat.Builder, insistent: Boolean) {
        if (!insistent) {
            return
        }
        val intent = Intent(context, DeleteBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
        builder.setDeleteIntent(pendingIntent)
    }

    private fun maybeSetSound(builder: NotificationCompat.Builder, insistent: Boolean, update: Boolean) {
        // Note that the sound setting is ignored in Android => O (26) in favor of notification channels
        val hasSound = !update && !insistent
        if (hasSound) {
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            builder.setSound(defaultSoundUri)
        } else {
            builder.setSound(null)
        }
    }

    private fun setStyleAndText(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification) {
        val contentUri = notification.attachment?.contentUri
        val isSupportedImage = supportedImage(notification.attachment?.type)
        val subscriptionIcon = if (subscription.icon != null) subscription.icon.readBitmapFromUriOrNull(context) else null
        val notificationIcon = if (notification.icon != null) notification.icon.contentUri?.readBitmapFromUriOrNull(context) else null
        val largeIcon = notificationIcon ?: subscriptionIcon
        if (contentUri != null && isSupportedImage) {
            try {
                val attachmentBitmap = contentUri.readBitmapFromUri(context)
                builder
                    .setContentText(maybeAppendActionErrors(maybeMarkdown(formatMessage(notification), notification), notification))
                    .setLargeIcon(attachmentBitmap)
                    .setStyle(NotificationCompat.BigPictureStyle()
                        .bigPicture(attachmentBitmap)
                        .bigLargeIcon(largeIcon)) // May be null
            } catch (_: Exception) {
                val message = maybeAppendActionErrors(formatMessageMaybeWithAttachmentInfos(notification), notification)
                builder
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            }
        } else {
            val message = maybeAppendActionErrors(formatMessageMaybeWithAttachmentInfos(notification), notification)
            builder
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setLargeIcon(largeIcon) // May be null
        }
    }

    private fun formatMessageMaybeWithAttachmentInfos(notification: Notification): CharSequence {
        val message = maybeMarkdown(formatMessage(notification), notification)
        val attachment = notification.attachment ?: return message
        val attachmentInfos = if (attachment.size != null) {
            "${attachment.name}, ${formatBytes(attachment.size)}"
        } else {
            attachment.name
        }
        if (attachment.progress in 0..99) {
            return context.getString(R.string.notification_popup_file_downloading, attachmentInfos, attachment.progress, message)
        }
        if (attachment.progress == ATTACHMENT_PROGRESS_DONE) {
            return context.getString(R.string.notification_popup_file_download_successful, message, attachmentInfos)
        }
        if (attachment.progress == ATTACHMENT_PROGRESS_FAILED) {
            return context.getString(R.string.notification_popup_file_download_failed, message, attachmentInfos)
        }
        return context.getString(R.string.notification_popup_file, message, attachmentInfos)
    }

    private fun setClickAction(builder: NotificationCompat.Builder, subscription: Subscription, notification: Notification) {
        if (notification.click == "") {
            builder.setContentIntent(detailActivityIntent(subscription))
        } else {
            try {
                val uri = Uri.parse(notification.click)
                val viewIntent = PendingIntent.getActivity(context, Random().nextInt(), Intent(Intent.ACTION_VIEW, uri), PendingIntent.FLAG_IMMUTABLE)
                builder.setContentIntent(viewIntent)
            } catch (e: Exception) {
                builder.setContentIntent(detailActivityIntent(subscription))
            }
        }
    }

    private fun maybeSetProgress(builder: NotificationCompat.Builder, notification: Notification) {
        val progress = notification.attachment?.progress
        if (progress in 0..99) {
            builder.setProgress(100, progress!!, false)
        } else {
            builder.setProgress(0, 0, false) // Remove progress bar
        }
    }

    private fun maybeAddOpenAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (!canOpenAttachment(notification.attachment)) {
            return
        }
        if (notification.attachment?.contentUri != null) {
            val contentUri = Uri.parse(notification.attachment.contentUri)
            val intent = Intent(Intent.ACTION_VIEW, contentUri).apply {
                setDataAndType(contentUri, notification.attachment.type ?: "application/octet-stream") // Required for Android <= P
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_open), pendingIntent).build())
        }
    }

    private fun maybeAddBrowseAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri != null) {
            val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_browse), pendingIntent).build())
        }
    }

    private fun maybeAddDownloadAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri == null && listOf(ATTACHMENT_PROGRESS_NONE, ATTACHMENT_PROGRESS_FAILED).contains(notification.attachment?.progress)) {
            val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_START)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_download), pendingIntent).build())
        }
    }

    private fun maybeAddCancelAction(builder: NotificationCompat.Builder, notification: Notification) {
        if (notification.attachment?.contentUri == null && notification.attachment?.progress in 0..99) {
            val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
                putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_DOWNLOAD_CANCEL)
                putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.notification_popup_action_cancel), pendingIntent).build())
        }
    }

    private fun maybeAddUserActions(builder: NotificationCompat.Builder, notification: Notification) {
        notification.actions?.forEach { action ->
            val actionType = action.action.lowercase(Locale.getDefault())
            if (actionType == ACTION_VIEW) {
                // Hack: Action "view" with "clear=true" is a special case, because it's apparently impossible to start a
                // URL activity from PendingIntent.getActivity() and also close the notification. To clear it, we
                // launch our own Activity (ViewActionWithClearActivity) which then calls the actual activity

                if (action.clear == true) {
                    addViewUserActionWithClear(builder, notification, action)
                } else {
                    addViewUserActionWithoutClear(builder, action)
                }
            } else {
                addHttpOrBroadcastUserAction(builder, notification, action)
            }
        }
    }

    /**
     * Open the URL and do NOT cancel the notification (clear=false). This uses a normal Intent with the given URL.
     * The other case is much more interesting.
     */
    private fun addViewUserActionWithoutClear(builder: NotificationCompat.Builder, action: Action) {
        try {
            val url = action.url ?: return
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, action.label, pendingIntent).build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add open user action", e)
        }
    }

    /**
     * HACK: Open the URL and CANCEL the notification (clear=true). This is a SPECIAL case with a horrible workaround.
     * We call our own activity ViewActionWithClearActivity and open the URL from there.
     */
    private fun addViewUserActionWithClear(builder: NotificationCompat.Builder, notification: Notification, action: Action) {
        try {
            val url = action.url ?: return
            val intent = Intent(context, ViewActionWithClearActivity::class.java).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(VIEW_ACTION_EXTRA_URL, url)
                putExtra(VIEW_ACTION_EXTRA_NOTIFICATION_ID, notification.notificationId)
            }
            val pendingIntent = PendingIntent.getActivity(context, Random().nextInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, action.label, pendingIntent).build())
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add open user action", e)
        }
    }

    private fun addHttpOrBroadcastUserAction(builder: NotificationCompat.Builder, notification: Notification, action: Action) {
        val intent = Intent(context, UserActionBroadcastReceiver::class.java).apply {
            putExtra(BROADCAST_EXTRA_TYPE, BROADCAST_TYPE_USER_ACTION)
            putExtra(BROADCAST_EXTRA_NOTIFICATION_ID, notification.id)
            putExtra(BROADCAST_EXTRA_ACTION_ID, action.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, Random().nextInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label = formatActionLabel(action)
        builder.addAction(NotificationCompat.Action.Builder(0, label, pendingIntent).build())
    }

    /**
     * Receives the broadcast from
     * - the "http" and "broadcast" action button (the "view" action is handled differently)
     * - the "download"/"cancel" action button
     *
     * Then queues a Worker via WorkManager to execute the action in the background
     */
    class UserActionBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = intent.getStringExtra(BROADCAST_EXTRA_TYPE) ?: return
            val notificationId = intent.getStringExtra(BROADCAST_EXTRA_NOTIFICATION_ID) ?: return
            when (type) {
                BROADCAST_TYPE_DOWNLOAD_START -> DownloadManager.enqueue(context, notificationId, userAction = true, DownloadType.ATTACHMENT)
                BROADCAST_TYPE_DOWNLOAD_CANCEL -> DownloadManager.cancel(context, notificationId)
                BROADCAST_TYPE_USER_ACTION -> {
                    val actionId = intent.getStringExtra(BROADCAST_EXTRA_ACTION_ID) ?: return
                    UserActionManager.enqueue(context, notificationId, actionId)
                }
            }
        }
    }

    /**
     * Receives a broadcast when a notification is swiped away. This is currently
     * only called for notifications with an insistent sound.
     */
    class DeleteBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Media player: Stopping insistent ring")
            val mediaPlayer = Repository.getInstance(context).mediaPlayer
            mediaPlayer.stop()
        }
    }

    private fun detailActivityIntent(subscription: Subscription): PendingIntent? {
        val intent = Intent(context, DetailActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(subscription))
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)
        }
        return TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent) // Add the intent, which inflates the back stack
            getPendingIntent(Random().nextInt(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) // Get the PendingIntent containing the entire back stack
        }
    }

    private fun maybeCreateNotificationChannel(group: String, priority: Int) {
        if (channelsSupported()) {
            // Note: To change a notification channel, you must delete the old one and create a new one!

            val channelId = toChannelId(group, priority)
            val pause = 300L
            val channel = when (priority) {
                PRIORITY_MIN -> NotificationChannel(channelId, context.getString(R.string.channel_notifications_min_name), NotificationManager.IMPORTANCE_MIN)
                PRIORITY_LOW -> NotificationChannel(channelId, context.getString(R.string.channel_notifications_low_name), NotificationManager.IMPORTANCE_LOW)
                PRIORITY_HIGH -> {
                    val channel = NotificationChannel(channelId, context.getString(R.string.channel_notifications_high_name), NotificationManager.IMPORTANCE_HIGH)
                    channel.enableVibration(true)
                    channel.vibrationPattern = longArrayOf(
                        pause, 100, pause, 100, pause, 100,
                        pause, 2000
                    )
                    channel
                }
                PRIORITY_MAX -> {
                    val channel = NotificationChannel(channelId, context.getString(R.string.channel_notifications_max_name), NotificationManager.IMPORTANCE_HIGH) // IMPORTANCE_MAX does not exist
                    channel.enableLights(true)
                    channel.enableVibration(true)
                    channel.setBypassDnd(true)
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
                else -> NotificationChannel(channelId, context.getString(R.string.channel_notifications_default_name), NotificationManager.IMPORTANCE_DEFAULT)
            }
            channel.group = group
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun maybeDeleteNotificationChannel(group: String, priority: Int) {
        if (channelsSupported()) {
            notificationManager.deleteNotificationChannel(toChannelId(group, priority))
        }
    }

    private fun maybeCreateNotificationGroup(id: String, name: String) {
        if (channelsSupported()) {
            notificationManager.createNotificationChannelGroup(NotificationChannelGroup(id, name))
        }
    }

    private fun maybeDeleteNotificationGroup(id: String) {
        if (channelsSupported()) {
            notificationManager.deleteNotificationChannelGroup(id)
        }
    }

    private fun toChannelId(groupId: String, priority: Int): String {
        return when (priority) {
            PRIORITY_MIN -> groupId + GROUP_SUFFIX_PRIORITY_MIN
            PRIORITY_LOW -> groupId + GROUP_SUFFIX_PRIORITY_LOW
            PRIORITY_HIGH -> groupId + GROUP_SUFFIX_PRIORITY_HIGH
            PRIORITY_MAX -> groupId + GROUP_SUFFIX_PRIORITY_MAX
            else -> groupId + GROUP_SUFFIX_PRIORITY_DEFAULT
        }
    }

    private fun maybePlayInsistentSound(groupId: String, insistent: Boolean) {
        if (!insistent) {
            return
        }
        try {
            val mediaPlayer = repository.mediaPlayer
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
                Log.d(TAG, "Media player: Playing insistent alarm on alarm channel")
                mediaPlayer.reset()
                mediaPlayer.setDataSource(context, getInsistentSound(groupId))
                mediaPlayer.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                mediaPlayer.isLooping = true
                mediaPlayer.prepare()
                mediaPlayer.start()
            } else {
                Log.d(TAG, "Media player: Alarm volume is 0; not playing insistent alarm")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Media player: Failed to play insistent alarm", e)
        }
    }

    private fun getInsistentSound(groupId: String): Uri {
        return if (channelsSupported()) {
            val channelId = toChannelId(groupId, PRIORITY_MAX)
            val channel = notificationManager.getNotificationChannel(channelId)
            channel.sound
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    /**
     * Activity used to launch a URL.
     * .
     * Horrible hack: Action "view" with "clear=true" is a special case, because it's apparently impossible to start a
     * URL activity from PendingIntent.getActivity() and also close the notification. To clear it, we
     * launch this activity which then calls the actual activity.
     */
    class ViewActionWithClearActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            Log.d(TAG, "Created $this")
            val url = intent.getStringExtra(VIEW_ACTION_EXTRA_URL)
            val notificationId = intent.getIntExtra(VIEW_ACTION_EXTRA_NOTIFICATION_ID, 0)
            if (url == null) {
                finish()
                return
            }

            // Immediately start the actual activity
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start activity from URL $url", e)
                val message = if (e is ActivityNotFoundException) url else e.message
                Toast
                    .makeText(this, getString(R.string.detail_item_cannot_open_url, message), Toast.LENGTH_LONG)
                    .show()
            }

            // Cancel notification
            val notifier = NotificationService(this)
            notifier.cancel(notificationId)

            // Close this activity
            finish()
        }
    }

    private fun maybeMarkdown(message: String, notification: Notification): CharSequence {
        if (notification.contentType == "text/markdown") {
            return markwon.toMarkdown(message)
        }
        return message
    }

    companion object {
        const val ACTION_VIEW = "view"
        const val ACTION_HTTP = "http"
        const val ACTION_BROADCAST = "broadcast"

        const val BROADCAST_EXTRA_TYPE = "type"
        const val BROADCAST_EXTRA_NOTIFICATION_ID = "notificationId"
        const val BROADCAST_EXTRA_ACTION_ID = "actionId"

        const val BROADCAST_TYPE_DOWNLOAD_START = "io.heckel.ntfy.DOWNLOAD_ACTION_START"
        const val BROADCAST_TYPE_DOWNLOAD_CANCEL = "io.heckel.ntfy.DOWNLOAD_ACTION_CANCEL"
        const val BROADCAST_TYPE_USER_ACTION = "io.heckel.ntfy.USER_ACTION_RUN"

        private const val TAG = "NtfyNotifService"

        private const val DEFAULT_GROUP = "ntfy"
        private const val SUBSCRIPTION_GROUP_PREFIX = "ntfy-subscription-"
        private const val GROUP_SUFFIX_PRIORITY_MIN = "-min"
        private const val GROUP_SUFFIX_PRIORITY_LOW = "-low"
        private const val GROUP_SUFFIX_PRIORITY_DEFAULT = ""
        private const val GROUP_SUFFIX_PRIORITY_HIGH = "-high"
        private const val GROUP_SUFFIX_PRIORITY_MAX = "-max"

        private const val VIEW_ACTION_EXTRA_URL = "url"
        private const val VIEW_ACTION_EXTRA_NOTIFICATION_ID = "notificationId"
    }
}
