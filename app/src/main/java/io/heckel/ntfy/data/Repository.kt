package io.heckel.ntfy.data

import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData

class Repository(private val subscriptionDao: SubscriptionDao) {
    fun list(): LiveData<List<Subscription>> {
        return subscriptionDao.list().asLiveData()
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun get(baseUrl: String, topic: String): Subscription? {
        return subscriptionDao.get(baseUrl, topic)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun add(subscription: Subscription) {
        subscriptionDao.add(subscription)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun update(subscription: Subscription) {
        subscriptionDao.update(subscription)
    }

    @Suppress("RedundantSuspendModifier")
    @WorkerThread
    suspend fun remove(subscription: Subscription) {
        subscriptionDao.remove(subscription)
    }

    companion object {
        private var instance: Repository? = null

        fun getInstance(subscriptionDao: SubscriptionDao): Repository {
            return synchronized(Repository::class) {
                val newInstance = instance ?: Repository(subscriptionDao)
                instance = newInstance
                newInstance
            }
        }
    }
}
