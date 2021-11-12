package io.heckel.ntfy.data

import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import java.util.*

class Repository(private val subscriptionDao: SubscriptionDao, private val notificationDao: NotificationDao) {
    init {
        Log.d(TAG, "Created $this")
    }

    fun getSubscriptionsLiveData(): LiveData<List<Subscription>> {
        return subscriptionDao.listFlow().asLiveData()
    }

    fun getSubscriptions(): List<Subscription> {
        return subscriptionDao.list()
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun getSubscription(baseUrl: String, topic: String): Subscription? {
        return subscriptionDao.get(baseUrl, topic)
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

    fun onlyNewNotifications(subscriptionId: Long, notifications: List<Notification>): List<Notification> {
        val existingIds = notificationDao.listIds(subscriptionId)
        return notifications.filterNot { existingIds.contains(it.id) }
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addNotification(subscriptionId: Long, notification: Notification) {
        val maybeExistingNotification = notificationDao.get(notification.id)
        if (maybeExistingNotification != null) {
            return
        }

        val subscription = subscriptionDao.get(subscriptionId) ?: return
        val newSubscription = subscription.copy(notifications = subscription.notifications + 1, lastActive = Date().time/1000)
        subscriptionDao.update(newSubscription)
        notificationDao.add(notification)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun removeNotification(subscriptionId: Long, notificationId: String) {
        val subscription = subscriptionDao.get(subscriptionId) ?: return
        val newSubscription = subscription.copy(notifications = subscription.notifications - 1, lastActive = Date().time/1000)
        subscriptionDao.update(newSubscription)
        notificationDao.remove(notificationId)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    fun removeAllNotifications(subscriptionId: Long) {
        notificationDao.removeAll(subscriptionId)
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
