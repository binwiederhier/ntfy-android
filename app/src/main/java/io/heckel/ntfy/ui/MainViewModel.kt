package io.heckel.ntfy.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.heckel.ntfy.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.collections.List

class SubscriptionsViewModel(private val repository: Repository) : ViewModel() {
    fun list(): LiveData<List<Subscription>> {
        return repository.getSubscriptionsLiveData()
    }

    fun listIds(): LiveData<Set<Long>> {
        return repository.getSubscriptionIdsLiveData()
    }

    fun add(subscription: Subscription) = viewModelScope.launch(Dispatchers.IO) {
        repository.addSubscription(subscription)
    }

    fun remove(subscriptionId: Long) = viewModelScope.launch(Dispatchers.IO) {
        repository.removeAllNotifications(subscriptionId)
        repository.removeSubscription(subscriptionId)
    }

    suspend fun get(baseUrl: String, topic: String): Subscription? {
        return repository.getSubscription(baseUrl, topic)
    }
}

class SubscriptionsViewModelFactory(private val repository: Repository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>) =
        with(modelClass){
            when {
                isAssignableFrom(SubscriptionsViewModel::class.java) -> SubscriptionsViewModel(repository) as T
                else -> throw IllegalArgumentException("Unknown viewModel class $modelClass")
            }
        }
}
