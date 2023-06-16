package io.heckel.ntfy.ui.detail

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import kotlinx.coroutines.CoroutineScope


class DetailAdapter(
    private val activity: Activity,
    private val lifecycleScope: CoroutineScope,
    private val repository: Repository,
    private val onClick: (Notification) -> Unit,
    private val onLongClick: (Notification) -> Unit
) : ListAdapter<DetailItem, DetailItemViewHolder>(TopicDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DetailItemViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> {
                val itemView = inflater.inflate(NotificationItemViewHolder.LAYOUT, parent, false)
                NotificationItemViewHolder(activity, lifecycleScope, repository, onClick, onLongClick, itemView)
            }
            1 -> {
                val itemView = inflater.inflate(UnreadDividerItemViewHolder.LAYOUT, parent, false)
                UnreadDividerItemViewHolder(itemView)
            }
            else -> throw IllegalStateException("Unknown viewType $viewType in DetailAdapter")
        }
    }

    override fun onBindViewHolder(holder: DetailItemViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // original method in ListAdapter is protected
    public override fun getItem(position: Int): DetailItem = super.getItem(position)

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is NotificationItem -> 0
            is UnreadDividerItem -> 1
        }
    }

    /* Take a list of notifications, insert the unread divider if necessary,
       and call submitList for the ListAdapter to do its diff magic */
    fun submitNotifications(newList: List<Notification>) {
        val selectedLocal = selectedNotificationIds
        val detailList: MutableList<DetailItem> = newList.map { notification ->
            NotificationItem(notification, selectedLocal.contains(notification.id))
        }.toMutableList()

        val lastUnreadIndex = newList.indexOfLast { notification -> notification.isUnread }
        if (lastUnreadIndex != -1) {
            detailList.add(lastUnreadIndex + 1, UnreadDividerItem)
        }
        submitList(detailList.toList())
    }

    val selectedNotificationIds
        get() = currentList
            .filterIsInstance<NotificationItem>()
            .filter { it.isSelected }
            .map { it.notification.id }

    fun clearSelection() {
        currentList.forEachIndexed { index, detailItem ->
            if (detailItem is NotificationItem && detailItem.isSelected) {
                detailItem.isSelected = false
                notifyItemChanged(index)
            }
        }
    }

    fun toggleSelection(notificationId: String) {
        currentList.forEachIndexed { index, detailItem ->
            if (detailItem is NotificationItem && detailItem.notification.id == notificationId) {
                detailItem.isSelected = !detailItem.isSelected
                notifyItemChanged(index)
            }
        }
    }

    object TopicDiffCallback : DiffUtil.ItemCallback<DetailItem>() {
        override fun areItemsTheSame(oldItem: DetailItem, newItem: DetailItem): Boolean {
            return if (oldItem is NotificationItem && newItem is NotificationItem) {
                oldItem.notification.id == newItem.notification.id
            } else {
                oldItem is UnreadDividerItem && newItem is UnreadDividerItem
            }
        }

        override fun areContentsTheSame(oldItem: DetailItem, newItem: DetailItem): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        const val TAG = "NtfyDetailAdapter"
    }
}
