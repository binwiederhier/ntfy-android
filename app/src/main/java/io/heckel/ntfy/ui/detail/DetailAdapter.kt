package io.heckel.ntfy.ui.detail

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import kotlinx.coroutines.CoroutineScope


class DetailAdapter(
    private val activity: Activity,
    private val lifecycleScope: CoroutineScope,
    private val repository: Repository,
    private val onClick: (Notification) -> Unit,
    private val onLongClick: (Notification) -> Unit
) : ListAdapter<Notification, DetailViewHolder>(TopicDiffCallback) {
    val selected = mutableSetOf<String>() // Notification IDs

    /* Creates and inflates view and return TopicViewHolder. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DetailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_detail_item, parent, false)
        return DetailViewHolder(activity, lifecycleScope, repository, view, selected, onClick, onLongClick)
    }

    /* Gets current topic and uses it to bind view. */
    override fun onBindViewHolder(holder: DetailViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun get(position: Int): Notification {
        return getItem(position)
    }

    fun toggleSelection(notificationId: String) {
        if (selected.contains(notificationId)) {
            selected.remove(notificationId)
        } else {
            selected.add(notificationId)
        }

        if (selected.size != 0) {
            val listIds = currentList.map { notification -> notification.id }
            val notificationPosition = listIds.indexOf(notificationId)
            notifyItemChanged(notificationPosition)
        }
    }

    object TopicDiffCallback : DiffUtil.ItemCallback<Notification>() {
        override fun areItemsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        const val TAG = "NtfyDetailAdapter"
    }
}
