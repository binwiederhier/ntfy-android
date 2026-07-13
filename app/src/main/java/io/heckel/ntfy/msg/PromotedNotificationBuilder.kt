package io.heckel.ntfy.msg

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.util.formatTitle

/**
 * Builds promoted notifications for live update messages (percentage or countdown).
 * Uses Android's Promoted Notification API (API 34+) to show status chips in the
 * status bar. Uses the built-in Chronometer API for live countdown updates.
 */
object PromotedNotificationBuilder {

    /**
     * Determines whether this notification should be shown as a promoted live notification.
     */
    fun shouldShowLiveNotification(
        notification: Notification,
        context: Context,
        liveNotificationsEnabled: Boolean
    ): Boolean {
        // Only on API 34+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }

        // Setting must be enabled
        if (!liveNotificationsEnabled) {
            return false
        }

        // Must have percentage or end set
        if (notification.percentage < 0 && notification.end <= 0) {
            return false
        }

        // Mutually exclusive with download attachments
        if (notification.attachment != null) {
            return false
        }

        return true
    }

    /**
     * Builds a promoted notification for live updates.
     * Returns null if conditions aren't met (falls back to regular notification).
     */
	fun buildLiveNotification(
		subscription: Subscription,
		notification: Notification,
		context: Context,
		channelId: String
	): NotificationCompat.Builder? {
        if (!shouldShowLiveNotification(notification, context, true)) {
            return null
        }

        val title = formatTitle(context.getString(R.string.app_base_url), subscription, notification)

		val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Colors.notificationIcon(context))
            .setContentTitle(title)
            .setContentText(notification.message ?: "")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)

        // Chronometer MUST be set before setShowWhen() — matches Home Assistant order
        if (notification.end > 0) {
            val endMillis = if (notification.end > 1_000_000_000_000L) notification.end else notification.end * 1000
            val remainingMillis = endMillis - System.currentTimeMillis()
            builder.setWhen(endMillis)
            builder.setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Fix for NPE on some OEMs (Home Assistant pattern)
                builder.addExtras(Bundle())
                builder.setChronometerCountDown(true)
            }
            // Auto-dismiss when countdown reaches 0 (prevents negative countdown)
            if (remainingMillis > 0) {
                builder.setTimeoutAfter(remainingMillis)
            }
        }

        builder.setShowWhen(true)
            .setRequestPromotedOngoing(true)

        if (notification.percentage >= 0) {
            builder.setShortCriticalText(buildStatusChipText(notification))
        }

        return builder
    }

    /**
     * Builds the status chip text for the promoted notification.
     * For countdown notifications, shows the initial countdown time.
     * For percentage notifications, shows the percentage.
     */
    fun buildStatusChipText(notification: Notification): String {
        // Percentage takes priority
        if (notification.percentage >= 0) {
            if (notification.percentage >= 100) {
                return "Done"
            }
            return "${notification.percentage}%"
        }

        return ""
    }

}
