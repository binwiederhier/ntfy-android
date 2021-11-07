package io.heckel.ntfy.data

import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.flow.Flow

class Repository(private val subscriptionDao: SubscriptionDao, private val notificationDao: NotificationDao) {
    fun getAllSubscriptions(): LiveData<List<Subscription>> {
        return subscriptionDao.list().asLiveData()
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

    fun getAllNotifications(subscriptionId: Long): LiveData<List<Notification>> {
        return notificationDao.list(subscriptionId).asLiveData()
    }

    fun getAllNotificationIds(subscriptionId: Long): List<String> {
        return notificationDao.listIds(subscriptionId)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun addNotification(notification: Notification) {
        notificationDao.add(notification)
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

    companion object {
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
