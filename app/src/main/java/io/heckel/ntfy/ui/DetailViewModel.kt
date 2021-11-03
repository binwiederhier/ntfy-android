package io.heckel.ntfy.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.data.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DetailViewModel(private val repository: Repository) : ViewModel() {
    fun list(subscriptionId: Long): LiveData<List<Notification>> {
        return repository.getAllNotifications(subscriptionId)
    }

    fun add(notification: Notification) = viewModelScope.launch(Dispatchers.IO) {
        repository.addNotification(notification)
    }

    fun remove(notificationId: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.removeNotification(notificationId)
    }

    fun removeAll(subscriptionId: Long) = viewModelScope.launch(Dispatchers.IO) {
        repository.removeAllNotifications(subscriptionId)
    }
}

class DetailViewModelFactory(private val repository: Repository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>) =
        with(modelClass){
            when {
                isAssignableFrom(DetailViewModel::class.java) -> DetailViewModel(repository) as T
                else -> throw IllegalArgumentException("Unknown viewModel class $modelClass")
            }
        }
}
