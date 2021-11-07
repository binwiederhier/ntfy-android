package io.heckel.ntfy.msg

import com.google.gson.Gson
import io.heckel.ntfy.data.Notification

class NotificationParser {
    private val gson = Gson()

    fun fromString(subscriptionId: Long, s: String): Notification {
        val n = gson.fromJson(s, NotificationData::class.java) // Indirection to prevent accidental field renames, etc.
        return Notification(n.id, subscriptionId, n.time, n.message)
    }

    private data class NotificationData(
        val id: String,
        val time: Long,
        val message: String
    )
}
