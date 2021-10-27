package io.heckel.ntfy

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.heckel.ntfy.data.*
import kotlin.collections.List


class SubscriptionViewModel(private val repository: Repository, private val connectionManager: ConnectionManager) : ViewModel() {
    fun add(topic: Subscription) {
        repository.add(topic)
        connectionManager.start(topic, viewModelScope)
    }

    fun get(id: Long) : Subscription? {
        return repository.get(id)
    }

    fun list(): LiveData<List<Subscription>> {
        return repository.list()
    }

    fun remove(topic: Subscription) {
        repository.remove(topic)
        connectionManager.stop(topic)
    }

    fun setListener(listener: NotificationListener) {
        connectionManager.setListener(object : ConnectionListener {
            override fun onStatusChanged(subcriptionId: Long, status: Status) {
                println("onStatusChanged($subcriptionId, $status)")
                val topic = repository.get(subcriptionId)
                if (topic != null) {
                    println("-> old topic: $topic")
                    repository.update(topic.copy(status = status))
                }
            }

            override fun onNotification(subscriptionId: Long, notification: Notification) {
                println("onNotification($subscriptionId, $notification)")
                val topic = repository.get(subscriptionId)
                if (topic != null) {
                    println("-> old topic: $topic")
                    repository.update(topic.copy(messages = topic.messages + 1))
                }
                listener.onNotification(subscriptionId, notification) // Forward downstream
            }
        })
    }
}

class SubscriptionsViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>) =
        with(modelClass){
            when {
                isAssignableFrom(SubscriptionViewModel::class.java) -> {
                    val repository = Repository.getInstance()
                    val connectionManager = ConnectionManager.getInstance()
                    SubscriptionViewModel(repository, connectionManager) as T
                }
                else -> throw IllegalArgumentException("Unknown viewModel class $modelClass")
            }
        }
}
