package io.heckel.ntfy.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.heckel.ntfy.db.*
import io.heckel.ntfy.up.Distributor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubscriptionsViewModel(private val repository: Repository) : ViewModel() {
    fun list(): LiveData<List<Subscription>> {
        return repository.getSubscriptionsLiveData()
    }

    fun listIdsWithInstantStatus(): LiveData<Set<Pair<Long, Boolean>>> {
        return repository.getSubscriptionIdsWithInstantStatusLiveData()
    }

    fun add(subscription: Subscription) = viewModelScope.launch(Dispatchers.IO) {
        repository.addSubscription(subscription)
    }

    fun remove(context: Context, subscriptionId: Long) = viewModelScope.launch(Dispatchers.IO) {
        val subscription = repository.getSubscription(subscriptionId) ?: return@launch
        if (subscription.upAppId != null && subscription.upConnectorToken != null) {
            val distributor = Distributor(context)
            distributor.sendUnregistered(subscription.upAppId, subscription.upConnectorToken)
        }
        repository.removeAllNotifications(subscriptionId)
        repository.removeSubscription(subscriptionId)
        if (subscription.icon != null) {
            val resolver = context.applicationContext.contentResolver
            try {
                resolver.delete(Uri.parse(subscription.icon), null, null)
            } catch (_: Exception) {
                // Don't care
            }
        }
    }

    suspend fun get(baseUrl: String, topic: String): Subscription? {
        return repository.getSubscription(baseUrl, topic)
    }
}

class SubscriptionsViewModelFactory(private val repository: Repository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        with(modelClass){
            when {
                isAssignableFrom(SubscriptionsViewModel::class.java) -> SubscriptionsViewModel(repository) as T
                else -> throw IllegalArgumentException("Unknown viewModel class $modelClass")
            }
        }
}
