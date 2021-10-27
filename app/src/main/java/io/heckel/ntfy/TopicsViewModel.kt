package io.heckel.ntfy

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.data.Topic
import kotlin.collections.List

data class Notification(val topic: String, val message: String)
typealias NotificationListener = (notification: Notification) -> Unit

class TopicsViewModel(private val repository: Repository) : ViewModel() {
    fun add(topic: Topic) {
        repository.add(topic, viewModelScope)
    }

    fun get(id: Long) : Topic? {
        return repository.get(id)
    }

    fun list(): LiveData<List<Topic>> {
        return repository.list()
    }

    fun remove(topic: Topic) {
        repository.remove(topic)
    }

    fun setNotificationListener(listener: NotificationListener) {
        repository.setNotificationListener(listener)
    }
}

class TopicsViewModelFactory() : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>) =
        with(modelClass){
            when {
                isAssignableFrom(TopicsViewModel::class.java) -> TopicsViewModel(Repository.getInstance()) as T
                else -> throw IllegalArgumentException("Unknown viewModel class $modelClass")
            }
        }
}
