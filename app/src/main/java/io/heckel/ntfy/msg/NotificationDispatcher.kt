package io.heckel.ntfy.msg

import android.content.Context
import android.util.Log
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.data.Subscription
import io.heckel.ntfy.up.Distributor
import io.heckel.ntfy.util.safeLet

/**
 * The notification dispatcher figures out what to do with a notification.
 * It may display a notification, send out a broadcast, or forward via UnifiedPush.
 */
class NotificationDispatcher(val context: Context, val repository: Repository) {
    private val notifier = NotificationService(context)
    private val broadcaster = BroadcastService(context)
    private val distributor = Distributor(context)

    fun init() {
        notifier.createNotificationChannels()
    }

    fun dispatch(subscription: Subscription, notification: Notification) {
        val muted = getMuted(subscription)
        val notify = shouldNotify(subscription, notification, muted)
        val broadcast = shouldBroadcast(subscription)
        val distribute = shouldDistribute(subscription)
        val download = shouldDownload(subscription, notification)
        if (notify) {
            notifier.display(subscription, notification)
        }
        if (broadcast) {
            broadcaster.send(subscription, notification, muted)
        }
        if (distribute) {
            safeLet(subscription.upAppId, subscription.upConnectorToken) { appId, connectorToken ->
                distributor.sendMessage(appId, connectorToken, notification.message)
            }
        }
        if (download) {
            // Download attachment (+ preview if available) in the background via WorkManager
            // The indirection via WorkManager is required since this code may be executed
            // in a doze state and Internet may not be available. It's also best practice apparently.
            scheduleAttachmentDownload(notification)
        }
    }

    private fun shouldDownload(subscription: Subscription, notification: Notification): Boolean {
        return notification.attachment != null
    }

    private fun shouldNotify(subscription: Subscription, notification: Notification, muted: Boolean): Boolean {
        if (subscription.upAppId != null) {
            return false
        }
        val priority = if (notification.priority > 0) notification.priority else 3
        if (priority < repository.getMinPriority()) {
            return false
        }
        val detailsVisible = repository.detailViewSubscriptionId.get() == notification.subscriptionId
        return !detailsVisible && !muted
    }

    private fun shouldBroadcast(subscription: Subscription): Boolean {
        if (subscription.upAppId != null) { // Never broadcast for UnifiedPush subscriptions
            return false
        }
        return repository.getBroadcastEnabled()
    }

    private fun shouldDistribute(subscription: Subscription): Boolean {
        return subscription.upAppId != null // Only distribute for UnifiedPush subscriptions
    }

    private fun getMuted(subscription: Subscription): Boolean {
        if (repository.isGlobalMuted()) {
            return true
        }
        return subscription.mutedUntil == 1L || (subscription.mutedUntil > 1L && subscription.mutedUntil > System.currentTimeMillis()/1000)
    }

    private fun scheduleAttachmentDownload(notification: Notification) {
        Log.d(TAG, "Enqueuing work to download attachment (+ preview if available)")
        val workManager = WorkManager.getInstance(context)
        val workRequest = OneTimeWorkRequest.Builder(AttachmentDownloadWorker::class.java)
            .setInputData(workDataOf(
                "id" to notification.id,
            ))
            .build()
        workManager.enqueue(workRequest)
    }

    companion object {
        private const val TAG = "NtfyNotifDispatch"
    }
}
