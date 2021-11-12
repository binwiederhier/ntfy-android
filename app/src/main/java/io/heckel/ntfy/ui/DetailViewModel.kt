package io.heckel.ntfy.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.heckel.ntfy.data.Notification
import io.heckel.ntfy.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DetailViewModel(private val repository: Repository) : ViewModel() {
    fun list(subscriptionId: Long): LiveData<List<Notification>> {
        return repository.getNotificationsLiveData(subscriptionId)
    }

    fun remove(subscriptionId: Long, notificationId: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.removeNotification(subscriptionId, notificationId)
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
