package io.heckel.ntfy.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.combineWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DetailViewModel(private val repository: Repository) : ViewModel() {
    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun list(subscriptionId: Long): LiveData<List<Notification>> {
        return repository.getNotificationsLiveData(subscriptionId)
    }

    fun listFiltered(subscriptionId: Long): LiveData<List<Notification>> {
        return repository.getNotificationsLiveData(subscriptionId)
            .combineWith(_searchQuery) { notifications, query ->
                if (query.isNullOrBlank()) {
                    notifications.orEmpty()
                } else {
                    val q = query.lowercase()
                    notifications.orEmpty().filter { n ->
                        n.title.lowercase().contains(q) ||
                        n.message.lowercase().contains(q) ||
                        n.tags.lowercase().contains(q)
                    }
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
