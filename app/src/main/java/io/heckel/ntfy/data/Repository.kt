package io.heckel.ntfy.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class Repository {
    private val subscriptions = mutableListOf<Subscription>()
    private val subscriptionsLiveData: MutableLiveData<List<Subscription>> = MutableLiveData(subscriptions)

    fun add(subscription: Subscription) {
        synchronized(subscriptions) {
            subscriptions.add(subscription)
            subscriptionsLiveData.postValue(ArrayList(subscriptions)) // Copy!
        }
    }

    fun update(subscription: Subscription) {
        synchronized(subscriptions) {
            val index = subscriptions.indexOfFirst { it.id == subscription.id } // Find index by Topic ID
            if (index == -1) return
            subscriptions[index] = subscription
            subscriptionsLiveData.postValue(ArrayList(subscriptions)) // Copy!
        }
    }

    fun remove(subscription: Subscription) {
        synchronized(subscriptions) {
            if (subscriptions.remove(subscription)) {
                subscriptionsLiveData.postValue(ArrayList(subscriptions)) // Copy!
            }
        }
    }

    fun get(id: Long): Subscription? {
        synchronized(subscriptions) {
            return subscriptions.firstOrNull { it.id == id } // Find index by Topic ID
        }
    }

    fun list(): LiveData<List<Subscription>> {
        return subscriptionsLiveData
    }

    companion object {
        private var instance: Repository? = null

        fun getInstance(): Repository {
            return synchronized(Repository::class) {
                val newInstance = instance ?: Repository()
                instance = newInstance
                newInstance
            }
        }
    }
}
