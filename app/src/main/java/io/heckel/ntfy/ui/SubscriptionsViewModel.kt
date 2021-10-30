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
        return repository.list()
    }

    fun add(topic: Subscription) = viewModelScope.launch(Dispatchers.IO) {
        repository.add(topic)
    }

    fun remove(topic: Subscription) = viewModelScope.launch(Dispatchers.IO) {
        repository.remove(topic)
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
