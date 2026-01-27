package io.heckel.ntfy.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DetailViewModel(private val repository: Repository) : ViewModel() {
    private val searchQuery = MutableLiveData("")

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun hasSearchQuery(): Boolean = !searchQuery.value.isNullOrBlank()

    fun list(subscriptionId: Long): LiveData<List<Notification>> {
        return repository.getNotificationsLiveData(subscriptionId)
    }

    fun listFiltered(subscriptionId: Long): LiveData<List<Notification>> {
        return searchQuery.switchMap { query ->
            if (query.isNullOrBlank()) {
                repository.getNotificationsLiveData(subscriptionId)
            } else {
                repository.getNotificationsFilteredLiveData(subscriptionId, query)
            }
        }
    }

    fun markAsDeleted(notificationId: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.markAsDeleted(notificationId)
    }
}

class DetailViewModelFactory(private val repository: Repository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        with(modelClass){
            when {
                isAssignableFrom(DetailViewModel::class.java) -> DetailViewModel(repository) as T
                else -> throw IllegalArgumentException("Unknown viewModel class $modelClass")
            }
        }
}
