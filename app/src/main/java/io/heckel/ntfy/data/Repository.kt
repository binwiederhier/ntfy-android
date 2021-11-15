package io.heckel.ntfy.data

import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import kotlinx.coroutines.flow.map

class Repository(private val subscriptionDao: SubscriptionDao, private val notificationDao: NotificationDao) {
    init {
        Log.d(TAG, "Created $this")
    }

    fun getSubscriptionsLiveData(): LiveData<List<Subscription>> {
        return subscriptionDao
            .listFlow()
            .asLiveData()
            .map { list -> toSubscriptionList(list) }
    }

    fun getSubscriptionIdsWithInstantStatusLiveData(): LiveData<Set<Pair<Long, Boolean>>> {
        return subscriptionDao
            .listFlow()
            .asLiveData()
            .map { list -> list.map { Pair(it.id, it.instant) }.toSet() }
    }

    fun getSubscriptions(): List<Subscription> {
        return toSubscriptionList(subscriptionDao.list())
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscription(subscriptionId: Long): Subscription? {
        return toSubscription(subscriptionDao.get(subscriptionId))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscription(baseUrl: String, topic: String): Subscription? {
        return toSubscription(subscriptionDao.get(baseUrl, topic))
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addSubscription(subscription: Subscription) {
        subscriptionDao.add(subscription)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun updateSubscription(subscription: Subscription) {
        subscriptionDao.update(subscription)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun removeSubscription(subscriptionId: Long) {
        subscriptionDao.remove(subscriptionId)
    }

    fun getNotificationsLiveData(subscriptionId: Long): LiveData<List<Notification>> {
        return notificationDao.list(subscriptionId).asLiveData()
    }

    fun getNotification(notificationId: String): Notification? {
        return notificationDao.get(notificationId)
    }

    fun onlyNewNotifications(subscriptionId: Long, notifications: List<Notification>): List<Notification> {
        val existingIds = notificationDao.listIds(subscriptionId)
        return notifications.filterNot { existingIds.contains(it.id) }
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addNotification(notification: Notification): Boolean {
        val maybeExistingNotification = notificationDao.get(notification.id)
        if (maybeExistingNotification == null) {
            notificationDao.add(notification)
            return true
        }
        return false
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun removeNotification(notificationId: String) {
        notificationDao.remove(notificationId)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    fun removeAllNotifications(subscriptionId: Long) {
        notificationDao.removeAll(subscriptionId)
    }

    private fun toSubscriptionList(list: List<SubscriptionWithMetadata>): List<Subscription> {
        return list.map { s ->
            Subscription(
                id = s.id,
                baseUrl = s.baseUrl,
                topic = s.topic,
                instant = s.instant,
                lastActive = s.lastActive,
                notifications = s.notifications
            )
        }
    }

    private fun toSubscription(s: SubscriptionWithMetadata?): Subscription? {
        if (s == null) {
            return null
        }
        return Subscription(
            id = s.id,
            baseUrl = s.baseUrl,
            topic = s.topic,
            instant = s.instant,
            lastActive = s.lastActive,
            notifications = s.notifications
        )
    }

    companion object {
        private val TAG = "NtfyRepository"
        private var instance: Repository? = null

        fun getInstance(subscriptionDao: SubscriptionDao, notificationDao: NotificationDao): Repository {
            return synchronized(Repository::class) {
                val newInstance = instance ?: Repository(subscriptionDao, notificationDao)
                instance = newInstance
                newInstance
            }
        }
    }
}
