package io.heckel.ntfy.msg

import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.User
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.deriveNotificationId

/**
 * Polls the server for notifications and updates the repository.
 * Groups notifications by sequenceId and only keeps the latest for each sequence.
 * Deletes sequences where the latest notification is marked as deleted.
 */
class Poller(
    private val api: ApiService,
    private val repository: Repository
) {
    /**
     * Polls for notifications and updates the repository.
     * Returns the list of new notifications that were added.
     *
     * @param subscription The subscription to poll
     * @param user The user for authentication (may be null)
     * @param since The message ID to poll since (null for all cached messages)
     * @param notify Whether to derive notification IDs for popup notifications
     */
    suspend fun poll(
        subscription: Subscription,
        user: User?,
        since: String? = null,
        notify: Boolean = false
    ): List<Notification> {
        val notifications = api.poll(
            subscriptionId = subscription.id,
            baseUrl = subscription.baseUrl,
            topic = subscription.topic,
            user = user,
            since = since
        )
        return processNotifications(subscription.id, notifications, notify)
    }

    /**
     * Processes a list of notifications: groups by sequenceId, deletes deleted sequences,
     * and adds only non-deleted latest notifications.
     * Returns the list of notifications that were added.
     */
    private suspend fun processNotifications(
        subscriptionId: Long,
        notifications: List<Notification>,
        notify: Boolean
    ): List<Notification> {
        // Group by sequenceId and only keep the latest notification for each sequence
        val latestBySequenceId = notifications
            .groupBy { it.sequenceId.ifEmpty { it.id } }
            .mapValues { (_, notifs) -> notifs.maxByOrNull { it.timestamp } }
            .values
            .filterNotNull()

        // Delete sequences where the latest notification is marked as deleted
        latestBySequenceId.filter { it.deleted }.forEach { notification ->
            val sequenceId = notification.sequenceId.ifEmpty { notification.id }
            Log.d(TAG, "Deleting notifications with sequenceId $sequenceId")
            repository.deleteBySequenceId(subscriptionId, sequenceId)
        }

        // Add only non-deleted latest notifications
        val notificationsToAdd = latestBySequenceId
            .filter { !it.deleted }
            .map { if (notify) it.copy(notificationId = deriveNotificationId(it.sequenceId)) else it }

        val addedNotifications = mutableListOf<Notification>()
        notificationsToAdd.forEach { notification ->
            if (repository.addNotification(notification)) {
                addedNotifications.add(notification)
            }
        }

        return addedNotifications
    }

    companion object {
        private const val TAG = "NtfyPoller"
    }
}
