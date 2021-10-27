package io.heckel.ntfy

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.heckel.ntfy.data.*
import kotlin.collections.List

class SubscriptionsViewModel(private val repository: Repository, private val connectionManager: ConnectionManager) : ViewModel() {
    fun add(topic: Subscription) {
        repository.add(topic)
        connectionManager.start(topic)
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
        connectionManager.setListener(listener)
    }
}

class SubscriptionsViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>) =
        with(modelClass){
            when {
                isAssignableFrom(SubscriptionsViewModel::class.java) -> {
                    val repository = Repository.getInstance()
                    val connectionManager = ConnectionManager.getInstance(repository)
                    SubscriptionsViewModel(repository, connectionManager) as T
                }
                else -> throw IllegalArgumentException("Unknown viewModel class $modelClass")
            }
        }
}
