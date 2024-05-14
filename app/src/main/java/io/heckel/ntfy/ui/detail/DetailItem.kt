package io.heckel.ntfy.ui.detail

import io.heckel.ntfy.db.Notification


sealed class DetailItem


data class NotificationItem(
    val notification: Notification,
    var isSelected: Boolean,
) : DetailItem()


object UnreadDividerItem : DetailItem()
